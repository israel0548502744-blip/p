"""Computer-vision detectors.

* :class:`SkinSegmenter` — per-pixel body-skin segmentation (MediaPipe Selfie Multiclass).
  Produces soft masks that follow the true outline of exposed skin.
* :class:`SensitiveRegionDetector` — NudeNet YOLOv8 detector for exposed sensitive
  body areas. Supports batched inference and GPU execution providers.
* :func:`color_skin_probability` — classic YCrCb/HSV skin model, used only *inside*
  person regions to catch skin the network missed (aggressive mode).
"""

from __future__ import annotations

import os
from dataclasses import dataclass

import cv2
import numpy as np

from . import models

# Selfie multiclass category indices.
CAT_BACKGROUND, CAT_HAIR, CAT_BODY_SKIN, CAT_FACE_SKIN, CAT_CLOTHES, CAT_OTHERS = range(6)
FACE_EXCLUSION = 0.35  # facial-skin probability above which a pixel is never treated as body skin


# per-person crops: skipped when they'd be (almost) the whole frame anyway; whole-frame pass every Nth detection round
ROI_MAX_FRAME_RATIO = 0.9
ROI_FULL_EVERY_DET = 2


@dataclass
class SegResult:
    skin: np.ndarray  # float32 HxW in [0,1] — body skin (+ face if requested)
    person: np.ndarray  # float32 HxW in [0,1] — any person pixel
    face: np.ndarray  # float32 HxW in [0,1] — facial skin


