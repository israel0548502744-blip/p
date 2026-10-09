"""The censorship pipeline — analysis and rendering are separate stages.

Analysis (``analyze``) — analysis resolution, streamed:
    decode → scene cuts → optical flow
      ├─ person detection (YOLOX-tiny)  ─► person tracking (IoU + appearance, re-id)
      │                                        └─► gender classification per track (face → FaceRes votes)
      ├─ skin segmentation (Selfie Multiclass, keyframes + flow propagation) ─► anti-flicker fusion
      └─ sensitive regions (NudeNet, batched) ─► region tracking ─► assigned to their person
    → compressed skin masks on disk + per-frame person/region records

Rendering (``render``) — original resolution, streamed:
    per-person censor decision (gender label + confidence threshold + fallback + manual override)
    → per-frame mask = skin pixels / sensitive regions *owned by censored people*
    → look-ahead → feather → blue composite → FFmpeg (H.264, original FPS, original audio)

Because the analysis is kept, changing a person's override (or the threshold,
colour, softness…) only re-runs rendering.
"""

from __future__ import annotations

import logging
import threading
import time
from collections import deque
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Callable, Optional

import cv2
import numpy as np

from . import media, outlines
from .censor import BlueCensor, hex_to_bgr
from .config import ANALYSIS_MAX_SIDE, MASK_MAX_SIDE, WORK_DIR
from .detectors import (ROI_FULL_EVERY_DET, SegResult, AGGRESSIVE_LABELS, FACE_LABELS, SENSITIVE_LABELS, ClothesSegmenter,
                        Detection, SensitiveRegionDetector, SkinSegmenter, color_skin_probability, roi_crops,
                        skin_color_plausible, veto_blobs)
from . import gender as gender_mod
from .gender import GenderClassifier, censor_decision
from .maskstore import MaskStore
from .people import PersonTrack, PersonTracker
from .persons import PersonDetector
from .tracking import BoxTracker, FlowEstimator, TemporalFuser, _iou, scene_cut

log = logging.getLogger("blueshield.pipeline")

SPEED_PRESETS = {
    # seg_stride: run skin segmentation every N frames (flow-propagated between)
    # det_stride: run person / sensitive-region detectors every N frames (tracked between)
    "quality": {"seg_stride": 1, "det_stride": 2},
    "balanced": {"seg_stride": 2, "det_stride": 3},
    "fast": {"seg_stride": 4, "det_stride": 6},
}
TARGETS = ("female", "everyone")
POLICIES = ("censor", "keep")


@dataclass
class CensorSettings:
    color: str = "#FFFFFF"
    sensitivity: int = 60  # 0..100
    softness: int = 20  # 0..100
    aggressive: bool = False
    animated: bool = False
    include_face: bool = False
    speed: str = "quality"  # quality = every frame, per-person high-resolution skin segmentation
    quality: str = "balanced"
    keep_audio: bool = True
    # who is censored
    target: str = "female"  # 'female' = only people classified as women | 'everyone'
    gender_threshold: int = 70  # % confidence needed to call someone female / male
    uncertain_policy: str = "censor"  # what to do with people the classifier is unsure about
    # 'color' = solid colour | 'clothing' = continue the garment over the skin (Android; the desktop renders colour)
    fill: str = "color"

    def validate(self) -> "CensorSettings":
        hex_to_bgr(self.color)
        self.sensitivity = int(min(100, max(0, self.sensitivity)))
        self.softness = int(min(100, max(0, self.softness)))
        self.gender_threshold = int(min(99, max(51, self.gender_threshold)))
        if self.speed not in SPEED_PRESETS:
            self.speed = "balanced"
        if self.quality not in media.QUALITY_CRF:
            self.quality = "balanced"
        if self.target not in TARGETS:
            self.target = "female"
        if self.uncertain_policy not in POLICIES:
            self.uncertain_policy = "censor"
        if self.fill not in ("color", "clothing"):
            self.fill = "color"
        return self

    def to_dict(self) -> dict:
        return asdict(self)


class Cancelled(Exception):
    pass


@dataclass
class Progress:
    stage: str = "queued"  # queued|analyzing|detecting|applying|encoding|complete|error|cancelled
    percent: float = 0.0
    frame: int = 0
    total_frames: int = 0
    pass_index: int = 0  # 1 = analysis, 2 = rendering
    eta_seconds: Optional[float] = None
    fps: float = 0.0
    elapsed: float = 0.0
    message: str = ""
    timeline: list[float] = field(default_factory=list)  # censored-area per time bucket


class Control:
    """Pause / cancel flags shared between the API and the worker thread."""

    def __init__(self) -> None:
        self._resume = threading.Event()
        self._resume.set()
        self.cancelled = False
        self.paused_time = 0.0

    def pause(self) -> None:
        self._resume.clear()

    def resume(self) -> None:
        self._resume.set()

    @property
    def paused(self) -> bool:
        return not self._resume.is_set()

    def cancel(self) -> None:
        self.cancelled = True
        self._resume.set()

    def reset(self) -> None:
        self.cancelled = False
        self._resume.set()
        self.paused_time = 0.0

    def checkpoint(self) -> None:
        if not self._resume.is_set():
            t0 = time.monotonic()
            while not self._resume.wait(0.25):
                pass
            self.paused_time += time.monotonic() - t0
        if self.cancelled:
            raise Cancelled()


ANALYSIS_WEIGHT = 0.7  # share of the progress bar used by analysis on a full run
TIMELINE_BUCKETS = 240


class Engine:
    """Holds loaded models so successive jobs don't pay the start-up cost again."""

    def __init__(self) -> None:
        self._m: dict[str, object] = {}
        self._lock = threading.Lock()

    def _get(self, key: str, factory):
        with self._lock:
            if key not in self._m:
                self._m[key] = factory()
            return self._m[key]

    def models(self) -> tuple[SkinSegmenter, SensitiveRegionDetector]:
        return self._get("seg", SkinSegmenter), self._get("nude", SensitiveRegionDetector)

    def people_models(self) -> tuple[PersonDetector, GenderClassifier]:
        return self._get("person", PersonDetector), self._get("gender", GenderClassifier)

    def outlines(self, size: int) -> outlines.PersonMasks:
        return self._get(f"outlines{size}", lambda: outlines.PersonMasks(size))

    def clothes(self) -> ClothesSegmenter:
        return self._get("clothes", ClothesSegmenter)


@dataclass
class FrameRecord:
    """Per-frame analysis output. Coordinates are normalised to 0..1."""

    persons: np.ndarray  # (n, 5): tid, x1, y1, x2, y2
    regions: np.ndarray  # (m, 6): owner tid (-1 = none), x1, y1, x2, y2, strength


_EMPTY_P = np.zeros((0, 5), np.float32)
_EMPTY_R = np.zeros((0, 6), np.float32)


