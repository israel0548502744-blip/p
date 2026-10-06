"""Temporal tracking: optical-flow mask propagation, box tracking and anti-flicker fusion."""

from __future__ import annotations

from dataclasses import dataclass, field

import cv2
import numpy as np

from .detectors import Detection


class FlowEstimator:
    """Dense optical flow (Farneback) on a small grayscale copy of each frame.

    The flow maps pixel positions in the *current* frame back to the previous
    frame, which lets us warp masks forward so they stick to moving people.
    """

    def __init__(self, width: int, height: int, flow_side: int = 256) -> None:
        s = min(1.0, flow_side / max(width, height))
        self.fw, self.fh = max(16, int(width * s)), max(16, int(height * s))
        self.w, self.h = width, height
        self.prev: np.ndarray | None = None
        gx, gy = np.meshgrid(np.arange(width, dtype=np.float32), np.arange(height, dtype=np.float32))
        self.grid_x, self.grid_y = gx, gy
        self.flow_full: np.ndarray | None = None  # (H,W,2) backward flow at analysis res

    def reset(self) -> None:
        self.prev = None
        self.flow_full = None

    def update(self, bgr: np.ndarray) -> np.ndarray:
        gray = cv2.cvtColor(cv2.resize(bgr, (self.fw, self.fh), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY)
        if self.prev is None:
            self.prev = gray
            self.flow_full = None
            return gray
        # Backward flow: for each pixel in current frame, where was it in the previous one.
        flow = cv2.calcOpticalFlowFarneback(gray, self.prev, None, 0.5, 3, 15, 3, 5, 1.2, 0)
        self.prev = gray
        sx, sy = self.w / self.fw, self.h / self.fh
        flow_up = cv2.resize(flow, (self.w, self.h), interpolation=cv2.INTER_LINEAR)
        flow_up[..., 0] *= sx
        flow_up[..., 1] *= sy
        self.flow_full = flow_up
        return gray

    def warp(self, mask: np.ndarray) -> np.ndarray:
        """Warp a mask from the previous frame into the current frame."""
        if self.flow_full is None:
            return mask
        mx = self.grid_x + self.flow_full[..., 0]
        my = self.grid_y + self.flow_full[..., 1]
        return cv2.remap(mask, mx, my, interpolation=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)

    def box_shift(self, box: tuple[float, float, float, float]) -> tuple[float, float]:
        """Median forward motion inside a box (current - previous)."""
        if self.flow_full is None:
            return 0.0, 0.0
        x1, y1, x2, y2 = (int(round(v)) for v in box)
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(self.w, max(x1 + 1, x2)), min(self.h, max(y1 + 1, y2))
        region = self.flow_full[y1:y2:2, x1:x2:2]
        if region.size == 0:
            return 0.0, 0.0
        # backward flow points to previous position, so motion is its negation
        return -float(np.median(region[..., 0])), -float(np.median(region[..., 1]))


def scene_cut(prev_small: np.ndarray | None, small: np.ndarray, threshold: float = 0.45) -> bool:
    """Detect hard cuts via HSV histogram correlation."""
    if prev_small is None:
        return False
    def hist(img: np.ndarray) -> np.ndarray:
        hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
        h = cv2.calcHist([hsv], [0, 1], None, [24, 16], [0, 180, 0, 256])
        return cv2.normalize(h, h).flatten()
    corr = cv2.compareHist(hist(prev_small), hist(small), cv2.HISTCMP_CORREL)
    return corr < (1.0 - threshold)


def _iou(a: tuple, b: tuple) -> float:
    ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / ua if ua > 0 else 0.0


@dataclass
class Track:
    tid: int
    label: str
    box: list[float]
    score: float
    hits: int = 1
    misses: int = 0  # consecutive detection rounds without a match
    age: int = 0


@dataclass
class BoxTracker:
    """Lightweight IoU tracker with flow-based motion prediction and exponential box smoothing.

    Tracks survive a few missed detection rounds so a region that is briefly missed
    (motion blur, occlusion, partial exit) stays censored instead of flickering.
    """

    max_misses: int = 3
    smooth: float = 0.55
    tracks: list[Track] = field(default_factory=list)
    _next: int = 0

    def reset(self) -> None:
        self.tracks.clear()

    def predict(self, flow: FlowEstimator) -> None:
        for t in self.tracks:
            dx, dy = flow.box_shift(tuple(t.box))
            t.box = [t.box[0] + dx, t.box[1] + dy, t.box[2] + dx, t.box[3] + dy]
            t.age += 1

    def update(self, dets: list[Detection]) -> None:
        unmatched = list(range(len(dets)))
        for t in self.tracks:
            best, best_iou = None, 0.2
            for i in unmatched:
                iou = _iou(tuple(t.box), dets[i].box)
                if iou > best_iou:
                    best, best_iou = i, iou
            if best is None:
                t.misses += 1
                continue
            unmatched.remove(best)
            d = dets[best]
            a = self.smooth
            t.box = [a * n + (1 - a) * o for n, o in zip(d.box, t.box)]
            t.score = max(d.score, 0.7 * t.score)
            t.label = d.label
            t.hits += 1
            t.misses = 0
        for i in unmatched:
            d = dets[i]
            self.tracks.append(Track(self._next, d.label, list(d.box), d.score))
            self._next += 1
        self.tracks = [t for t in self.tracks if t.misses <= self.max_misses]

    def render(self, shape: tuple[int, int], pad: float = 0.15) -> np.ndarray:
        """Draw tracked regions as soft ellipses into a float mask."""
        h, w = shape
        m = np.zeros((h, w), np.float32)
        for t in self.tracks:
            x1, y1, x2, y2 = t.box
            cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
            ax, ay = (x2 - x1) * (0.5 + pad), (y2 - y1) * (0.5 + pad)
            if ax < 1 or ay < 1:
                continue
            fade = 1.0 if t.misses == 0 else max(0.6, 1.0 - 0.1 * t.misses)
            cv2.ellipse(m, (int(cx), int(cy)), (int(ax), int(ay)), 0, 0, 360, fade, -1, cv2.LINE_AA)
        return m


class TemporalFuser:
    """Anti-flicker fusion of per-frame soft masks.

    * motion compensated — the previous state is warped with optical flow first,
    * fast attack — new detections appear immediately (no lag on censorship),
    * evidence-anchored memory — on a freshly measured frame the past can only *lift* the current
      score by a bounded amount (``lift``), never keep a pixel on by itself. Skin that moved away is
      released at once, so a moving arm leaves no trail behind it,
    * hysteresis — pixels need a high score to switch on but only a lower
      score to stay on, which removes edge chatter.
    """

    def __init__(self, memory: float = 0.4, lift: float = 0.35, on_threshold: float = 0.5,
                 off_ratio: float = 0.6) -> None:
        self.memory = memory
        self.lift = lift
        self.on = on_threshold
        self.off = on_threshold * off_ratio
        self.state: np.ndarray | None = None
        self.binary: np.ndarray | None = None

    def reset(self) -> None:
        self.state = None
        self.binary = None

    def update(self, current: np.ndarray, flow: FlowEstimator, fresh: bool) -> np.ndarray:
        if self.state is None:
            state = current
            prev_bin = np.zeros(current.shape, bool)
        else:
            warped = flow.warp(self.state)
            prev_bin = flow.warp(self.binary.astype(np.float32)) > 0.5
            if fresh:
                remembered = np.minimum(warped, current + self.lift)
                state = np.where(current >= warped, current, current * (1 - self.memory) + remembered * self.memory)
            else:
                # propagated frame: current *is* the warped measurement; keep the max
                state = np.maximum(current, warped * 0.97)
        binary = (state >= self.on) | (prev_bin & (state >= self.off))
        self.state = state.astype(np.float32)
        self.binary = binary
        return binary
