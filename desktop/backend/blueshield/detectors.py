"""Computer-vision detectors.

* :class:`SkinSegmenter` — per-pixel body-skin segmentation (MediaPipe Selfie Multiclass).
  Produces soft masks that follow the true outline of exposed skin.
* :class:`SensitiveRegionDetector` — NudeNet YOLOv8 detector for exposed sensitive
  body areas. Supports batched inference and GPU execution providers.
* :class:`ClothesSegmenter` — skin / clothes / hair segmenter, a second opinion on clothing (the clothes veto,
  :func:`veto_blobs`).
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


ROI_MAX_TILES = 3  # extra crops along a tall (standing) or wide (lying / arms out) person
ROI_TILE_MIN_SIDE = 384  # tiles only when the whole-person crop is shrunk >= 1.5x (small people: one crop is enough)


def roi_crops(box, side_scale: float = 1.15) -> list[tuple[int, int, int]]:
    """Square crops (left, top, side) that segment one person: the whole person, plus — for a tall or wide
    box — up to ROI_MAX_TILES overlapping squares along the long side. A standing person's whole-body crop
    shrinks them to 256 px; the tiles see the torso and arms about twice as large (a belly-dance costume's
    bare midriff was missed otherwise). Same as the Android ``SkinSegmenter.roiCrops``."""
    x1, y1, x2, y2 = box
    bw, bh = x2 - x1, y2 - y1
    cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
    side = int(round(max(bw, bh) * side_scale))
    out = [(int(round(cx - side / 2)), int(round(cy - side / 2)), side)]
    long_, short = max(bw, bh), min(bw, bh)
    if short > 0 and long_ > 1.3 * short and side >= ROI_TILE_MIN_SIDE:
        n = min(ROI_MAX_TILES, max(2, int(np.ceil(long_ / (short * 1.25)))))
        t = int(round(max(short * 1.25, long_ / n * 1.2)))
        for i in range(n):
            f = (i + 0.5) / n
            if bh >= bw:
                tx, ty = cx, y1 + f * bh
            else:
                tx, ty = x1 + f * bw, cy
            out.append((int(round(tx - t / 2)), int(round(ty - t / 2)), t))
    return out


# per-person crops: skipped when they'd be (almost) the whole frame anyway; whole-frame pass every Nth detection round
ROI_MAX_FRAME_RATIO = 0.9
# close-ups of skin found away from every person (SkinSegmenter._close_up_orphans): at most ORPHAN_MAX per frame,
# of blobs covering at least ORPHAN_MIN_AREA of the frame, each seen in a square ORPHAN_SCALE times its size
ORPHAN_MAX = 4
ORPHAN_MIN_AREA = 0.0005
ORPHAN_SCALE = 2.0
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
                     max_frame_ratio: float = ROI_MAX_FRAME_RATIO, orphan_skin: float = 0.0) -> SegResult:
        """Re-segment each person at much higher effective resolution.

        The model only sees 256x256 pixels, so on a whole frame an arm is a handful of pixels. Here every
        person gets their own square crop (``side_scale`` x the longer box side, no distortion), and the
        result replaces ``base`` inside that person's (slightly padded) box. With ``orphan_skin`` > 0 (and a
        fresh base), skin of the base outside every crop gets a close-up of its own (``_close_up_orphans``).
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
            px, py = paste_pad * bw, paste_pad * bh
            for k, (a, b, side) in enumerate(roi_crops((x1, y1, x2, y2), side_scale)):
                if side < min_side:
                    continue
                if k == 0 and base_is_fresh and side >= max_frame_ratio * max(h, w):
                    continue  # the whole-person crop would be the whole frame again: the fresh full pass already is that
                # edge-replicated crop (the box may reach past the frame)
                pad = (max(0, -b), max(0, b + side - h), max(0, -a), max(0, a + side - w))
                crop = bgr[max(0, b):min(h, b + side), max(0, a):min(w, a + side)]
                if crop.size == 0:
                    continue
                crop = cv2.copyMakeBorder(crop, *pad, cv2.BORDER_REPLICATE)
                s_, p_, f_ = self._masks(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB), include_face)
                # region of the frame this person owns: their padded box, intersected with the crop
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
        if orphan_skin > 0 and base_is_fresh and cover.any():
            self._close_up_orphans(bgr, base.skin, (roi_skin, roi_person, roi_face), cover, orphan_skin, include_face, min_side)
        skin[cover], person[cover], face[cover] = roi_skin[cover], roi_person[cover], roi_face[cover]
        return SegResult(skin=skin, person=person, face=face)

    def _close_up_orphans(self, bgr: np.ndarray, base_skin: np.ndarray, out: tuple, cover: np.ndarray, thr: float,
                          include_face: bool, min_side: int) -> None:
        """Skin the whole-frame pass found away from every person's crop was seen at a few pixels per finger —
        and that is where it calls a car's red paint or a wooden door skin. Each such blob (the ORPHAN_MAX largest
        of at least ORPHAN_MIN_AREA of the frame) gets a close-up of its own, a square ORPHAN_SCALE times its size,
        whose result replaces the coarse one around it: real skin (a person the detector missed, an arm reaching
        far out) stays skin, the false ones go. Same as the Android ``SkinSegmenter.closeUpOrphans``."""
        h, w = bgr.shape[:2]
        on = (~cover) & (base_skin >= thr)
        n, _, st, _ = cv2.connectedComponentsWithStats(on.astype(np.uint8), connectivity=8)
        blobs = [i for i in (np.argsort(-st[1:, 4], kind="stable") + 1) if st[i, 4] >= ORPHAN_MIN_AREA * w * h][:ORPHAN_MAX]
        for i in blobs:
            x0, y0, bw, bh = (int(v) for v in st[i, :4])
            side = max(min_side, int(np.floor(max(bw, bh) * ORPHAN_SCALE + 0.5)))
            a = int(np.floor(x0 + bw / 2 - side / 2 + 0.5))
            b = int(np.floor(y0 + bh / 2 - side / 2 + 0.5))
            m = max(2, max(bw, bh) // 4)
            rx1, ry1, rx2, ry2 = max(0, x0 - m), max(0, y0 - m), min(w, x0 + bw + m), min(h, y0 + bh + m)
            pad = (max(0, -b), max(0, b + side - h), max(0, -a), max(0, a + side - w))
            crop = cv2.copyMakeBorder(bgr[max(0, b):min(h, b + side), max(0, a):min(w, a + side)], *pad, cv2.BORDER_REPLICATE)
            maps = self._masks(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB), include_face)
            dst = (slice(ry1, ry2), slice(rx1, rx2))
            free = ~cover[dst]
            for o, m_ in zip(out, maps):
                o[dst][free] = m_[ry1 - b:ry2 - b, rx1 - a:rx2 - a][free]
            cover[dst] = True

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


