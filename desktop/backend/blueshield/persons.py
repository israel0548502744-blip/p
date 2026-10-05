"""Stage 1 — person detection.

Finds every person in a frame with the MediaPipe EfficientDet-Lite0 COCO detector
(only the ``person`` class is kept). Person boxes are what lets the pipeline
decide *per person* whether to censor, instead of censoring every skin pixel.

This module knows nothing about gender or nudity; see ``gender.py`` and
``detectors.py`` for those stages.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

import cv2
import numpy as np

from . import models

INPUT = 320
PERSON_CLASS = 0  # COCO "person" (background-less 90-class head)


@dataclass
class PersonBox:
    box: tuple[float, float, float, float]  # x1, y1, x2, y2 in frame pixels
    score: float


def _ssd_anchors(size: int = INPUT) -> np.ndarray:
    """EfficientDet anchors: levels 3–7, 3 octave scales x 3 aspect ratios, (cy, cx, h, w)."""
    out = []
    for lvl in range(3, 8):
        stride = 2 ** lvl
        n = int(np.ceil(size / stride))
        cfg = []
        for octave in range(3):
            for ar in (1.0, 2.0, 0.5):
                base = 4.0 * stride * 2 ** (octave / 3)
                cfg.append((base / np.sqrt(ar), base * np.sqrt(ar)))
        ys, xs = np.meshgrid(np.arange(n), np.arange(n), indexing="ij")
        cy = (ys.ravel() + 0.5) * stride
        cx = (xs.ravel() + 0.5) * stride
        lvl_a = np.zeros((n * n, len(cfg), 4), np.float32)
        for k, (ah, aw) in enumerate(cfg):
            lvl_a[:, k] = np.stack([cy, cx, np.full_like(cy, ah), np.full_like(cx, aw)], 1)
        out.append(lvl_a.reshape(-1, 4))
    return np.concatenate(out)


class PersonDetector:
    def __init__(self) -> None:
        path = str(models.ensure_person_detector())
        self.backend = "litert"
        try:
            from ai_edge_litert.interpreter import Interpreter  # type: ignore

            self._it = Interpreter(model_path=path, num_threads=max(1, os.cpu_count() or 1))
            self._it.allocate_tensors()
            self._in = self._it.get_input_details()[0]["index"]
            outs = self._it.get_output_details()
            # outputs: scores (1, N, 90) and box deltas (1, N, 4)
            self._scores = next(o["index"] for o in outs if o["shape"][-1] != 4)
            self._boxes = next(o["index"] for o in outs if o["shape"][-1] == 4)
            self._anchors = _ssd_anchors()
        except ImportError:
            import mediapipe as mp
            from mediapipe.tasks.python import BaseOptions, vision

            self.backend = "mediapipe"
            self._mp = mp
            opts = vision.ObjectDetectorOptions(
                base_options=BaseOptions(model_asset_path=path),
                running_mode=vision.RunningMode.IMAGE,
                score_threshold=0.2, category_allowlist=["person"], max_results=20,
            )
            self._det = vision.ObjectDetector.create_from_options(opts)

    def detect(self, bgr: np.ndarray, min_score: float = 0.35) -> list[PersonBox]:
        h, w = bgr.shape[:2]
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        if self.backend == "mediapipe":
            res = self._det.detect(self._mp.Image(image_format=self._mp.ImageFormat.SRGB, data=rgb))
            out = []
            for d in res.detections:
                b = d.bounding_box
                s = d.categories[0].score if d.categories else 0.0
                if s >= min_score:
                    out.append(PersonBox((b.origin_x, b.origin_y, b.origin_x + b.width, b.origin_y + b.height), s))
            return out
        x = cv2.resize(rgb, (INPUT, INPUT), interpolation=cv2.INTER_AREA).astype(np.float32)
        x = (x - 127.0) / 128.0
        self._it.set_tensor(self._in, x[None])
        self._it.invoke()
        scores = self._it.get_tensor(self._scores)[0][:, PERSON_CLASS]
        keep = np.where(scores >= min_score)[0]
        if keep.size == 0:
            return []
        deltas = self._it.get_tensor(self._boxes)[0][keep]
        a = self._anchors[keep]
        cy = deltas[:, 0] * a[:, 2] + a[:, 0]
        cx = deltas[:, 1] * a[:, 3] + a[:, 1]
        bh = np.exp(deltas[:, 2]) * a[:, 2]
        bw = np.exp(deltas[:, 3]) * a[:, 3]
        sx, sy = w / INPUT, h / INPUT
        x1 = np.clip((cx - bw / 2) * sx, 0, w)
        y1 = np.clip((cy - bh / 2) * sy, 0, h)
        x2 = np.clip((cx + bw / 2) * sx, 0, w)
        y2 = np.clip((cy + bh / 2) * sy, 0, h)
        rects = [[float(a_), float(b_), float(c_ - a_), float(d_ - b_)] for a_, b_, c_, d_ in zip(x1, y1, x2, y2)]
        sc = scores[keep].astype(float).tolist()
        idx = np.array(cv2.dnn.NMSBoxes(rects, sc, min_score, 0.5)).reshape(-1)
        return [PersonBox((float(x1[i]), float(y1[i]), float(x2[i]), float(y2[i])), float(sc[i]))
                for i in idx if (x2[i] - x1[i]) > 4 and (y2[i] - y1[i]) > 8]