@dataclass
class Analysis:
    info: media.VideoInfo
    settings: CensorSettings
    store: MaskStore
    records: list[FrameRecord]
    people: dict[int, PersonTrack]
    total: int
    elapsed: float = 0.0
    aliases: dict[int, int] = field(default_factory=dict)  # merged duplicate tid -> surviving tid

    def close(self) -> None:
        try:
            self.store.close()
        except Exception:
            pass


def person_summary(t: PersonTrack, settings: CensorSettings, override: str, fps: float) -> dict:
    label = t.gender.label(settings.gender_threshold / 100.0)
    return {
        "id": t.tid,
        "gender": label,
        "p_female": round(t.gender.p_female, 3),
        "confidence": round(t.gender.confidence(), 3),
        "votes": t.gender.votes,
        "censored": censor_decision(label, settings.target, settings.uncertain_policy, override, t.gender.is_child),
        "override": override,
        "age": None if t.gender.age_median is None else round(t.gender.age_median, 1),
        "child": t.gender.is_child,
        "frames": t.frames,
        "start": round(t.first_frame / max(fps, 1e-6), 2),
        "end": round(t.last_frame / max(fps, 1e-6), 2),
        "has_thumbnail": t.thumb is not None,
    }


class _Meter:
    """Progress, ETA and speed bookkeeping shared by both stages."""

    def __init__(self, progress: Progress, control: Control, total: int, span: tuple[float, float],
                 pass_index: int) -> None:
        self.p, self.c, self.total, self.span, self.pass_index = progress, control, total, span, pass_index
        self.t0 = time.monotonic()
        self.paused0 = control.paused_time
        self.recent: deque = deque(maxlen=60)
        self.fps_hist: deque = deque(maxlen=30)

    def update(self, done: int) -> None:
        frac = min(1.0, done / max(self.total, 1))
        lo, hi = self.span
        overall = lo + frac * (hi - lo)
        elapsed = time.monotonic() - self.t0 - (self.c.paused_time - self.paused0)
        p = self.p
        p.frame, p.total_frames, p.pass_index = done, self.total, self.pass_index
        p.percent = round(overall * 100, 2)
        self.recent.append((elapsed, overall))
        if len(self.recent) >= 2 and self.recent[-1][1] > self.recent[0][1]:
            rate = (self.recent[-1][1] - self.recent[0][1]) / max(1e-6, self.recent[-1][0] - self.recent[0][0])
            p.eta_seconds = max(0.0, (1 - overall) / rate)
        self.fps_hist.append((elapsed, done))
        if len(self.fps_hist) >= 2 and self.fps_hist[-1][0] > self.fps_hist[0][0]:
            p.fps = round((self.fps_hist[-1][1] - self.fps_hist[0][1]) / (self.fps_hist[-1][0] - self.fps_hist[0][0]), 1)

    @property
    def elapsed(self) -> float:
        return time.monotonic() - self.t0 - (self.c.paused_time - self.paused0)


class _Previewer:
    def __init__(self, cb: Callable[[np.ndarray], None]) -> None:
        self.cb, self.last = cb, 0.0

    def __call__(self, fn: Callable[[], np.ndarray]) -> None:
        now = time.monotonic()
        if now - self.last > 0.6:
            self.last = now
            try:
                self.cb(fn())
            except Exception:  # preview must never break processing
                log.exception("preview failed")


# ═══════════════════════════════════ analysis ═══════════════════════════════════