class ClothesSegmenter:
    """Skin / clothes / hair segmentation (Kazuhito Takahashi's DeepLabV3+ on MobileNetV3-small, MIT): a second
    opinion on what is clothing. The selfie model now and then calls a belt, trousers or a purple-lit dress body
    skin; this model, trained on exactly skin vs. clothes, vetoes those blobs (:func:`veto_blobs`). It sees the
    square whole-person crops of the close-up skin pass at SIZE x SIZE (RGB / 255, ImageNet mean and std, NCHW;
    outputs are probabilities: skin, clothes, hair). Same as the Android ``ClothesSegmenter``."""

    SIZE = 512
    MEAN = np.array([0.485, 0.456, 0.406], np.float32)
    STD = np.array([0.229, 0.224, 0.225], np.float32)

    def __init__(self) -> None:
        import onnxruntime as ort

        so = ort.SessionOptions()
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.session = ort.InferenceSession(str(models.CLOTHES_FILE), sess_options=so, providers=models.onnx_providers())
        self.input_name = self.session.get_inputs()[0].name
        self.runs = 0

    def segment(self, bgr: np.ndarray, crops: list[tuple[int, int, int]]) -> np.ndarray:
        """Frame-sized clothes probability from square crops (left, top, side), 0 outside every crop (max where
        crops overlap); out-of-frame parts of a crop are edge-replicated."""
        h, w = bgr.shape[:2]
        out = np.zeros((h, w), np.float32)
        for a, b, side in crops:
            x0, y0, x1, y1 = max(0, a), max(0, b), min(w, a + side), min(h, b + side)
            if x1 <= x0 or y1 <= y0:
                continue
            crop = cv2.copyMakeBorder(bgr[y0:y1, x0:x1], y0 - b, b + side - y1, x0 - a, a + side - x1, cv2.BORDER_REPLICATE)
            rgb = cv2.resize(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB), (self.SIZE, self.SIZE),
                             interpolation=cv2.INTER_AREA if side >= 2 * self.SIZE else cv2.INTER_LINEAR)
            x = ((rgb.astype(np.float32) / 255.0 - self.MEAN) / self.STD).transpose(2, 0, 1)[None]
            clothes = self.session.run(None, {self.input_name: np.ascontiguousarray(x)})[0][0, 1]
            self.runs += 1
            c = cv2.resize(clothes, (side, side), interpolation=cv2.INTER_LINEAR)
            np.maximum(out[y0:y1, x0:x1], c[y0 - b:y1 - b, x0 - a:x1 - a], out=out[y0:y1, x0:x1])
        return out


def veto_blobs(skin: np.ndarray, selfie: np.ndarray, clothes: np.ndarray, clothes_min: float, skin_max: float,
               on: float, share: float, rim: int) -> np.ndarray:
    """The clothes veto by blob: a pixel is vetoed where the clothes probability is at least ``clothes_min`` and the
    selfie model's own skin probability is below ``skin_max``; a skin blob (``skin`` >= ``on``, 8-connected) loses its
    vetoed pixels only when they are at least ``share`` of it, and the soft rim (below ``on``) within ``rim`` pixels
    of a blob that stays is kept — a patch of "skin" on trousers or a belt goes, an arm whose motion-blurred end the
    clothes model calls clothing stays whole. Returns a new map. Same as the Android ``ClothesSegmenter.vetoBlobs``."""
    cand = (clothes >= clothes_min) & (selfie < skin_max)
    n, lab = cv2.connectedComponents((skin >= on).astype(np.uint8), connectivity=8)
    total = np.bincount(lab.ravel(), minlength=n)
    vetoed = np.bincount(lab.ravel(), weights=cand.ravel().astype(np.float64), minlength=n)
    drop = (vetoed > 0) & (vetoed >= share * total)
    drop[0] = False
    kept = (lab > 0) & ~drop[lab]
    near = cv2.dilate(kept.astype(np.uint8), np.ones((2 * rim + 1, 2 * rim + 1), np.uint8)) > 0 if rim > 0 else kept
    out = skin.copy()
    out[cand & np.where(lab == 0, ~near, drop[lab])] = 0
    return out


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