class SkinSegmenter:
    """Body-skin segmentation with the MediaPipe Selfie Multiclass model.

    Uses the LiteRT (TensorFlow Lite) interpreter with XNNPACK and all CPU cores when
    available — ~3x faster than the MediaPipe task wrapper — and falls back to
    the MediaPipe Tasks API otherwise (e.g. on Windows).
    """

    SIZE = 256

    def __init__(self) -> None:
        path = str(models.ensure_segmenter())
        self.backend = "litert"
        try:
            from ai_edge_litert.interpreter import Interpreter  # type: ignore

            self._it = Interpreter(model_path=path, num_threads=max(1, os.cpu_count() or 1))
            self._it.allocate_tensors()
            self._in = self._it.get_input_details()[0]["index"]
            self._out = self._it.get_output_details()[0]["index"]
        except ImportError:
            import mediapipe as mp
            from mediapipe.tasks.python import BaseOptions, vision

            self.backend = "mediapipe"
            self._mp = mp
            opts = vision.ImageSegmenterOptions(
                base_options=BaseOptions(model_asset_path=path),
                running_mode=vision.RunningMode.IMAGE,
                output_confidence_masks=True,
                output_category_mask=False,
            )
            self._seg = vision.ImageSegmenter.create_from_options(opts)

    def _run(self, rgb: np.ndarray) -> np.ndarray:
        """Return per-class probabilities (H, W, 6) at the input's resolution.

        Non-square inputs are letterboxed (edge-replicated) to a square instead of being squashed,
        so a portrait video keeps its proportions and arms keep their shape.
        """
        h, w = rgb.shape[:2]
        if self.backend == "litert":
            side = max(h, w)
            top, left = (side - h) // 2, (side - w) // 2
            sq = rgb if side == h == w else cv2.copyMakeBorder(rgb, top, side - h - top, left, side - w - left,
                                                               cv2.BORDER_REPLICATE)
            x = cv2.resize(sq, (self.SIZE, self.SIZE), interpolation=cv2.INTER_LINEAR).astype(np.float32)
            x = x * (1 / 127.5) - 1.0
            self._it.set_tensor(self._in, x[None])
            self._it.invoke()
            logits = self._it.get_tensor(self._out)[0]
            e = np.exp(logits - logits.max(-1, keepdims=True))
            probs = cv2.resize(e / e.sum(-1, keepdims=True), (side, side), interpolation=cv2.INTER_LINEAR)
            return np.ascontiguousarray(probs[top:top + h, left:left + w])
        img = self._mp.Image(image_format=self._mp.ImageFormat.SRGB, data=np.ascontiguousarray(rgb))
        res = self._seg.segment(img)
        return np.stack([np.squeeze(m.numpy_view()).astype(np.float32) for m in res.confidence_masks], -1)

    def segment_rois(self, bgr: np.ndarray, boxes: list[tuple[float, float, float, float]], base: SegResult,
                     include_face: bool = False, side_scale: float = 1.15, paste_pad: float = 0.08,
                     min_side: int = 24, base_is_fresh: bool = False,
                     max_frame_ratio: float = ROI_MAX_FRAME_RATIO) -> SegResult:
        """Re-segment each person at much higher effective resolution.

        The model only sees 256x256 pixels, so on a whole frame an arm is a handful of pixels. Here every
        person gets their own square crop (``side_scale`` x the longer box side, no distortion), and the
        result replaces ``base`` inside that person's (slightly padded) box.
        """
        h, w = bgr.shape[:2]
        skin, person, face = base.skin.copy(), base.person.copy(), base.face.copy()
        roi_skin = np.zeros_like(skin)
        roi_person = np.zeros_like(person)
        roi_face = np.zeros_like(face)
        cover = np.zeros(skin.shape, bool)
        rgb_full = None
        for (x1, y1, x2, y2) in boxes:
            bw, bh = x2 - x1, y2 - y1
            side = int(round(max(bw, bh) * side_scale))
            if side < min_side:
                continue
            if base_is_fresh and side >= max_frame_ratio * max(h, w):
                continue  # the crop would be the whole frame again: the fresh full pass already is that
            cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
            a, b = int(round(cx - side / 2)), int(round(cy - side / 2))
            # edge-replicated crop (the box may reach past the frame)
            pad = (max(0, -b), max(0, b + side - h), max(0, -a), max(0, a + side - w))
            crop = bgr[max(0, b):min(h, b + side), max(0, a):min(w, a + side)]
            if crop.size == 0:
                continue
            crop = cv2.copyMakeBorder(crop, *pad, cv2.BORDER_REPLICATE)
            s_, p_, f_ = self._masks(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB), include_face)
            # region of the frame this person owns: their padded box, intersected with the crop
            px, py = paste_pad * bw, paste_pad * bh
            rx1, ry1 = max(0, int(x1 - px), a), max(0, int(y1 - py), b)
            rx2, ry2 = min(w, int(x2 + px) + 1, a + side), min(h, int(y2 + py) + 1, b + side)
            if rx2 <= rx1 or ry2 <= ry1:
                continue
            src = (slice(ry1 - b, ry2 - b), slice(rx1 - a, rx2 - a))
            dst = (slice(ry1, ry2), slice(rx1, rx2))
            np.maximum(roi_skin[dst], s_[src], out=roi_skin[dst])
            np.maximum(roi_person[dst], p_[src], out=roi_person[dst])
            np.maximum(roi_face[dst], f_[src], out=roi_face[dst])
            cover[dst] = True
        skin[cover], person[cover], face[cover] = roi_skin[cover], roi_person[cover], roi_face[cover]
        return SegResult(skin=skin, person=person, face=face)

    def segment(self, bgr: np.ndarray, include_face: bool = False, tiled: bool = False) -> SegResult:
        """Segment a BGR frame.

        The model works on a 256x256 squashed view, so small people in wide frames
        lose detail. With ``tiled=True`` the frame is additionally processed as
        overlapping square crops and the results are max-fused.
        """
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        skin, person, face = self._masks(rgb, include_face)
        h, w = rgb.shape[:2]
        if tiled and (w > h * 1.2 or h > w * 1.2):
            side, long = min(h, w), max(h, w)
            n = int(np.ceil(long / side)) + 1
            for i in range(n):
                off = int(round(i * (long - side) / max(n - 1, 1)))
                sl = (slice(None), slice(off, off + side)) if w >= h else (slice(off, off + side), slice(None))
                s, p, f = self._masks(rgb[sl], include_face)
                np.maximum(skin[sl], s, out=skin[sl])
                np.maximum(person[sl], p, out=person[sl])
                np.maximum(face[sl], f, out=face[sl])
        return SegResult(skin=skin, person=person, face=face)

    def _masks(self, rgb: np.ndarray, include_face: bool) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        m = self._run(rgb)
        skin = m[..., CAT_BODY_SKIN].copy()
        face = np.ascontiguousarray(m[..., CAT_FACE_SKIN])
        if include_face:
            skin = np.maximum(skin, face)
        else:
            # the body-skin class sometimes bleeds onto faces (glasses, side light): keep faces clear
            skin[face >= FACE_EXCLUSION] = 0.0
        person = 1.0 - m[..., CAT_BACKGROUND]
        return skin, np.ascontiguousarray(person), face


# NudeNet v3 label set (order matters — matches the model's output channels).
NUDENET_LABELS = [
    "FEMALE_GENITALIA_COVERED", "FACE_FEMALE", "BUTTOCKS_EXPOSED", "FEMALE_BREAST_EXPOSED",
    "FEMALE_GENITALIA_EXPOSED", "MALE_BREAST_EXPOSED", "ANUS_EXPOSED", "FEET_EXPOSED",
    "BELLY_COVERED", "FEET_COVERED", "ARMPITS_COVERED", "ARMPITS_EXPOSED", "FACE_MALE",
    "BELLY_EXPOSED", "MALE_GENITALIA_EXPOSED", "ANUS_COVERED", "FEMALE_BREAST_COVERED",
    "BUTTOCKS_COVERED",
]
# Face boxes (any gender label) — used only to keep faces *un*censored when face censoring is off.
FACE_LABELS = {"FACE_FEMALE", "FACE_MALE"}
# Always censored when detected.
SENSITIVE_LABELS = {
    "BUTTOCKS_EXPOSED", "FEMALE_BREAST_EXPOSED", "FEMALE_GENITALIA_EXPOSED",
    "ANUS_EXPOSED", "MALE_GENITALIA_EXPOSED",
}
# Additionally censored in aggressive mode (includes "covered" but revealing regions).
AGGRESSIVE_LABELS = SENSITIVE_LABELS | {
    "MALE_BREAST_EXPOSED", "BELLY_EXPOSED", "ARMPITS_EXPOSED",
    "FEMALE_BREAST_COVERED", "FEMALE_GENITALIA_COVERED", "BUTTOCKS_COVERED",
}


