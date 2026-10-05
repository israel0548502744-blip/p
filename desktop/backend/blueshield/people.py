"""Stage 3 — person tracking.

Gives every person a stable identity across frames so that the gender decision
(and any manual override) applies to the whole time that person is on screen.

* IoU + appearance (HSV histogram) matching between detections and tracks.
* Optical-flow motion prediction between detection keyframes.
* Tracks survive short detection gaps; lost tracks are kept in a short-term
  "gallery" and re-identified by appearance when a person re-enters the frame
  or after a scene cut, so they keep their identity and accumulated evidence.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

import cv2
import numpy as np

from .gender import GenderEstimate
from .persons import PersonBox
from .tracking import FlowEstimator, _iou


def appearance(frame: np.ndarray, box: tuple[float, float, float, float]) -> Optional[np.ndarray]:
    """Torso-weighted HSV colour histogram used for re-identification."""
    h, w = frame.shape[:2]
    x1, y1, x2, y2 = (int(round(v)) for v in box)
    bw, bh = x2 - x1, y2 - y1
    # central torso band: avoids background at the box edges
    rx1, rx2 = max(0, x1 + bw // 5), min(w, x2 - bw // 5)
    ry1, ry2 = max(0, y1 + bh // 6), min(h, y1 + int(bh * 0.75))
    if rx2 - rx1 < 4 or ry2 - ry1 < 4:
        return None
    hsv = cv2.cvtColor(frame[ry1:ry2, rx1:rx2], cv2.COLOR_BGR2HSV)
    hist = cv2.calcHist([hsv], [0, 1], None, [16, 8], [0, 180, 0, 256])
    return cv2.normalize(hist, hist).flatten()


def hist_sim(a: Optional[np.ndarray], b: Optional[np.ndarray]) -> float:
    if a is None or b is None:
        return 0.5
    return float(max(0.0, cv2.compareHist(a, b, cv2.HISTCMP_CORREL)))


@dataclass
class PersonTrack:
    tid: int
    box: list[float]
    score: float
    hist: Optional[np.ndarray]
    gender: GenderEstimate = field(default_factory=GenderEstimate)
    misses: int = 0
    hits: int = 1
    first_frame: int = 0
    last_frame: int = 0
    frames: int = 0  # frames this identity was on screen
    thumb: Optional[bytes] = None
    thumb_quality: float = 0.0
    lost_at: int = -1


class PersonTracker:
    def __init__(self, max_misses: int = 6, gallery_frames: int = 300) -> None:
        self.max_misses = max_misses  # detection rounds a track may go unmatched
        self.gallery_frames = gallery_frames
        self.active: list[PersonTrack] = []
        self.gallery: list[PersonTrack] = []
        self.all: dict[int, PersonTrack] = {}
        self._next = 1

    def reset(self, frame_index: int) -> None:
        """Scene cut: every active track goes to the gallery (still re-identifiable)."""
        for t in self.active:
            t.lost_at = frame_index
            self.gallery.append(t)
        self.active = []

    def predict(self, flow: FlowEstimator) -> None:
        for t in self.active:
            dx, dy = flow.box_shift(tuple(t.box))
            t.box = [t.box[0] + dx, t.box[1] + dy, t.box[2] + dx, t.box[3] + dy]

    def update(self, frame: np.ndarray, dets: list[PersonBox], frame_index: int) -> None:
        hists = [appearance(frame, d.box) for d in dets]
        # greedy matching on a combined IoU + appearance cost
        pairs = []
        for ti, t in enumerate(self.active):
            for di, d in enumerate(dets):
                iou = _iou(tuple(t.box), d.box)
                if iou < 0.15:
                    continue
                pairs.append((iou * 0.7 + 0.3 * hist_sim(t.hist, hists[di]), ti, di))
        pairs.sort(reverse=True)
        used_t, used_d = set(), set()
        for _, ti, di in pairs:
            if ti in used_t or di in used_d:
                continue
            used_t.add(ti)
            used_d.add(di)
            t, d = self.active[ti], dets[di]
            t.box = [0.6 * n + 0.4 * o for n, o in zip(d.box, t.box)]
            t.score = d.score
            if hists[di] is not None:
                t.hist = hists[di] if t.hist is None else 0.85 * t.hist + 0.15 * hists[di]
            t.misses = 0
            t.hits += 1
        for ti, t in enumerate(self.active):
            if ti not in used_t:
                t.misses += 1
        # lost tracks -> gallery
        still = []
        for t in self.active:
            if t.misses > self.max_misses:
                t.lost_at = frame_index
                self.gallery.append(t)
            else:
                still.append(t)
        self.active = still
        self.gallery = [g for g in self.gallery if frame_index - g.lost_at <= self.gallery_frames]
        # unmatched detections: re-identify from the gallery, else start a new identity
        for di, d in enumerate(dets):
            if di in used_d:
                continue
            best, best_sim = None, 0.72
            for g in self.gallery:
                s = hist_sim(g.hist, hists[di])
                if s > best_sim:
                    best, best_sim = g, s
            if best is not None:
                self.gallery.remove(best)
                best.box, best.score, best.misses = list(d.box), d.score, 0
                self.active.append(best)
                continue
            t = PersonTrack(self._next, list(d.box), d.score, hists[di], first_frame=frame_index)
            self._next += 1
            self.active.append(t)
            self.all[t.tid] = t

    def visible(self) -> list[PersonTrack]:
        """Tracks considered on screen this frame (recently matched)."""
        return [t for t in self.active if t.misses <= self.max_misses]

    def mark_frame(self, frame: np.ndarray, frame_index: int) -> None:
        for t in self.visible():
            if t.frames == 0:
                t.first_frame = frame_index
            t.frames += 1
            t.last_frame = frame_index
            if t.misses == 0 and frame_index % 6 == 0:
                x1, y1, x2, y2 = t.box
                quality = (x2 - x1) * (y2 - y1) * t.score
                if quality > t.thumb_quality * 1.15:
                    crop = _thumb(frame, t.box)
                    if crop is not None:
                        t.thumb, t.thumb_quality = crop, quality


def _thumb(frame: np.ndarray, box: list[float]) -> Optional[bytes]:
    h, w = frame.shape[:2]
    x1, y1, x2, y2 = box
    bw, bh = x2 - x1, y2 - y1
    side = max(bw, min(bh, bw * 1.4) * 0.8)
    cx = (x1 + x2) / 2
    a, b = int(max(0, cx - side / 2)), int(max(0, y1 - 0.04 * bh))
    c, d = int(min(w, cx + side / 2)), int(min(h, b + side))
    if c - a < 8 or d - b < 8:
        return None
    crop = cv2.resize(frame[b:d, a:c], (160, int(160 * (d - b) / (c - a))), interpolation=cv2.INTER_AREA)
    ok, buf = cv2.imencode(".jpg", crop, [cv2.IMWRITE_JPEG_QUALITY, 85])
    return buf.tobytes() if ok else None
