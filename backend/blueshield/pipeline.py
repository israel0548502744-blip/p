"""The two-pass censorship pipeline.

Pass 1 — *Analyze & detect* (analysis resolution, ≤640px):
    decode → scene-cut detection → optical flow → keyframe segmentation
    (+ batched NudeNet detection) → flow propagation between keyframes →
    box tracking → anti-flicker temporal fusion → compressed mask store.

Pass 2 — *Apply & encode* (original resolution):
    decode → read mask (+ short look-ahead) → feather → blue composite →
    pipe into FFmpeg (H.264, original FPS, original audio).

Frames are always streamed through FFmpeg pipes; nothing holds the whole
video in memory.
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
from .detectors import (AGGRESSIVE_LABELS, SENSITIVE_LABELS, SensitiveRegionDetector, SkinSegmenter,
                        color_skin_probability)
from .maskstore import MaskStore
from .tracking import BoxTracker, FlowEstimator, TemporalFuser, scene_cut

log = logging.getLogger("blueshield.pipeline")

SPEED_PRESETS = {
    # seg_stride: run skin segmentation every N frames (flow-propagated between)
    # det_stride: run sensitive-region detector every N frames (tracked between)
    "quality": {"seg_stride": 1, "det_stride": 2},
    "balanced": {"seg_stride": 2, "det_stride": 3},
    "fast": {"seg_stride": 4, "det_stride": 6},
}


@dataclass
class CensorSettings:
    color: str = "#1E4DFF"
    sensitivity: int = 60  # 0..100
    softness: int = 35  # 0..100
    aggressive: bool = False
    animated: bool = False
    include_face: bool = False
    speed: str = "balanced"
    quality: str = "balanced"
    keep_audio: bool = True

    def validate(self) -> "CensorSettings":
        hex_to_bgr(self.color)
        self.sensitivity = int(min(100, max(0, self.sensitivity)))
        self.softness = int(min(100, max(0, self.softness)))
        if self.speed not in SPEED_PRESETS:
            self.speed = "balanced"
        if self.quality not in media.QUALITY_CRF:
            self.quality = "balanced"
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
    pass_index: int = 0  # 1 or 2
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

    def checkpoint(self) -> None:
        if not self._resume.is_set():
            t0 = time.monotonic()
            while not self._resume.wait(0.25):
                pass
            self.paused_time += time.monotonic() - t0
        if self.cancelled:
            raise Cancelled()


# Relative cost of the two passes, used to blend progress into one percentage.
PASS1_WEIGHT = 0.62
TIMELINE_BUCKETS = 240


class Engine:
    """Holds loaded models so successive jobs don't pay the start-up cost again."""

    def __init__(self) -> None:
        self._seg: SkinSegmenter | None = None
        self._det: SensitiveRegionDetector | None = None
        self._lock = threading.Lock()

    def models(self) -> tuple[SkinSegmenter, SensitiveRegionDetector]:
        with self._lock:
            if self._seg is None:
                self._seg = SkinSegmenter()
            if self._det is None:
                self._det = SensitiveRegionDetector()
            return self._seg, self._det


