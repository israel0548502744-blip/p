"""The censorship pipeline — analysis and rendering are separate stages.

Analysis (``analyze``) — analysis resolution, streamed:
    decode → scene cuts → optical flow
      ├─ person detection (EfficientDet)  ─► person tracking (IoU + appearance, re-id)
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

from . import media
from .censor import BlueCensor, hex_to_bgr
from .config import ANALYSIS_MAX_SIDE, MASK_MAX_SIDE, WORK_DIR
from .detectors import (SegResult, AGGRESSIVE_LABELS, FACE_LABELS, SENSITIVE_LABELS, SensitiveRegionDetector, SkinSegmenter,
                        color_skin_probability, skin_color_plausible)
from .gender import GenderClassifier, censor_decision
from .maskstore import MaskStore
from .people import PersonTrack, PersonTracker
from .persons import PersonDetector
from .tracking import BoxTracker, FlowEstimator, TemporalFuser, scene_cut

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
    color: str = "#1E4DFF"
    sensitivity: int = 60  # 0..100
    softness: int = 35  # 0..100
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
        "censored": censor_decision(label, settings.target, settings.uncertain_policy, override),
        "override": override,
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
    person_min_score = 0.45 - 0.20 * sens  # EfficientDet: 0.45 .. 0.25
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
    regions = BoxTracker(max_misses=3 if not settings.aggressive else 5)
    faces = BoxTracker(max_misses=2, smooth=0.6)  # face boxes, only used to keep faces uncensored
    people = PersonTracker(max_misses=max(3, int(round(1.5 * info.fps / det_stride))),
                           gallery_frames=int(round(12 * info.fps)))
    fuser = TemporalFuser(release=0.35 if not settings.aggressive else 0.2, on_threshold=skin_threshold)
    last_cls: dict[int, int] = {}
    state = {"prev_small": None, "last_skin": None, "since_seg": 10 ** 9, "analyzed": 0, "face_map": None,
             "last_person": None}
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
                people.update(frame, person_model.detect(frame, person_min_score), idx)
                regions.update([d for d in nude[k] if d.label in labels])
                faces.update([d for d in nude[k] if d.label in FACE_LABELS])

            # ── skin segmentation: whole frame on detection keyframes, per-person crops on every analysed frame ──
            boxes = [tuple(t.box) for t in people.visible()]
            fast_motion = False
            if flow.flow_full is not None and state["since_seg"] >= 1:
                fast_motion = float(np.abs(flow.flow_full[::8, ::8]).mean()) > 0.012 * max(aw, ah)
            fresh = state["last_skin"] is None or cut or state["since_seg"] + 1 >= seg_stride or fast_motion
            if fresh:
                full_due = state["last_skin"] is None or cut or not boxes or k in nude
                if full_due:
                    seg = seg_model.segment(frame, include_face=settings.include_face, tiled=settings.aggressive)
                else:  # between whole-frame passes: carry the last result along with the motion
                    seg = SegResult(flow.warp(state["last_skin"]), flow.warp(state["last_person"]),
                                    flow.warp(state["face_map"]))
                if boxes:
                    seg = seg_model.segment_rois(frame, boxes, seg, include_face=settings.include_face)
                skin = seg.skin
                if settings.aggressive:
                    person_px = cv2.dilate((seg.person > 0.4).astype(np.uint8), np.ones((9, 9), np.uint8))
                    backup = color_skin_probability(frame) * person_px * 0.9
                    if not settings.include_face:
                        face = cv2.dilate((seg.face > 0.3).astype(np.uint8), np.ones((15, 15), np.uint8))
                        backup *= 1 - face
                    skin = np.maximum(skin, backup)
                skin = skin * skin_color_plausible(frame)
                state["last_skin"], state["last_person"], state["since_seg"] = skin, seg.person, 0
                state["face_map"] = seg.face
            else:
                state["last_skin"] = flow.warp(state["last_skin"])
                state["since_seg"] += 1
            skin_bin = fuser.update(state["last_skin"], flow, fresh)
            if k in nude:
                # ── stage 2: gender classification, only where it's still useful ──
                for t in people.visible():
                    if t.misses:
                        continue
                    need = t.gender.votes < 12 or idx - last_cls.get(t.tid, -10 ** 9) >= reclassify_every
                    if need:
                        res = gender_model.classify_person(frame, tuple(t.box), state["face_map"])
                        last_cls[t.tid] = idx
                        if res is not None:
                            t.gender.add(*res)
            if not settings.include_face:
                # Faces are never censored unless asked. The segmenter sometimes labels a whole face as
                # body skin, so clear every tracked face box (NudeNet face detections, flow-tracked).
                for f in faces.tracks:
                    _clear_face(skin_bin, tuple(f.box))
            people.mark_frame(frame, idx)

            # ── per-frame record (normalised coordinates) ──
            vis = people.visible()
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
            store.append(cv2.resize(skin_bin.astype(np.uint8) * 255, (mw, mh), interpolation=cv2.INTER_AREA))

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
                    total=state["analyzed"], elapsed=meter.elapsed)


# ═══════════════════════════════════ rendering ═══════════════════════════════════

def decisions_for(analysis: Analysis, settings: CensorSettings, overrides: dict[int, str]) -> dict[int, bool]:
    th = settings.gender_threshold / 100.0
    return {tid: censor_decision(t.gender.label(th), settings.target, settings.uncertain_policy,
                                 overrides.get(tid, "auto"))
            for tid, t in analysis.people.items()}


UNASSIGNED_MIN_AREA = 0.001  # fraction of the frame; smaller unattributed blobs are noise, not people


def compose_mask(skin: np.ndarray, rec: FrameRecord, decisions: dict[int, bool], unassigned_censor: bool,
                 box_pad: float = 0.06, unassigned_min_area: float = UNASSIGNED_MIN_AREA) -> np.ndarray:
    """Censor mask for one frame: only skin / sensitive regions owned by people who are censored.

    Ownership: each skin pixel belongs to the person whose (slightly padded) box
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
            if flags.any() or unassigned_censor:
                out = _owned_skin(skin, persons, flags, unassigned_censor, box_pad, min_area)
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
    sure = on & (cover == 1)
    count, comp = cv2.connectedComponents(on.astype(np.uint8), connectivity=8)
    votes = np.bincount(comp[sure] * (n + 1) + nearest[sure], minlength=count * (n + 1)).reshape(count, n + 1)
    has = votes[:, 1:].sum(1) > 0
    comp_owner = np.where(has, votes[:, 1:].argmax(1) + 1, 0)
    owner = np.where(sure, nearest, comp_owner[comp])
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

    window: deque = deque()  # composed masks for frames i .. i+lookahead
    next_compose = 0

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
            while next_compose < min(total, i + lookahead + 1):
                window.append(compose(next_compose))
                next_compose += 1
            m = window[0]
            for extra in list(window)[1:]:
                if extra.any():
                    m = np.maximum(m, extra)
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