def analyze(engine: Engine, info: media.VideoInfo, settings: CensorSettings, control: Control,
            progress: Progress, on_preview: Callable[[np.ndarray], None], job_id: str,
            span: tuple[float, float] = (0.0, 1.0)) -> Analysis:
    progress.stage = "analyzing"
    progress.message = "Loading models…"
    progress.total_frames = info.frames
    seg_model, det_model = engine.models()
    person_model, gender_model = engine.people_models()
    control.checkpoint()

    preset = SPEED_PRESETS[settings.speed]
    seg_stride = 1 if settings.aggressive else preset["seg_stride"]
    det_stride = max(1, preset["det_stride"] - (1 if settings.aggressive else 0))
    sens = settings.sensitivity / 100.0
    skin_threshold = 0.72 - 0.42 * sens - (0.08 if settings.aggressive else 0.0)
    det_min_score = 0.50 - 0.30 * sens  # NudeNet: 0.50 .. 0.20
    person_min_score = 0.45 - 0.20 * sens  # YOLOX: 0.45 .. 0.25
    labels = AGGRESSIVE_LABELS if settings.aggressive else SENSITIVE_LABELS
    reclassify_every = max(det_stride, int(round(info.fps)))  # once a second after enough votes

    aw, ah = media.scaled_size(info.width, info.height, ANALYSIS_MAX_SIDE)
    mw, mh = media.scaled_size(info.width, info.height, MASK_MAX_SIDE)
    store = MaskStore(WORK_DIR / f"{job_id}.masks", mw, mh)
    records: list[FrameRecord] = []
    meter = _Meter(progress, control, info.frames, span, 1)
    preview = _Previewer(on_preview)
    timeline_sum = np.zeros(TIMELINE_BUCKETS)
    timeline_cnt = np.zeros(TIMELINE_BUCKETS)

    progress.stage = "detecting"
    progress.message = "Detecting people and sensitive regions…"
    flow = FlowEstimator(aw, ah)
    outline_size = PM_VIDEO_SIZE if settings.speed in PM_SPEEDS else 0
    outline_state = {"model": engine.outlines(outline_size) if outline_size else None, "masks": {},
                     "since": 10 ** 9, "every": max(1, int(round(PM_EVERY_SEC * info.fps)))}
    regions = BoxTracker(max_misses=3 if not settings.aggressive else 5)
    faces = BoxTracker(max_misses=2, smooth=0.6)  # face boxes, only used to keep faces uncensored
    people = PersonTracker(max_misses=max(3, int(round(1.5 * info.fps / det_stride))),
                           gallery_frames=int(round(12 * info.fps)))
    fuser = TemporalFuser(memory=FUSER_MEMORY, lift=FUSER_LIFT_AGGRESSIVE if settings.aggressive else FUSER_LIFT,
                          on_threshold=skin_threshold)
    last_cls: dict[int, int] = {}
    state = {"prev_small": None, "last_skin": None, "since_seg": 10 ** 9, "analyzed": 0, "face_map": None,
             "last_person": None, "pending_faces": None}
    # the clothes veto (shared/pipeline.json "clothes_veto"): the clothes model's latest map, carried along with the
    # motion between refreshes, the track ids it was computed for, and freshly segmented frames since
    veto = {"model": engine.clothes() if VETO_ENABLED and VETO_VIDEO_EVERY > 0 else None, "map": None, "ids": set(),
            "since": 10 ** 9}

    def clothes_map(frame: np.ndarray, tracks: list, cut: bool) -> np.ndarray:
        """The clothes model's map for this freshly segmented frame: recomputed on the whole-person crops of the
        close-up skin pass every VETO_VIDEO_EVERY frames, after a cut, or when someone new is to be covered; carried
        along with the motion otherwise. Same as the Android ``Analyzer.clothesMaps``."""
        ids = {t.tid for t in tracks}
        veto["since"] += 1
        if veto["map"] is not None and not cut and veto["since"] < max(1, VETO_VIDEO_EVERY) and ids <= veto["ids"]:
            veto["map"] = flow.warp(veto["map"])
            return veto["map"]
        crops = [c for c in (roi_crops(tuple(t.box))[0] for t in tracks) if c[2] >= ROI_MIN_SIDE]
        veto["map"], veto["ids"], veto["since"] = veto["model"].segment(frame, crops), ids, 0
        return veto["map"]

    def own_faces(frame: np.ndarray, nude_faces: list) -> list:
        """Each visible person's own face found by the face detector in their head area, except those the
        sensitive-region detector already has. A real face is facial skin to the segmenter too (a cartoon face on
        a balloon, a printed T-shirt is not): without that check it would uncover the hand holding it. Same as
        the Android ``Analyzer.ownFaces``."""
        if settings.include_face:
            return []
        seen = [t for t in people.visible() if not t.misses]
        face_map = state["face_map"]
        out = []
        for t in seen:
            b = gender_model.face_box(frame, tuple(t.box), face_map, others=[tuple(o.box) for o in seen if o is not t])
            if b is None or any(_iou(d.box, b) > 0.3 for d in nude_faces):
                continue
            if face_map is None or mean_in(face_map, b) < OWN_FACE_SKIN:
                continue
            out.append(Detection("FACE", 0.5, b))
        return out

    CHUNK = 8 * det_stride  # frames buffered so detector keyframes can be batched

    def process_chunk(frames: list[np.ndarray], start_index: int) -> None:
        det_idx = [k for k in range(len(frames)) if (start_index + k) % det_stride == 0]
        nude = dict(zip(det_idx, det_model.detect_batch([frames[k] for k in det_idx], det_min_score)))
        for k, frame in enumerate(frames):
            control.checkpoint()
            idx = start_index + k
            small = cv2.resize(frame, (64, 36), interpolation=cv2.INTER_AREA)
            cut = scene_cut(state["prev_small"], small)
            state["prev_small"] = small
            if cut:
                flow.reset()
                fuser.reset()
                regions.reset()
                faces.reset()
                people.reset(idx)
            flow.update(frame)

            # ── stage 1+3: person detection & tracking (before segmentation: the boxes drive per-person ROIs) ──
            people.predict(flow)
            regions.predict(flow)
            faces.predict(flow)
            if k in nude:
                people.update(frame, person_model.detect(frame, person_min_score), idx,
                              faces=[tuple(d.box) for d in nude[k] if d.label in FACE_LABELS])
                regions.update([d for d in nude[k] if d.label in labels])
                # faces to keep uncovered: the sensitive-region detector's, plus each person's own face found by the
                # face detector in their head area (it finds the small, turned and dim faces the other one misses).
                # The own faces are checked against the facial-skin map: on the first frame of a shot there is none
                # yet (or only the previous shot's), so they wait for this frame's (below).
                nude_faces = [d for d in nude[k] if d.label in FACE_LABELS]
                if not settings.include_face and (state["face_map"] is None or cut):
                    state["pending_faces"] = nude_faces
                else:
                    faces.update(nude_faces + own_faces(frame, nude_faces))

            # ── skin segmentation: whole frame on detection keyframes, per-person crops on every analysed frame ──
            boxes = [tuple(t.box) for t in people.visible()]
            fast_motion = False
            if flow.flow_full is not None and state["since_seg"] >= 1:
                fast_motion = float(np.abs(flow.flow_full[::8, ::8]).mean()) > 0.012 * max(aw, ah)
            fresh = state["last_skin"] is None or cut or state["since_seg"] + 1 >= seg_stride or fast_motion
            if fresh:
                # (with nobody in view, every detection round — the motion-carried mask covers the frames between)
                full_due = (state["last_skin"] is None or cut
                            or (k in nude and (not boxes or (start_index + k) // det_stride % ROI_FULL_EVERY_DET == 0)))
                if full_due:
                    seg = seg_model.segment(frame, include_face=settings.include_face, tiled=settings.aggressive)
                else:  # between whole-frame passes: carry the last result along with the motion
                    seg = SegResult(flow.warp(state["last_skin"]), flow.warp(state["last_person"]),
                                    flow.warp(state["face_map"]))
                # the close-up per-person pass only for people who are (still) to be censored, or not decided yet: a
                # man already recognised, or a small child, is never covered, so his skin needn't be found in detail
                th = settings.gender_threshold / 100.0
                roi_tracks = [t for t in people.visible() if _roi_wanted(t, settings, th)]
                roi_boxes = [tuple(t.box) for t in roi_tracks]
                if roi_boxes:
                    seg = seg_model.segment_rois(frame, roi_boxes, seg, include_face=settings.include_face,
                                                 base_is_fresh=full_due, orphan_skin=skin_threshold)
                skin = seg.skin
                clothes = clothes_map(frame, roi_tracks, cut) if veto["model"] is not None else None
                if settings.aggressive:
                    person_px = cv2.dilate((seg.person > 0.4).astype(np.uint8), np.ones((9, 9), np.uint8))
                    backup = color_skin_probability(frame) * person_px * 0.9
                    if not settings.include_face:
                        face = cv2.dilate((seg.face > 0.3).astype(np.uint8), np.ones((15, 15), np.uint8))
                        backup *= 1 - face
                    skin = np.maximum(skin, backup)
                if clothes is not None:  # the clothes veto: blobs of "skin" lying largely on clothing go
                    skin = veto_blobs(skin.astype(np.float32), seg.skin, clothes, VETO_CLOTHES_MIN, VETO_SKIN_MAX,
                                      skin_threshold, VETO_BLOB_SHARE, int(round(VETO_BLOB_RIM * float(np.hypot(aw, ah)))))
                # edges: re-fit the coarse (256 px model) probability to the frame's own outlines — first the pixels
                # near the boundary by colour (this frame's skin vs. what surrounds it), then a guided filter
                rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                band = max(2, int(round(REFINE_COLOR_BAND * float(np.hypot(aw, ah)))))
                skin = outlines.recolour(skin.astype(np.float32), rgb, band, REFINE_COLOR_KEEP)
                skin = guided_filter(outlines.guide(rgb), skin.astype(np.float32), max(2, int(round(REFINE_RADIUS * (aw + ah)))), REFINE_EPS)
                skin = skin * skin_color_plausible(frame)
                # skin only counts on a person: skin-coloured objects (wood, a mug, a lamp) are not people
                skin = skin * person_gate(seg.person, [tuple(b) for b in boxes], aw, ah)
                state["last_skin"], state["last_person"], state["since_seg"] = skin, seg.person, 0
                state["face_map"] = seg.face
            else:
                state["last_skin"] = flow.warp(state["last_skin"])
                if veto["map"] is not None:
                    veto["map"] = flow.warp(veto["map"])
                state["since_seg"] += 1
            skin_bin = fuser.update(state["last_skin"], flow, fresh)
            if k in nude:
                # ── stage 2: gender classification, only where it's still useful ──
                seen = [t for t in people.visible() if not t.misses]
                for t in seen:
                    need = t.gender.votes < 12 or idx - last_cls.get(t.tid, -10 ** 9) >= reclassify_every
                    if need:
                        res = gender_model.classify_person(frame, tuple(t.box), state["face_map"],
                                                           others=[tuple(o.box) for o in seen if o is not t])
                        last_cls[t.tid] = idx
                        if res is not None:
                            p_male, weight, age, _ = res
                            t.gender.add(p_male, weight)
                            t.gender.add_age(age)
            if state["pending_faces"] is not None:
                faces.update(state["pending_faces"] + own_faces(frame, state["pending_faces"]))
                state["pending_faces"] = None
            if not settings.include_face:
                # Faces and necks are never censored unless asked (the segmenter sometimes labels a whole face as
                # body skin); a low neckline is censored from just below the chin. See apply_neckline.
                for f in faces.tracks:
                    if apply_neckline(skin_bin, tuple(f.box), state["face_map"], f.cleavage_frames >= CLEAVAGE_STICKY_FRAMES):
                        f.cleavage_frames += 1
            remove_specks(skin_bin, [tuple(t.box) for t in people.visible()])
            people.mark_frame(frame, idx)

            # ── per-frame record (normalised coordinates) ──
            vis = people.visible()
            owner_map = _ownership(outline_state, frame, flow, cut, k in nude, vis, skin_bin, aw, ah)
            prec = (np.array([[t.tid, t.box[0] / aw, t.box[1] / ah, t.box[2] / aw, t.box[3] / ah] for t in vis],
                             np.float32) if vis else _EMPTY_P)
            rrows = []
            for r in regions.tracks:
                cx, cy = (r.box[0] + r.box[2]) / 2, (r.box[1] + r.box[3]) / 2
                owner, best_area = -1, float("inf")
                for t in vis:
                    if t.box[0] <= cx <= t.box[2] and t.box[1] <= cy <= t.box[3]:
                        area = (t.box[2] - t.box[0]) * (t.box[3] - t.box[1])
                        if area < best_area:
                            owner, best_area = t.tid, area
                strength = 1.0 if r.misses == 0 else max(0.6, 1.0 - 0.1 * r.misses)
                rrows.append([owner, r.box[0] / aw, r.box[1] / ah, r.box[2] / aw, r.box[3] / ah, strength])
            records.append(FrameRecord(prec, np.array(rrows, np.float32) if rrows else _EMPTY_R))
            store.append(cv2.resize(owner_map, (mw, mh), interpolation=cv2.INTER_NEAREST))

            b = min(TIMELINE_BUCKETS - 1, int(idx / max(info.frames, 1) * TIMELINE_BUCKETS))
            timeline_sum[b] += float(skin_bin.mean())
            timeline_cnt[b] += 1
            state["analyzed"] = idx + 1
            meter.update(idx + 1)
            preview(lambda: _analysis_preview(frame, skin_bin, vis, settings))
        progress.timeline = _timeline(timeline_sum, timeline_cnt)

    reader = media.FrameReader(info, aw, ah)
    try:
        start, chunk = 0, []
        for frame in reader:
            chunk.append(frame)
            if len(chunk) >= CHUNK:
                process_chunk(chunk, start)
                start += len(chunk)
                chunk = []
        if chunk:
            process_chunk(chunk, start)
    except BaseException:
        store.close()  # cancelled or failed: drop the temporary mask file
        raise
    finally:
        reader.close()
    if state["analyzed"] == 0:
        store.close()
        raise media.MediaError("Could not decode any frames from this video.")
    tracks = {tid: t for tid, t in people.all.items() if t.frames > 0}
    return Analysis(info=info, settings=settings, store=store, records=records, people=tracks,
                    total=state["analyzed"], elapsed=meter.elapsed, aliases=dict(people.aliases))


# ═══════════════════════════════════ rendering ═══════════════════════════════════

def decisions_for(analysis: Analysis, settings: CensorSettings, overrides: dict[int, str]) -> dict[int, bool]:
    th = settings.gender_threshold / 100.0
    out = {tid: censor_decision(t.gender.label(th), settings.target, settings.uncertain_policy,
                                overrides.get(tid, "auto"), t.gender.is_child)
           for tid, t in analysis.people.items()}
    # frames recorded before two duplicate tracks were merged still carry the merged-away id
    for old, new in analysis.aliases.items():
        if new in out:
            out[old] = out[new]
    return out


# temporal fusion: how much the motion-compensated past may lift the current skin score (see TemporalFuser)
FUSER_MEMORY = 0.4
FUSER_LIFT = 0.35
FUSER_LIFT_AGGRESSIVE = 0.5
PERSON_GATE = 0.3  # segmenter "person" probability a skin pixel must (nearly) touch to count
MIN_BODY_AREA = 0.02  # away from every person box, a smaller "person" blob is an object (a mug, a lamp)
REFINE_RADIUS = 0.004  # guided-filter window, fraction of (width + height)
REFINE_EPS = 0.004
FILL_GAP = 2  # censor drop-outs up to this many frames long are filled at render time
GATE_PAD = 0.3  # person_gate: skin counts within this much (box sizes) around a person's box
REFINE_COLOR_BAND = 0.008  # band around the skin boundary decided by colour, fraction of the frame diagonal
REFINE_COLOR_KEEP = 0.85  # …which never removes a pixel the model is at least this sure is skin
ROI_MIN_SIDE = 24  # shared/pipeline.json roi.min_side_px: smaller crops are skipped
# the clothes veto — mirror shared/pipeline.json "clothes_veto" (see detectors.veto_blobs); on video the clothes map is
# refreshed every VETO_VIDEO_EVERY freshly segmented frames and carried along with the motion in between. 0: photos
# only — on video it also took paint off motion-blurred arms — so the desktop (videos only) doesn't run it at all.
VETO_ENABLED = True
VETO_CLOTHES_MIN = 0.5
VETO_SKIN_MAX = 0.85
VETO_VIDEO_EVERY = 0
VETO_BLOB_SHARE = 0.2
VETO_BLOB_RIM = 0.01
# per-person outlines (MobileSAM): see outlines.PersonMasks and shared/pipeline.json "person_masks"
PM_VIDEO_SIZE = 512
PM_PHOTO_SIZE = 1024
PM_EVERY_SEC = 0.5
PM_SPEEDS = ("quality", "balanced")
PM_OWNER_MIN_LOGIT = 0.0
PM_CLIP_LOGIT = -1000.0


def guided_filter(guide: np.ndarray, p: np.ndarray, r: int, eps: float) -> np.ndarray:
    """Edge-aware refinement (He et al.): re-fit the coarse probability to the frame's luminance so mask edges
    follow real outlines. Same as the Android ``Guided.filter``."""
    k = (2 * r + 1, 2 * r + 1)
    box = lambda x: cv2.boxFilter(x, -1, k, borderType=cv2.BORDER_REPLICATE)  # noqa: E731
    mean_i, mean_p = box(guide), box(p)
    var_i = box(guide * guide) - mean_i * mean_i
    cov = box(guide * p) - mean_i * mean_p
    a = cov / (var_i + eps)
    b = mean_p - a * mean_i
    return np.clip(box(a) * guide + box(b), 0, 1)


def person_gate(person: np.ndarray, boxes: list, aw: int, ah: int) -> np.ndarray:
    """Where skin may count: on the segmenter's person map (slightly grown), and — away from every detected
    person box — only on a person-sized body. Same as the Android ``Analyzer.personGate``."""
    on = (person >= PERSON_GATE).astype(np.uint8)
    n, labels, stats, _ = cv2.connectedComponentsWithStats(on, connectivity=8)
    big = stats[:, cv2.CC_STAT_AREA] >= MIN_BODY_AREA * aw * ah
    big[0] = False
    in_box = np.zeros(on.shape, bool)
    for (x1, y1, x2, y2) in boxes:
        px, py = GATE_PAD * (x2 - x1), GATE_PAD * (y2 - y1)  # an arm or leg reaching out of a tight box
        in_box[max(0, int(y1 - py)):int(y2 + py) + 1, max(0, int(x1 - px)):int(x2 + px) + 1] = True
    keep = ((labels > 0) & (in_box | big[labels])).astype(np.uint8)
    r = max(2, int(round(0.004 * (aw + ah))))
    return cv2.dilate(keep, np.ones((2 * r + 1, 2 * r + 1), np.uint8)).astype(np.float32)
UNASSIGNED_MIN_AREA_WITH_PEOPLE = 0.004  # with people detected, a lone unattributed blob must be this big
OWNER_REACH = 0.12  # a skin blob whose nearest edge is this close (box sizes) to a person's box belongs to them
UNASSIGNED_MIN_AREA = 0.001  # fraction of the frame; smaller unattributed blobs are noise, not people


def _ownership(st: dict, frame: np.ndarray, flow: FlowEstimator, cut: bool, det_frame: bool, vis: list,
               skin_bin: np.ndarray, aw: int, ah: int) -> np.ndarray:
    """Skin labelled with its owner, as stored: 0 = no skin, k (1..254) = the k-th visible person (the
    FrameRecord order), 255 = skin whose owner is decided later from the boxes (compose_mask).

    Owners come from the people's outlines (MobileSAM), refreshed on detection frames at most every
    ``st["every"]`` frames and carried by the motion in between. Skin inside a person's box but clearly
    outside every outline (background around an arm) is dropped from ``skin_bin``."""
    out = np.where(skin_bin, 255, 0).astype(np.uint8)
    model = st["model"]
    if model is None:
        return out
    masks = st["masks"]
    if cut:
        masks.clear()
    for key in list(masks):
        masks[key] = flow.warp(masks[key] + 20.0) - 20.0  # outside the frame: clearly nobody
    ids = {t.tid for t in vis}
    for key in [k for k in masks if k not in ids]:
        del masks[key]
    st["since"] += 1
    missing = any(t.misses == 0 and t.tid not in masks for t in vis)
    if det_frame and vis and (st["since"] >= st["every"] or missing or cut):
        boxes = [(max(0.0, t.box[0]), max(0.0, t.box[1]), min(float(aw), t.box[2]), min(float(ah), t.box[3])) for t in vis]
        masks.clear()
        for t, m in zip(vis, model.masks(frame, boxes)):
            masks[t.tid] = m
        st["since"] = 0
    if not masks or len(vis) > 254:
        return out
    own = outlines.owners([masks.get(t.tid) for t in vis], PM_OWNER_MIN_LOGIT, PM_CLIP_LOGIT)
    near = np.zeros((ah, aw), bool)  # clipping only near people: skin far from every box is someone missed
    for t in vis:
        x1, y1, x2, y2 = t.box
        bw, bh = x2 - x1, y2 - y1
        near[max(0, int(y1 - 0.1 * bh)):min(ah, int(y2 + 0.1 * bh) + 1), max(0, int(x1 - 0.1 * bw)):min(aw, int(x2 + 0.1 * bw) + 1)] = True
    clipped = skin_bin & (own < 0) & near
    skin_bin[clipped] = False
    own = outlines.follow_arms(own, skin_bin, [masks.get(t.tid) for t in vis])
    out = np.where(skin_bin, np.where(own > 0, own, 255), 0).astype(np.uint8)
    return out


def compose_mask(skin: np.ndarray, rec: FrameRecord, decisions: dict[int, bool], unassigned_censor: bool,
                 box_pad: float = 0.06, unassigned_min_area: float = UNASSIGNED_MIN_AREA) -> np.ndarray:
    """Censor mask for one frame: only skin / sensitive regions owned by people who are censored.

    Ownership: a skin pixel stored with an owner label (1..n, from the person outlines) belongs to that
    person. Otherwise (label 255) it belongs to the person whose (slightly padded) box
    contains it. Where boxes overlap, or a pixel lies outside every box (e.g. an
    outstretched arm), the pixel adopts the owner of the majority of its connected
    skin component; failing that, the nearest box centre wins. Skin that can't be
    attributed to any detected person follows ``unassigned_censor`` — but only blobs of at
    least ``unassigned_min_area`` of the frame, so stray false-positive specks (lamps, wood
    grain…) never get censored on their own.
    """
    mh, mw = skin.shape
    persons = rec.persons
    out = np.zeros_like(skin)
    n = len(persons)
    if skin.any():
        min_area = unassigned_min_area * mh * mw
        if n == 0:
            if unassigned_censor:
                out = _drop_small_blobs(skin, min_area)
        else:
            flags = np.array([decisions.get(int(p[0]), unassigned_censor) for p in persons], bool)
            # with people in view, a lone unattributed blob must be bigger to count (objects near people)
            orphan_min = max(min_area, UNASSIGNED_MIN_AREA_WITH_PEOPLE * mh * mw)
            if flags.any() or unassigned_censor:
                out = _owned_skin(skin, persons, flags, unassigned_censor, box_pad, orphan_min)
    for r in rec.regions:
        owner = int(r[0])
        allowed = decisions.get(owner, unassigned_censor) if owner >= 0 else unassigned_censor
        if not allowed:
            continue
        x1, y1, x2, y2 = r[1] * mw, r[2] * mh, r[3] * mw, r[4] * mh
        ax, ay = (x2 - x1) * 0.65, (y2 - y1) * 0.65
        if ax >= 1 and ay >= 1:
            cv2.ellipse(out, (int((x1 + x2) / 2), int((y1 + y2) / 2)), (int(ax), int(ay)), 0, 0, 360,
                        int(255 * float(r[5])), -1, cv2.LINE_AA)
    return out


def _drop_small_blobs(skin: np.ndarray, min_area: float) -> np.ndarray:
    count, comp, stats, _ = cv2.connectedComponentsWithStats((skin > 0).astype(np.uint8), connectivity=8)
    keep = stats[:, cv2.CC_STAT_AREA] >= min_area
    keep[0] = False
    return np.where(keep[comp], 255, 0).astype(np.uint8)


def _owned_skin(skin: np.ndarray, persons: np.ndarray, flags: np.ndarray, unassigned_censor: bool,
                pad: float, min_area: float = 0.0) -> np.ndarray:
    mh, mw = skin.shape
    n = len(persons)
    best_d = np.full((mh, mw), np.inf, np.float32)
    nearest = np.zeros((mh, mw), np.int32)  # 1..n, 0 = outside all boxes
    cover = np.zeros((mh, mw), np.uint8)
    for i, (_, x1, y1, x2, y2) in enumerate(persons):
        bw, bh = (x2 - x1) * mw, (y2 - y1) * mh
        px1, py1 = int(max(0, x1 * mw - pad * bw)), int(max(0, y1 * mh - pad * bh))
        px2, py2 = int(min(mw, x2 * mw + pad * bw + 1)), int(min(mh, y2 * mh + pad * bh + 1))
        if px2 <= px1 or py2 <= py1:
            continue
        cx, cy = (x1 + x2) / 2 * mw, (y1 + y2) / 2 * mh
        ys = (np.arange(py1, py2, dtype=np.float32)[:, None] - cy) / max(bh / 2, 1)
        xs = (np.arange(px1, px2, dtype=np.float32)[None, :] - cx) / max(bw / 2, 1)
        d = ys * ys + xs * xs
        sub_d = best_d[py1:py2, px1:px2]
        closer = d < sub_d
        sub_d[closer] = d[closer]
        nearest[py1:py2, px1:px2][closer] = i + 1
        cover[py1:py2, px1:px2] += 1
    on = skin > 0
    # owner already known from the person outlines (stored label 1..n), else 0
    direct = np.where((skin >= 1) & (skin <= n), skin, 0).astype(np.int32)
    sure = on & (cover == 1) & (direct == 0)
    count, comp = cv2.connectedComponents(on.astype(np.uint8), connectivity=8)
    votes = np.bincount(np.concatenate([comp[sure] * (n + 1) + nearest[sure], comp[direct > 0] * (n + 1) + direct[direct > 0]]),
                        minlength=count * (n + 1)).reshape(count, n + 1)
    has = votes[:, 1:].sum(1) > 0
    comp_owner = np.where(has, votes[:, 1:].argmax(1) + 1, 0)
    # a blob entirely outside every box (a forearm stretched beyond the detector's box) belongs to the person
    # whose box its nearest edge almost touches — so it follows that person's decision. A blob separated by a
    # real gap (a mug on the desk) stays unattributed.
    orphans = np.where(~has)[0]
    orphans = orphans[orphans > 0]
    if len(orphans):
        _, _, stats, _ = cv2.connectedComponentsWithStats(on.astype(np.uint8), connectivity=8)
        for c in orphans:
            if c >= len(stats) or stats[c, cv2.CC_STAT_AREA] == 0:
                continue
            cx0, cy0 = stats[c, cv2.CC_STAT_LEFT], stats[c, cv2.CC_STAT_TOP]
            cx1, cy1 = cx0 + stats[c, cv2.CC_STAT_WIDTH] - 1, cy0 + stats[c, cv2.CC_STAT_HEIGHT] - 1
            best, best_r = 0, OWNER_REACH
            for i, (_, x1, y1, x2, y2) in enumerate(persons):
                X1, Y1, X2, Y2 = x1 * mw, y1 * mh, x2 * mw, y2 * mh
                bw, bh = max(X2 - X1, 1.0), max(Y2 - Y1, 1.0)
                r = max(max(0.0, X1 - cx1, cx0 - X2) / bw, max(0.0, Y1 - cy1, cy0 - Y2) / bh)
                if r <= best_r:
                    best, best_r = i + 1, r
            comp_owner[c] = best
    owner = np.where(direct > 0, direct, np.where(sure, nearest, comp_owner[comp]))
    # ambiguous pixels with no component majority fall back to the nearest box (if any)
    owner = np.where((owner == 0) & (cover > 0), nearest, owner)
    lut = np.concatenate([[unassigned_censor], flags]).astype(np.uint8) * 255
    out = np.where(on, lut[owner], 0).astype(np.uint8)
    if unassigned_censor and min_area > 0:
        # unattributed pixels only count if their whole blob is big enough to be part of a person
        areas = np.bincount(comp.ravel(), minlength=count)
        orphan_small = (owner == 0) & (areas[comp] < min_area)
        out[orphan_small] = 0
    return out


def render(analysis: Analysis, settings: CensorSettings, overrides: dict[int, str], out_path: Path,
           control: Control, progress: Progress, on_preview: Callable[[np.ndarray], None],
           span: tuple[float, float] = (0.0, 1.0)) -> dict:
    info = analysis.info
    total = analysis.total
    a_set = analysis.settings
    decisions = decisions_for(analysis, settings, overrides)
    unassigned_censor = settings.target == "everyone" or settings.uncertain_policy == "censor"
    preset = SPEED_PRESETS[a_set.speed]
    seg_stride = 1 if a_set.aggressive else preset["seg_stride"]
    lookahead = max(0, seg_stride - 1) + (1 if a_set.aggressive else 0)

    progress.stage = "applying"
    progress.message = "Applying censorship…"
    meter = _Meter(progress, control, total, span, 2)
    preview = _Previewer(on_preview)
    censor = BlueCensor(info.width, info.height, settings.color, settings.softness, a_set.aggressive,
                        settings.animated, info.fps)
    timeline_sum = np.zeros(TIMELINE_BUCKETS)
    timeline_cnt = np.zeros(TIMELINE_BUCKETS)

    window: deque = deque()  # composed masks for frames i .. i+max(FILL_GAP, lookahead)
    next_compose = 0
    prev: deque = deque(maxlen=FILL_GAP)  # composed masks of the previous FILL_GAP frames

    def compose(i: int) -> np.ndarray:
        return compose_mask(analysis.store.get(i), analysis.records[i], decisions, unassigned_censor)

    writer = None
    used_encoder = None
    for enc in media.available_encoders():
        try:
            writer = media.FrameWriter(info, out_path, encoder=enc, quality=settings.quality,
                                       keep_audio=settings.keep_audio)
            used_encoder = enc
            break
        except Exception:
            continue
    assert writer is not None
    reader = media.FrameReader(info, info.width, info.height)
    censored_frames = 0
    try:
        i = 0
        for frame in reader:
            control.checkpoint()
            if i >= total:
                break
            while next_compose < min(total, i + max(FILL_GAP, lookahead) + 1):
                window.append(compose(next_compose))
                next_compose += 1
            cur = window[0]
            m = cur
            # momentary drop-outs: censored in one of the previous FILL_GAP frames and one of the next ones ->
            # censored here too (only ever adds; same as Analysis.maskFor on Android)
            before = [x for x in prev if x.any()]
            after = [x for x in list(window)[1:FILL_GAP + 1] if x.any()]
            # the first / last frames have only one side: there the cover just holds
            if i + FILL_GAP >= total:
                after = after + before
            if i < FILL_GAP:
                before = before + after
            if before and after:
                m = np.maximum(m, np.minimum(np.maximum.reduce(before), np.maximum.reduce(after)))
            for extra in list(window)[1:lookahead + 1]:
                if extra.any():
                    m = np.maximum(m, extra)
            prev.append(cur)
            window.popleft()
            out, cov = censor.apply(frame, m, i)
            if cov > 0:
                censored_frames += 1
            b = min(TIMELINE_BUCKETS - 1, int(i / max(total, 1) * TIMELINE_BUCKETS))
            timeline_sum[b] += cov
            timeline_cnt[b] += 1
            writer.write(out)
            i += 1
            meter.update(i)
            preview(lambda: out)
        progress.stage = "encoding"
        progress.message = "Encoding final video…"
        writer.finish()
    except BaseException:
        writer.abort()
        raise
    finally:
        reader.close()
    progress.timeline = _timeline(timeline_sum, timeline_cnt)
    return {
        "frames": total,
        "censored_frames": censored_frames,
        "encoder": used_encoder,
        "elapsed": round(meter.elapsed, 2),
        "timeline": progress.timeline,
    }


def run_pipeline(engine: Engine, info: media.VideoInfo, settings: CensorSettings, out_path: Path,
                 control: Control, progress: Progress, on_preview: Callable[[np.ndarray], None],
                 job_id: str, keep_analysis: bool = False):
    """Analyse + render in one go. Returns the result dict (and the Analysis if ``keep_analysis``)."""
    t0 = time.monotonic()
    analysis = analyze(engine, info, settings, control, progress, on_preview, job_id, (0.0, ANALYSIS_WEIGHT))
    try:
        result = render(analysis, settings, {}, out_path, control, progress, on_preview, (ANALYSIS_WEIGHT, 1.0))
    except BaseException:
        analysis.close()
        raise
    result["elapsed"] = round(time.monotonic() - t0 - control.paused_time, 2)
    result["people"] = [person_summary(t, settings, "auto", info.fps) for t in analysis.people.values()]
    progress.percent = 100.0
    progress.eta_seconds = 0.0
    if keep_analysis:
        return result, analysis
    analysis.close()
    return result


OWN_FACE_SKIN = 0.3  # mean facial-skin probability the segmenter needs inside a face box found only by the face detector


def mean_in(m: np.ndarray, b: tuple[float, float, float, float]) -> float:
    """Mean of ``m`` over the inner half of box ``b`` (frame pixels). Same as the Android ``Analyzer.meanIn``."""
    cx, cy, bw, bh = (b[0] + b[2]) / 2, (b[1] + b[3]) / 2, b[2] - b[0], b[3] - b[1]
    x0, x1 = max(0, int(cx - bw / 4)), min(m.shape[1], int(cx + bw / 4) + 1)
    y0, y1 = max(0, int(cy - bh / 4)), min(m.shape[0], int(cy + bh / 4) + 1)
    return float(m[y0:y1, x0:x1].mean()) if x1 > x0 and y1 > y0 else 0.0


def _roi_wanted(t, settings: CensorSettings, th: float) -> bool:
    """Close-up skin pass for everyone to be censored — and, when unsure people are kept, also for everyone not
    decided yet (too few face votes), who may still turn out to be a woman. Same as the Android ``roiWanted``."""
    g = t.gender
    if censor_decision(g.label(th), settings.target, settings.uncertain_policy, "auto", g.is_child):
        return True
    undecided = g.votes < gender_mod.MIN_VOTES or g.weight < gender_mod.MIN_WEIGHT
    return undecided and censor_decision(g.label(th), settings.target, "censor", "auto", g.is_child)


# face / neck / neckline rules (sizes relative to the face box) — mirror shared/pipeline.json "neckline"
NECK_BAND_HALF_WIDTH = 0.6
NECK_BAND_HEIGHT = 0.55  # = CLEAVAGE_START: the throat ends at the same depth whether or not a neckline is seen
NECK_PROBE_HALF_WIDTH = 0.35
NECK_PROBE_HEIGHT = 0.6
CLEAVAGE_MIN_FILL = 0.12
CLEAVAGE_START = 0.55  # the end of the throat (a little over half a face below the chin): the throat stays visible
# (a third of the earlier 0.006 / 0.0006: those removed a small hand far from the camera, measured)
SPECK_PERSON_FRAC = 0.002
SPECK_FRAME_FRAC = 0.0002
CLEAVAGE_STICKY_FRAMES = 3  # a low neckline seen in this many frames counts for the rest of the shot
_FACE_INNER = 0.75
_FACE_EDGE_PROB = 0.2
_JAW = 0.25
_CHIN_UP, _CHIN_DOWN = 0.35, 0.1


def face_chin(face_prob: np.ndarray, face: tuple[float, float, float, float]) -> float:
    """The chin: the lowest row between the face centre and a little under the box that is mostly facial skin
    (central 40 % of the face width), kept within _CHIN_UP face heights above and _CHIN_DOWN below the box's
    bottom edge; the box's edge where no row is. Same as the Android ``Neckline.faceChin``."""
    h, w = face_prob.shape
    x1, y1, x2, y2 = face
    fh, cx = y2 - y1, (x1 + x2) / 2
    x0, xe = max(0, int(cx - 0.2 * (x2 - x1))), min(w, int(cx + 0.2 * (x2 - x1)) + 1)
    if xe <= x0:
        return y2
    lo, hi = y2 - _CHIN_UP * fh, y2 + _CHIN_DOWN * fh
    ys = np.arange(max(0, int((y1 + y2) / 2)), min(h - 1, int(hi)) + 1)
    if ys.size == 0:
        return y2
    rows = (face_prob[ys, x0:xe] >= 0.5).sum(axis=1) >= 0.5 * (xe - x0)
    if not rows.any():
        return y2
    return float(min(max(ys[rows][-1] + 1.0, lo), hi))


def apply_neckline(skin: np.ndarray, face: tuple[float, float, float, float],
                   face_prob: Optional[np.ndarray] = None, known_cleavage: bool = False) -> bool:
    """Face and throat stay uncensored; bare skin is censored from the end of the throat (a little over half a
    face below the chin, which is where the facial skin ends) downwards. Same algorithm as the Android
    ``Neckline.apply``.

    Returns whether a low neckline was seen in this frame; ``known_cleavage`` (seen on this face before) makes
    the decision stick, so the censoring doesn't flicker when the head turns or tilts."""
    h, w = skin.shape
    x1, y1, x2, y2 = face
    fw, fh = x2 - x1, y2 - y1
    if fw < 2 or fh < 2:
        return False
    cx, cy = (x1 + x2) / 2, (y1 + y2) / 2 - 0.12 * fh
    ax, ay = 0.65 * fw, 0.72 * fh
    # face ellipse: the core always, the rim only where the segmenter sees face skin (a hand at the cheek stays)
    ex0, ex1 = max(0, int(cx - ax)), min(w, int(cx + ax) + 1)
    ey0, ey1 = max(0, int(cy - ay)), min(h, int(cy + ay) + 1)
    if ex1 > ex0 and ey1 > ey0:
        yy, xx = np.mgrid[ey0:ey1, ex0:ex1]
        r2 = ((xx + 0.5 - cx) / ax) ** 2 + ((yy + 0.5 - cy) / ay) ** 2
        clear = r2 <= _FACE_INNER ** 2
        if face_prob is not None:
            clear |= (r2 <= 1) & (face_prob[ey0:ey1, ex0:ex1] >= _FACE_EDGE_PROB)
        else:
            clear |= r2 <= 1
        skin[ey0:ey1, ex0:ex1][clear] = False
    chin = face_chin(face_prob, face) if face_prob is not None else y2
    band_bottom = chin + NECK_BAND_HEIGHT * fh
    px0, px1 = max(0, int(cx - NECK_PROBE_HALF_WIDTH * fw)), min(w, int(cx + NECK_PROBE_HALF_WIDTH * fw) + 1)
    py0, py1 = max(0, int(band_bottom)), min(h, int(band_bottom + NECK_PROBE_HEIGHT * fh) + 1)
    probe = skin[py0:py1, px0:px1]
    seen = bool(probe.size > 0 and probe.sum() >= CLEAVAGE_MIN_FILL * probe.size)
    clear_to = chin + CLEAVAGE_START * fh if (seen or known_cleavage) else band_bottom
    bx0, bx1 = max(0, int(cx - NECK_BAND_HALF_WIDTH * fw)), min(w, int(cx + NECK_BAND_HALF_WIDTH * fw) + 1)
    by0, by1 = max(0, int(chin - _JAW * fh)), min(h, int(clear_to) + 1)
    if bx1 > bx0 and by1 > by0:
        skin[by0:by1, bx0:bx1] = False
    return seen


def remove_specks(skin: np.ndarray, boxes: list[tuple[float, float, float, float]]) -> None:
    """Drop tiny isolated skin specks (patterned or pink clothes): smaller than SPECK_PERSON_FRAC of the person
    box they sit in, or SPECK_FRAME_FRAC of the frame outside every box."""
    h, w = skin.shape
    n, labels, stats, cents = cv2.connectedComponentsWithStats(skin.astype(np.uint8), connectivity=8)
    if n <= 1:
        return
    drop = np.zeros(n, bool)
    for l in range(1, n):
        mx, my = cents[l]
        inside = [b for b in boxes if b[0] <= mx <= b[2] and b[1] <= my <= b[3]]
        if inside:
            b = min(inside, key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))
            min_area = min(SPECK_PERSON_FRAC * (b[2] - b[0]) * (b[3] - b[1]), SPECK_FRAME_FRAC * w * h * 4)
        else:
            min_area = SPECK_FRAME_FRAC * w * h
        drop[l] = stats[l, cv2.CC_STAT_AREA] < min_area
    skin[drop[labels]] = False


def _timeline(s: np.ndarray, c: np.ndarray) -> list[float]:
    vals = np.where(c > 0, s / np.maximum(c, 1), 0.0)
    return [round(float(v), 4) for v in vals]


def _analysis_preview(frame: np.ndarray, skin: np.ndarray, vis: list[PersonTrack],
                      settings: CensorSettings) -> np.ndarray:
    """Live preview: tint only what the *current* decisions would censor, and label each person."""
    out = frame.copy()
    h, w = frame.shape[:2]
    th = settings.gender_threshold / 100.0
    decisions = {t.tid: censor_decision(t.gender.label(th), settings.target, settings.uncertain_policy,
                                        child=t.gender.is_child) for t in vis}
    persons = (np.array([[t.tid, t.box[0] / w, t.box[1] / h, t.box[2] / w, t.box[3] / h] for t in vis], np.float32)
               if vis else _EMPTY_P)
    unassigned = settings.target == "everyone" or settings.uncertain_policy == "censor"
    mask = compose_mask(skin.astype(np.uint8) * 255, FrameRecord(persons, _EMPTY_R), decisions, unassigned) > 0
    tint = np.array(hex_to_bgr(settings.color), np.uint8)
    out[mask] = (out[mask] * 0.3 + tint * 0.7).astype(np.uint8)
    for t in vis:
        label = t.gender.label(th)
        color = (255, 120, 40) if decisions[t.tid] else (140, 140, 140)
        x1, y1, x2, y2 = (int(v) for v in t.box)
        cv2.rectangle(out, (x1, y1), (x2, y2), color, 2, cv2.LINE_AA)
        text = {"female": "Female", "male": "Male"}.get(label, "Unsure")
        if label != "uncertain":
            text += f" {int(round(t.gender.confidence() * 100))}%"
        text = f"#{t.tid} {text}" + ("" if decisions[t.tid] else " - not censored")
        (tw, th_), _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, 0.45, 1)
        ty = y1 - 6 if y1 >= th_ + 10 else y1 + th_ + 6  # keep the label on-screen
        cv2.rectangle(out, (x1, ty - th_ - 4), (x1 + tw + 8, ty + 4), color, -1)
        cv2.putText(out, text, (x1 + 4, ty), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1, cv2.LINE_AA)
    return out