@dataclass
class Detection:
    label: str
    score: float
    box: tuple[float, float, float, float]  # x1, y1, x2, y2 in frame pixels


class SensitiveRegionDetector:
    def __init__(self, input_size: int = 320) -> None:
        import onnxruntime as ort

        path = models.ensure_nudenet()
        so = ort.SessionOptions()
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.session = ort.InferenceSession(str(path), sess_options=so, providers=models.onnx_providers())
        self.input_name = self.session.get_inputs()[0].name
        self.size = input_size

    def _prep(self, bgr: np.ndarray) -> tuple[np.ndarray, float]:
        h, w = bgr.shape[:2]
        side = max(h, w)
        padded = cv2.copyMakeBorder(bgr, 0, side - h, 0, side - w, cv2.BORDER_CONSTANT)
        blob = cv2.resize(padded, (self.size, self.size), interpolation=cv2.INTER_AREA)
        blob = cv2.cvtColor(blob, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        return blob.transpose(2, 0, 1), side / self.size

    def detect_batch(self, frames: list[np.ndarray], min_score: float = 0.25) -> list[list[Detection]]:
        if not frames:
            return []
        preps = [self._prep(f) for f in frames]
        batch = np.stack([p[0] for p in preps]).astype(np.float32)
        out = self.session.run(None, {self.input_name: batch})[0]  # (B, 4+C, N)
        results = []
        for b, (_, scale) in enumerate(preps):
            h, w = frames[b].shape[:2]
            pred = out[b].T  # (N, 4+C)
            cls_scores = pred[:, 4:]
            cls = cls_scores.argmax(1)
            score = cls_scores[np.arange(len(cls)), cls]
            keep = score >= min_score
            pred, cls, score = pred[keep], cls[keep], score[keep]
            boxes = []
            for (cx, cy, bw, bh) in pred[:, :4]:
                x1 = max(0.0, (cx - bw / 2) * scale)
                y1 = max(0.0, (cy - bh / 2) * scale)
                x2 = min(float(w), (cx + bw / 2) * scale)
                y2 = min(float(h), (cy + bh / 2) * scale)
                boxes.append([x1, y1, x2 - x1, y2 - y1])
            dets: list[Detection] = []
            if boxes:
                idx = cv2.dnn.NMSBoxes(boxes, score.tolist(), min_score, 0.45)
                for i in np.array(idx).reshape(-1):
                    x, y, bw, bh = boxes[i]
                    dets.append(Detection(NUDENET_LABELS[int(cls[i])], float(score[i]),
                                          (x, y, x + bw, y + bh)))
            results.append(dets)
        return results


SKIN_MAX_BLUE_OVER_RED = 6  # a skin pixel is never clearly bluer than it is red
SKIN_MIN_LUMA = 28  # …and never almost black (navy / black clothes are the usual false positives)


def skin_color_plausible(bgr: np.ndarray) -> np.ndarray:
    """1.0 where the pixel colour *could* be skin, 0.0 for bluish or near-black pixels (soft-edged)."""
    b = bgr[..., 0].astype(np.int32)
    g = bgr[..., 1].astype(np.int32)
    r = bgr[..., 2].astype(np.int32)
    luma = (299 * r + 587 * g + 114 * b) // 1000
    ok = ((b - r) <= SKIN_MAX_BLUE_OVER_RED) & (luma >= SKIN_MIN_LUMA)
    return cv2.GaussianBlur(ok.astype(np.float32), (0, 0), 1.0)


def color_skin_probability(bgr: np.ndarray) -> np.ndarray:
    """Heuristic skin likelihood in [0,1] from YCrCb + HSV ranges (robust across skin tones)."""
    ycrcb = cv2.cvtColor(bgr, cv2.COLOR_BGR2YCrCb)
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    y, cr, cb = cv2.split(ycrcb)
    hch, s, v = cv2.split(hsv)
    m1 = (cr >= 135) & (cr <= 180) & (cb >= 85) & (cb <= 135) & (y > 40)
    m2 = ((hch <= 25) | (hch >= 165)) & (s >= 30) & (s <= 200) & (v >= 50)
    prob = (m1 & m2).astype(np.float32)
    return cv2.GaussianBlur(prob, (0, 0), 1.5)