def _clear_face(skin: np.ndarray, face: tuple[float, float, float, float]) -> None:
    """Remove skin inside an ellipse around a face box — forehead to chin, not the neck."""
    x1, y1, x2, y2 = face
    cx, cy = (x1 + x2) / 2, (y1 + y2) / 2 - 0.12 * (y2 - y1)
    ax, ay = 0.65 * (x2 - x1), 0.78 * (y2 - y1)
    if ax < 1 or ay < 1:
        return
    hole = np.zeros(skin.shape, np.uint8)
    cv2.ellipse(hole, (int(cx), int(cy)), (int(ax), int(ay)), 0, 0, 360, 1, -1)
    skin[hole.astype(bool)] = False


def _timeline(s: np.ndarray, c: np.ndarray) -> list[float]:
    vals = np.where(c > 0, s / np.maximum(c, 1), 0.0)
    return [round(float(v), 4) for v in vals]


def _analysis_preview(frame: np.ndarray, skin: np.ndarray, vis: list[PersonTrack],
                      settings: CensorSettings) -> np.ndarray:
    """Live preview: tint only what the *current* decisions would censor, and label each person."""
    out = frame.copy()
    h, w = frame.shape[:2]
    th = settings.gender_threshold / 100.0
    decisions = {t.tid: censor_decision(t.gender.label(th), settings.target, settings.uncertain_policy) for t in vis}
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