def run_pipeline(
    engine: Engine,
    info: media.VideoInfo,
    settings: CensorSettings,
    out_path: Path,
    control: Control,
    progress: Progress,
    on_preview: Callable[[np.ndarray], None],
    job_id: str,
) -> dict:
    t_start = time.monotonic()
    total = info.frames
    progress.total_frames = total
    progress.stage = "analyzing"
    progress.message = "Loading models…"
    seg_model, det_model = engine.models()
    control.checkpoint()

    preset = SPEED_PRESETS[settings.speed]
    seg_stride = 1 if settings.aggressive else preset["seg_stride"]
    det_stride = max(1, preset["det_stride"] - (1 if settings.aggressive else 0))
    sens = settings.sensitivity / 100.0
    skin_threshold = 0.72 - 0.42 * sens  # 0.72 (strict) .. 0.30 (sensitive)
    if settings.aggressive:
        skin_threshold -= 0.08
    det_min_score = 0.50 - 0.30 * sens  # 0.50 .. 0.20
    labels = AGGRESSIVE_LABELS if settings.aggressive else SENSITIVE_LABELS

    aw, ah = media.scaled_size(info.width, info.height, ANALYSIS_MAX_SIDE)
    mw, mh = media.scaled_size(info.width, info.height, MASK_MAX_SIDE)
    store = MaskStore(WORK_DIR / f"{job_id}.masks", mw, mh)
    timeline_sum = np.zeros(TIMELINE_BUCKETS, np.float64)
    timeline_cnt = np.zeros(TIMELINE_BUCKETS, np.float64)

    last_preview = [0.0]

    def maybe_preview(img_fn: Callable[[], np.ndarray]) -> None:
        now = time.monotonic()
        if now - last_preview[0] > 0.6:
            last_preview[0] = now
            try:
                on_preview(img_fn())
            except Exception:  # preview must never break processing
                log.exception("preview failed")

    def update_progress(done_frames: int, pass_index: int) -> None:
        frac = min(1.0, done_frames / max(total, 1))
        overall = frac * PASS1_WEIGHT if pass_index == 1 else PASS1_WEIGHT + frac * (1 - PASS1_WEIGHT)
        elapsed = time.monotonic() - t_start - control.paused_time
        progress.frame = done_frames
        progress.pass_index = pass_index
        progress.percent = round(overall * 100, 2)
        progress.elapsed = elapsed
        recent.append((elapsed, overall))
        if len(recent) >= 2 and recent[-1][1] > recent[0][1]:
            rate = (recent[-1][1] - recent[0][1]) / max(1e-6, recent[-1][0] - recent[0][0])
            progress.eta_seconds = max(0.0, (1 - overall) / rate)
        fps_hist.append((elapsed, done_frames))
        if len(fps_hist) >= 2 and fps_hist[-1][0] > fps_hist[0][0]:
            progress.fps = round((fps_hist[-1][1] - fps_hist[0][1]) / (fps_hist[-1][0] - fps_hist[0][0]), 1)

    recent: deque = deque(maxlen=60)
    fps_hist: deque = deque(maxlen=30)

    # ───────────────────────── Pass 1: analyze & detect ─────────────────────────
    progress.stage = "detecting"
    progress.message = "Detecting sensitive regions…"
    flow = FlowEstimator(aw, ah)
    tracker = BoxTracker(max_misses=3 if not settings.aggressive else 5)
    fuser = TemporalFuser(release=0.35 if not settings.aggressive else 0.2, on_threshold=skin_threshold)
    reader = media.FrameReader(info, aw, ah)
    prev_small = None
    last_skin: np.ndarray | None = None
    since_seg = 10**9
    analyzed = 0
    chunk: list[np.ndarray] = []
    CHUNK = 8 * det_stride  # frames buffered so detector keyframes can be batched

    def process_chunk(frames: list[np.ndarray], start_index: int) -> None:
        nonlocal prev_small, last_skin, since_seg, analyzed
        det_idx = [k for k in range(len(frames)) if (start_index + k) % det_stride == 0]
        det_results = dict(zip(det_idx, det_model.detect_batch([frames[k] for k in det_idx], det_min_score)))
        for k, frame in enumerate(frames):
            control.checkpoint()
            idx = start_index + k
            small = cv2.resize(frame, (64, 36), interpolation=cv2.INTER_AREA)
            cut = scene_cut(prev_small, small)
            prev_small = small
            if cut:
                flow.reset()
                fuser.reset()
                tracker.reset()
            flow.update(frame)
            # adaptive keyframes: fast motion forces a fresh segmentation
            fast_motion = False
            if flow.flow_full is not None and since_seg >= 1:
                fast_motion = float(np.abs(flow.flow_full[::8, ::8]).mean()) > 0.012 * max(aw, ah)
            fresh = last_skin is None or cut or since_seg + 1 >= seg_stride or fast_motion
            if fresh:
                seg = seg_model.segment(frame, include_face=settings.include_face, tiled=settings.aggressive)
                skin = seg.skin
                if settings.aggressive:
                    person = cv2.dilate((seg.person > 0.4).astype(np.uint8), np.ones((9, 9), np.uint8))
                    backup = color_skin_probability(frame) * person * 0.9
                    if not settings.include_face:
                        # hair and facial skin are never part of the colour-based backup
                        face = cv2.dilate((seg.face > 0.3).astype(np.uint8), np.ones((15, 15), np.uint8))
                        backup *= 1 - face
                    skin = np.maximum(skin, backup)
                last_skin = skin
                since_seg = 0
            else:
                last_skin = flow.warp(last_skin)
                since_seg += 1
            tracker.predict(flow)
            if k in det_results:
                tracker.update([d for d in det_results[k] if d.label in labels])
            combined = np.maximum(last_skin, tracker.render((ah, aw)))
            binary = fuser.update(combined, flow, fresh)
            mask = cv2.resize(binary.astype(np.uint8) * 255, (mw, mh), interpolation=cv2.INTER_AREA)
            store.append(mask)
            b = min(TIMELINE_BUCKETS - 1, int(idx / max(total, 1) * TIMELINE_BUCKETS))
            timeline_sum[b] += float(binary.mean())
            timeline_cnt[b] += 1
            analyzed = idx + 1
            update_progress(analyzed, 1)
            maybe_preview(lambda: _overlay_preview(frame, binary, settings.color))
        progress.timeline = _timeline(timeline_sum, timeline_cnt)

    try:
        start = 0
        for frame in reader:
            chunk.append(frame)
            if len(chunk) >= CHUNK:
                process_chunk(chunk, start)
                start += len(chunk)
                chunk = []
        if chunk:
            process_chunk(chunk, start)
            start += len(chunk)
    except BaseException:
        store.close()  # cancelled or failed: drop the temporary mask file
        raise
    finally:
        reader.close()
    if analyzed == 0:
        store.close()
        raise media.MediaError("Could not decode any frames from this video.")
    # The real decoded count is the source of truth (probe estimates can be off by a frame or two).
    total = analyzed
    progress.total_frames = total

    # ───────────────────────── Pass 2: apply & encode ─────────────────────────
    progress.stage = "applying"
    progress.message = "Applying censorship…"
    recent.clear()
    fps_hist.clear()
    encoders = media.available_encoders()
    lookahead = max(0, seg_stride - 1) + (1 if settings.aggressive else 0)
    censor = BlueCensor(info.width, info.height, settings.color, settings.softness, settings.aggressive,
                        settings.animated, info.fps)
    writer = None
    used_encoder = None
    for enc in encoders:
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
            m = store.get(i)
            for j in range(1, lookahead + 1):
                nxt = store.get(i + j)
                if nxt is not m and nxt.any():
                    m = np.maximum(m, nxt)
            out, cov = censor.apply(frame, m, i)
            if cov > 0:
                censored_frames += 1
            writer.write(out)
            i += 1
            update_progress(i, 2)
            maybe_preview(lambda: out)
        # pad if the second decode produced fewer frames (keeps audio sync)
        progress.stage = "encoding"
        progress.message = "Encoding final video…"
        writer.finish()
    except BaseException:
        writer.abort()
        raise
    finally:
        reader.close()
        store.close()

    progress.percent = 100.0
    progress.eta_seconds = 0.0
    return {
        "frames": total,
        "censored_frames": censored_frames,
        "encoder": used_encoder,
        "elapsed": round(time.monotonic() - t_start - control.paused_time, 2),
        "timeline": progress.timeline,
    }


def _timeline(s: np.ndarray, c: np.ndarray) -> list[float]:
    vals = np.where(c > 0, s / np.maximum(c, 1), 0.0)
    return [round(float(v), 4) for v in vals]


def _overlay_preview(frame: np.ndarray, binary: np.ndarray, color: str) -> np.ndarray:
    out = frame.copy()
    b, g, r = hex_to_bgr(color)
    tint = np.array([b, g, r], np.uint8)
    out[binary] = (out[binary] * 0.25 + tint * 0.75).astype(np.uint8)
    contours, _ = cv2.findContours(binary.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    cv2.drawContours(out, contours, -1, (255, 255, 255), 1, cv2.LINE_AA)
    return out
