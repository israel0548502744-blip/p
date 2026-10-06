"""Stage 1 — person detection.

Finds every person in a frame with the YOLOX-tiny COCO detector
(only the ``person`` class is kept). Person boxes are what lets the pipeline
decide *per person* whether to censor, instead of censoring every skin pixel.

This module knows nothing about gender or nudity; see ``gender.py`` and
``detectors.py`` for those stages.
"""

from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

from . import models

INPUT = 416


@dataclass
class PersonBox:
    box: tuple[float, float, float, float]  # x1, y1, x2, y2 in frame pixels
    score: float


def _grid(size: int = INPUT) -> np.ndarray:
    """(grid x, grid y, stride) per output row, strides 8, 16, 32."""
    rows = []
    for st in (8, 16, 32):
        g = size // st
        ys, xs = np.meshgrid(np.arange(g), np.arange(g), indexing="ij")
        rows.append(np.stack([xs.ravel(), ys.ravel(), np.full(g * g, st)], 1))
    return np.concatenate(rows).astype(np.float32)


class PersonDetector:
    """YOLOX-tiny (Megvii, Apache-2.0), COCO class 0: input 416 x 416 BGR 0..255, letterboxed top-left with 114."""

    def __init__(self) -> None:
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.log_severity_level = 3
        self.session = ort.InferenceSession(str(models.ensure_person_detector()), sess_options=so, providers=models.onnx_providers())
        self._grid = _grid()

    def detect(self, bgr: np.ndarray, min_score: float = 0.35) -> list[PersonBox]:
        h, w = bgr.shape[:2]
        r = min(INPUT / w, INPUT / h)
        nw, nh = max(1, int(w * r)), max(1, int(h * r))
        x = np.full((INPUT, INPUT, 3), 114, np.uint8)
        x[:nh, :nw] = cv2.resize(bgr, (nw, nh), interpolation=cv2.INTER_AREA)
        out = self.session.run(None, {"images": x.transpose(2, 0, 1)[None].astype(np.float32)})[0][0]
        score = out[:, 4] * out[:, 5]
        keep = np.where(score >= min_score)[0]
        if keep.size == 0:
            return []
        o, g = out[keep], self._grid[keep]
        cx, cy = (o[:, 0] + g[:, 0]) * g[:, 2] / r, (o[:, 1] + g[:, 1]) * g[:, 2] / r
        bw, bh = np.exp(o[:, 2]) * g[:, 2] / r, np.exp(o[:, 3]) * g[:, 2] / r
        x1, y1 = np.clip(cx - bw / 2, 0, w), np.clip(cy - bh / 2, 0, h)
        x2, y2 = np.clip(cx + bw / 2, 0, w), np.clip(cy + bh / 2, 0, h)
        rects = [[float(a_), float(b_), float(c_ - a_), float(d_ - b_)] for a_, b_, c_, d_ in zip(x1, y1, x2, y2)]
        sc = score[keep].astype(float).tolist()
        idx = np.array(cv2.dnn.NMSBoxes(rects, sc, min_score, 0.45)).reshape(-1)
        out_boxes = [PersonBox((float(x1[i]), float(y1[i]), float(x2[i]), float(y2[i])), float(sc[i]))
                     for i in idx if (x2[i] - x1[i]) > 4 and (y2[i] - y1[i]) > 8]
        return suppress_contained(out_boxes)


CONTAINED_MIN = 0.8  # a box this much inside a bigger one is a partial (e.g. upper-body) duplicate


def containment(inner: tuple[float, float, float, float], outer: tuple[float, float, float, float]) -> float:
    """Fraction of ``inner``'s area that lies inside ``outer``."""
    ix = max(0.0, min(inner[2], outer[2]) - max(inner[0], outer[0]))
    iy = max(0.0, min(inner[3], outer[3]) - max(inner[1], outer[1]))
    area = max(1e-6, (inner[2] - inner[0]) * (inner[3] - inner[1]))
    return ix * iy / area


def suppress_contained(dets: list[PersonBox]) -> list[PersonBox]:
    """Drop partial duplicates: a detection lying almost entirely inside a bigger one of the same person.

    Plain NMS keeps them because their IoU is small; left alone they become a second "person" that splits
    the gender evidence (both halves then stay "uncertain"). The smaller box survives only when the
    detector is clearly more confident about it.
    """
    def area(d: PersonBox) -> float:
        return (d.box[2] - d.box[0]) * (d.box[3] - d.box[1])

    order = sorted(dets, key=area, reverse=True)
    keep: list[PersonBox] = []
    for d in order:
        if any(containment(d.box, k.box) > CONTAINED_MIN and d.score < k.score + 0.15 for k in keep):
            continue
        keep.append(d)
    return keep
