"""Stage 2 — gender classification (per tracked person, with explicit uncertainty).

Pipeline for one person on a classification keyframe::

    person box ─► upper-body crop ─► face detection (BlazeFace) ─► eye-aligned face crop
               ─► gender model (FaceRes MobileNet) ─► P(male) ─► weighted log-odds vote

Why it's built this way
-----------------------
* Gender is inferred from the **face** only. Body-based cues (e.g. NudeNet's
  gendered labels) proved unreliable in testing and are *not* used here — that
  detector is only used for sensitive-region detection.
* A single frame is never trusted. Each frame's prediction becomes a vote
  (weighted by face size and detector confidence) that accumulates on the
  person's track for the whole time they are on screen.
* The decision uses a user-set **confidence threshold**:
  ``P(female) >= t`` → female, ``P(female) <= 1-t`` → male, otherwise — or
  with too little evidence (fewer than ``MIN_VOTES`` usable face observations or
  less than ``MIN_WEIGHT`` total quality: back turned, faces small or blurry) —
  **uncertain**, which follows a user-chosen fallback (default: censor).
* Users can override any person manually and re-render without re-analysis.

The model predicts *apparent* gender presentation from a face; it is not
identity, and it can be wrong (see README "Limitations").
"""

from __future__ import annotations

import math
import os
from dataclasses import dataclass, field
from typing import Optional

import cv2
import numpy as np

from . import models

MAX_LOGIT = 6.0
MIN_VOTES = 6  # face observations needed before calling anyone female or male
MIN_WEIGHT = 3.0  # …and their summed quality weight (small / low-confidence faces count less)
MALE_MIN_CONFIDENCE = 0.8  # calling someone male needs at least this, whatever the threshold (missing a woman costs more)
# A child (not censored when only women are) only when the age estimate clearly says "small child": the face
# model puts young adult women anywhere from ~10 to ~16, so a higher cut-off would leave real women uncensored.
# Older girls are therefore treated as adults (the safe side) — one tap on "Don't" in the people list keeps them.
CHILD_MAX_AGE = 7.0
MIN_AGE_VOTES = 6  # age observations needed before anyone can be called a child; until then: adult
FACE_INPUT = 128
GENDER_INPUT = 224
AGE_INPUT = 112
AGE_TIGHT = 0.85


@dataclass
class GenderEstimate:
    logit: float = 0.0  # log-odds of "female"
    votes: int = 0
    weight: float = 0.0
    ages: list = field(default_factory=list)

    def add_age(self, age: float) -> None:
        if age is not None and math.isfinite(age):
            self.ages.append(float(age))

    @property
    def age_median(self) -> Optional[float]:
        return float(np.median(self.ages)) if self.ages else None

    @property
    def is_child(self) -> bool:
        """Clearly a child: enough age observations with a median below the adult age (unknown age = adult)."""
        return len(self.ages) >= MIN_AGE_VOTES and self.age_median < CHILD_MAX_AGE

    def add(self, p_male: float, weight: float = 1.0) -> None:
        p = float(np.clip(p_male, 0.02, 0.98))
        frame_logit = math.log((1 - p) / p)  # towards female
        # consecutive frames are correlated, so every vote counts only partially
        self.logit = float(np.clip(self.logit + 0.35 * weight * frame_logit, -MAX_LOGIT, MAX_LOGIT))
        self.votes += 1
        self.weight += weight

    def absorb(self, other: "GenderEstimate") -> None:
        """Merge the evidence of a duplicate track of the same person."""
        self.logit = float(np.clip(self.logit + other.logit, -MAX_LOGIT, MAX_LOGIT))
        self.votes += other.votes
        self.weight += other.weight
        self.ages.extend(other.ages)

    @property
    def p_female(self) -> float:
        return 1.0 / (1.0 + math.exp(-self.logit))

    def label(self, threshold: float) -> str:
        """'female' | 'male' | 'uncertain' for a confidence threshold in (0.5, 1)."""
        if self.votes < MIN_VOTES or self.weight < MIN_WEIGHT:
            return "uncertain"
        p = self.p_female
        if p >= threshold:
            return "female"
        if p <= 1.0 - max(threshold, MALE_MIN_CONFIDENCE):
            return "male"
        return "uncertain"

    def confidence(self) -> float:
        return max(self.p_female, 1.0 - self.p_female)


def censor_decision(label: str, target: str, uncertain_policy: str, override: str = "auto",
                    child: bool = False) -> bool:
    """Should this person be censored?

    target: 'female' (adult women only — girls are not censored) | 'everyone'
    uncertain_policy: 'censor' (safe default) | 'keep'
    override: 'auto' | 'censor' | 'keep'
    """
    if override == "censor":
        return True
    if override == "keep":
        return False
    if target == "everyone":
        return True
    if child:
        return False
    if label == "female":
        return True
    if label == "male":
        return False
    return uncertain_policy == "censor"


def _interpreter(path: str):
    try:
        from ai_edge_litert.interpreter import Interpreter  # type: ignore
    except ImportError:  # e.g. Intel macOS, where LiteRT has no wheel
        from tensorflow.lite import Interpreter  # type: ignore

    it = Interpreter(model_path=path, num_threads=max(1, os.cpu_count() or 1))
    it.allocate_tensors()
    return it


@dataclass
class Face:
    box: tuple[float, float, float, float]  # x1, y1, x2, y2 in crop pixels
    score: float
    right_eye: tuple[float, float]
    left_eye: tuple[float, float]


class FaceDetector:
    """MediaPipe BlazeFace (short range) with SSD anchor decoding."""

    def __init__(self) -> None:
        self._it = _interpreter(str(models.ensure_face_detector()))
        self._in = self._it.get_input_details()[0]["index"]
        outs = {int(o["shape"][-1]): o["index"] for o in self._it.get_output_details()}
        self._reg, self._cls = outs[16], outs[1]
        anchors = []
        for stride, per_cell in ((8, 2), (16, 6)):
            g = FACE_INPUT // stride
            for y in range(g):
                for x in range(g):
                    anchors += [((x + 0.5) / g, (y + 0.5) / g)] * per_cell
        self._anchors = np.array(anchors, np.float32)

    def detect(self, bgr: np.ndarray, min_score: float = 0.6) -> list[Face]:
        h, w = bgr.shape[:2]
        x = cv2.resize(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB), (FACE_INPUT, FACE_INPUT)).astype(np.float32)
        self._it.set_tensor(self._in, (x / 127.5 - 1.0)[None])
        self._it.invoke()
        reg = self._it.get_tensor(self._reg)[0]
        logits = np.clip(self._it.get_tensor(self._cls)[0][:, 0], -80, 80)
        scores = 1 / (1 + np.exp(-logits))
        keep = np.where(scores >= min_score)[0]
        if keep.size == 0:
            return []
        faces, rects = [], []
        for i in keep:
            r, (ax, ay) = reg[i] / FACE_INPUT, self._anchors[i]
            cx, cy, bw, bh = r[0] + ax, r[1] + ay, r[2], r[3]
            box = ((cx - bw / 2) * w, (cy - bh / 2) * h, (cx + bw / 2) * w, (cy + bh / 2) * h)
            reye = ((r[4] + ax) * w, (r[5] + ay) * h)
            leye = ((r[6] + ax) * w, (r[7] + ay) * h)
            faces.append(Face(box, float(scores[i]), reye, leye))
            rects.append([box[0], box[1], box[2] - box[0], box[3] - box[1]])
        idx = np.array(cv2.dnn.NMSBoxes(rects, [f.score for f in faces], min_score, 0.3)).reshape(-1)
        return [faces[i] for i in idx]


class GenderClassifier:
    """FaceRes MobileNet gender head (outputs P(male)) on eye-aligned face crops."""

    def __init__(self) -> None:
        self.faces = FaceDetector()
        self._it = _interpreter(str(models.ensure_gender_model()))
        self._in = self._it.get_input_details()[0]["index"]
        self._out = self._it.get_output_details()[0]["index"]
        # second face model: face-api.js AgeGenderNet (P(male) + age; trained on UTKFace, which includes children)
        self._ag = _interpreter(str(models.ensure_age_gender_model()))
        self._ag_in = self._ag.get_input_details()[0]["index"]
        self._ag_out = [d["index"] for d in self._ag.get_output_details()]

    def aligned_face(self, img: np.ndarray, face: Face) -> np.ndarray:
        (rx, ry), (lx, ly) = face.right_eye, face.left_eye
        angle = math.degrees(math.atan2(ly - ry, lx - rx))
        if abs(angle) > 90:  # eyes reported mirrored
            angle -= 180 * math.copysign(1, angle)
        x1, y1, x2, y2 = face.box
        cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
        side = max(x2 - x1, y2 - y1) * 1.4
        scale = GENDER_INPUT / side
        M = cv2.getRotationMatrix2D((cx, cy), angle, scale)
        M[0, 2] += GENDER_INPUT / 2 - cx
        M[1, 2] += GENDER_INPUT / 2 - cy
        return cv2.warpAffine(img, M, (GENDER_INPUT, GENDER_INPUT), flags=cv2.INTER_LINEAR,
                              borderMode=cv2.BORDER_REPLICATE)

    def p_male(self, face_bgr: np.ndarray) -> float:
        x = cv2.cvtColor(face_bgr, cv2.COLOR_BGR2RGB).astype(np.float32)  # model expects 0..255 RGB
        self._it.set_tensor(self._in, x[None])
        self._it.invoke()
        return float(self._it.get_tensor(self._out).ravel()[0])

    def age_gender(self, face_bgr: np.ndarray) -> tuple[float, float]:
        """(P(male), age) from the second model, on the same aligned crop (cut to the tighter face it was trained on)."""
        s = face_bgr.shape[0]
        m = int(round(s * (1 - AGE_TIGHT) / 2))
        x = cv2.resize(cv2.cvtColor(face_bgr[m:s - m, m:s - m], cv2.COLOR_BGR2RGB), (AGE_INPUT, AGE_INPUT),
                       interpolation=cv2.INTER_AREA).astype(np.float32)
        self._ag.set_tensor(self._ag_in, x[None])
        self._ag.invoke()
        outs = [self._ag.get_tensor(i).ravel() for i in self._ag_out]
        age = next(o for o in outs if o.size == 1)[0]
        gender = next(o for o in outs if o.size == 2)
        return float(gender[0]), float(age)

    def find_face(self, frame: np.ndarray, box: tuple[float, float, float, float],
                  face_map: Optional[np.ndarray] = None, others=(), min_px: float = 0.0
                  ) -> Optional[tuple[np.ndarray, Face, float, tuple[float, float]]]:
        """Locate a person's face: (crop, face-in-crop, frame px per crop px, crop origin in frame) or None.

        ``face_map`` (facial-skin probability from the segmentation model, frame-sized)
        lets us find the head even when the person box is wide (outstretched arms)
        or the face is in profile. A face narrower than ``min_px`` frame pixels doesn't count: the next crop is
        tried (same as the Android ``GenderClassifier.locate``).
        """
        crops = []
        head = head_crop(frame, box, face_map, others=others) if face_map is not None else None
        if head is not None:
            crops.append(head)
        top = top_crop(frame, box)
        if top is not None:
            crops.append(top)
        ub = upper_body_crop(frame, box, 256)
        if ub is not None:
            a, b, _, _ = _ub_rect(frame.shape, box)
            crops.append((ub, crop_scale(frame, box, 256), (float(a), float(b))))
        for n, (crop, scale, origin) in enumerate(crops):
            def in_frame(b, scale=scale, origin=origin):
                return (origin[0] + b[0] * scale, origin[1] + b[1] * scale, origin[0] + b[2] * scale, origin[1] + b[3] * scale)
            # a face that sits where another person's head is belongs to them, not to this person
            faces = [f for f in self.faces.detect(crop, 0.5)
                     if (f.box[1] + f.box[3]) / 2 < 0.75 * crop.shape[0] and owns_face(in_frame(f.box), box, others)]
            face = pick_face(faces, crop.shape[1], centred=n == 0 and head is not None)
            if face is not None and (face.box[2] - face.box[0]) * scale >= min_px:
                return crop, face, scale, origin
        return None

    def face_box(self, frame: np.ndarray, box: tuple[float, float, float, float],
                 face_map: Optional[np.ndarray] = None, others=(), min_px: float = 6.0) -> Optional[tuple[float, float, float, float]]:
        """This person's face (frame pixels) without classifying it: faces the sensitive-region detector misses
        (small, turned, in the dark, in a crowd) must still stay uncovered, and need not be big enough to classify.
        Same as the Android ``GenderClassifier.face``."""
        found = self.find_face(frame, box, face_map, others, min_px)
        if found is None:
            return None
        _, face, scale, (ox, oy) = found
        return (ox + face.box[0] * scale, oy + face.box[1] * scale, ox + face.box[2] * scale, oy + face.box[3] * scale)

    def classify_person(self, frame: np.ndarray, box: tuple[float, float, float, float],
                        face_map: Optional[np.ndarray] = None, found=None, others=()) -> Optional[tuple[float, float, float, tuple]]:
        """Return (P(male), vote weight, age, face box in the frame) for the person in ``box``, or None if no usable face.

        P(male) averages the log-odds of both face models: their mistakes are largely independent (e.g. older
        women, whom FaceRes alone often calls male), so the ensemble is much steadier than either.
        """
        found = found if found is not None else self.find_face(frame, box, face_map, others, min_px=14)
        if found is None:
            return None
        crop, face, scale, (ox, oy) = found
        face_px = (face.box[2] - face.box[0]) * scale
        if face_px < 14:
            return None  # too small to classify meaningfully
        weight = face.score * min(1.0, face_px / 48.0)
        aligned = self.aligned_face(crop, face)
        p2, age = self.age_gender(aligned)
        fb = tuple(float(v) for v in (ox + face.box[0] * scale, oy + face.box[1] * scale, ox + face.box[2] * scale, oy + face.box[3] * scale))
        return ensemble(self.p_male(aligned), p2), weight, age, fb


def head_score(face, box) -> float:
    """How much ``face`` looks like the head of ``box``: 0 at the top centre, growing downwards and sideways."""
    fcx, fcy = (face[0] + face[2]) / 2, (face[1] + face[3]) / 2
    bw, bh = max(1.0, box[2] - box[0]), max(1.0, box[3] - box[1])
    return (fcy - box[1]) / bh + 0.5 * abs(fcx - (box[0] + box[2]) / 2) / bw


def owns_face(face, own, others) -> bool:
    """False when the face centre lies in another person's box that it fits clearly better as a head
    (a child in front of her mother must not make the mother a child)."""
    mine = head_score(face, own)
    fcx, fcy = (face[0] + face[2]) / 2, (face[1] + face[3]) / 2
    return not any(o[0] <= fcx <= o[2] and o[1] <= fcy <= o[3] and head_score(face, o) + 0.08 < mine for o in others)


def ensemble(a: float, b: float) -> float:
    """Mean of two P(male) log-odds, back to a probability."""
    def logit(p: float) -> float:
        p = min(max(p, 0.02), 0.98)
        return math.log(p / (1 - p))
    return 1.0 / (1.0 + math.exp(-(logit(a) + logit(b)) / 2))


def top_crop(frame: np.ndarray, box, size: int = 256):
    """Head-sized square at the top centre of a full-body box (faces of distant, standing people)."""
    x1, y1, x2, y2 = box
    bw, bh = x2 - x1, y2 - y1
    if bh < 2.2 * bw * 0.6 or bh < 40:  # only for tall (full-body) boxes
        return None
    side = min(bw, 0.36 * bh)
    cx, cy = (x1 + x2) / 2, y1 + 0.42 * side
    a, b = int(round(cx - side / 2)), int(round(cy - side / 2))
    h, w = frame.shape[:2]
    s_ = int(round(side))
    pad = [max(0, -b), max(0, b + s_ - h), max(0, -a), max(0, a + s_ - w)]
    crop = frame[max(0, b):min(h, b + s_), max(0, a):min(w, a + s_)]
    if crop.size == 0 or s_ < 12:
        return None
    crop = cv2.copyMakeBorder(crop, *pad, cv2.BORDER_REPLICATE)
    return cv2.resize(crop, (size, size), interpolation=cv2.INTER_LINEAR), side / size, (float(a), float(b))


def pick_face(faces, side: float, centred: bool):
    """The face that belongs to the person: among the clearly-sized faces the topmost (a person's own head is
    at the top of their box, a child in front must not lend an adult its face); in a head crop, the one
    nearest the centre."""
    if not faces:
        return None
    biggest = max(f.box[2] - f.box[0] for f in faces)
    sized = [f for f in faces if f.box[2] - f.box[0] >= 0.6 * biggest]
    if centred:
        return min(sized, key=lambda f: abs((f.box[0] + f.box[2]) / 2 - side / 2) + abs((f.box[1] + f.box[3]) / 2 - side / 2))
    return min(sized, key=lambda f: f.box[1])


def head_crop(frame: np.ndarray, box, face_map: np.ndarray, size: int = 256, others=()):
    """Square crop centred on this person's facial-skin blob in the top part of the person box."""
    h, w = frame.shape[:2]
    x1, y1, x2, y2 = (int(round(v)) for v in box)
    x1, y1 = max(0, x1), max(0, y1)
    x2, y2 = min(w, x2), min(h, y1 + max(8, int((y2 - y1) * 0.6)))
    if x2 - x1 < 8 or y2 - y1 < 8:
        return None
    region = (face_map[y1:y2, x1:x2] > 0.4).astype(np.uint8)
    n, _, stats, _ = cv2.connectedComponentsWithStats(region)
    if n <= 1:
        return None
    # Of the sizeable facial-skin blobs, the one that sits best where this person's head should be and is not
    # a better fit for someone else's head (a neighbour's face inside a loose box, a child in front).
    areas = stats[1:, cv2.CC_STAT_AREA]
    if areas.max() < 30:
        return None

    def blob_box(i):
        bx, by, bw, bh = stats[i, :4]
        return (float(x1 + bx), float(y1 + by), float(x1 + bx + bw), float(y1 + by + bh))
    big = [i for i in range(1, n) if stats[i, cv2.CC_STAT_AREA] >= max(30, 0.2 * areas.max()) and owns_face(blob_box(i), box, others)]
    if not big:
        return None
    k = min(big, key=lambda i: head_score(blob_box(i), box))
    fx, fy, fw, fh, area = stats[k]
    cx, cy = x1 + fx + fw / 2, y1 + fy + fh / 2
    side = max(fw, fh) * 2.4
    a, b = int(round(cx - side / 2)), int(round(cy - side / 2))
    c, d = a + int(round(side)), b + int(round(side))
    pad = [max(0, -b), max(0, d - h), max(0, -a), max(0, c - w)]
    crop = frame[max(0, b):min(h, d), max(0, a):min(w, c)]
    if crop.size == 0:
        return None
    crop = cv2.copyMakeBorder(crop, *pad, cv2.BORDER_REPLICATE)
    return cv2.resize(crop, (size, size), interpolation=cv2.INTER_LINEAR), side / size, (float(a), float(b))


def _ub_rect(frame_shape, box):
    h, w = frame_shape[:2]
    x1, y1, x2, y2 = box
    bw, bh = x2 - x1, y2 - y1
    side = max(bw * 1.1, min(bh, bw * 1.6) * 0.75)
    cx = (x1 + x2) / 2
    top = y1 - 0.05 * bh
    return (int(max(0, cx - side / 2)), int(max(0, top)), int(min(w, cx + side / 2)), int(min(h, top + side)))


def crop_scale(frame: np.ndarray, box, size: int) -> float:
    """Original-pixels-per-crop-pixel factor of ``upper_body_crop``."""
    a, b, c, d = _ub_rect(frame.shape, box)
    return max(c - a, d - b) / size


def upper_body_crop(frame: np.ndarray, box: tuple[float, float, float, float], size: int = 256) -> Optional[np.ndarray]:
    """Square crop of a person's head/torso region, resized for the face detector."""
    x1, y1, x2, y2 = box
    if x2 - x1 < 12 or y2 - y1 < 24:
        return None
    a, b, c, d = _ub_rect(frame.shape, box)
    if c - a < 12 or d - b < 12:
        return None
    crop = frame[b:d, a:c]
    # pad to square so faces keep their aspect ratio
    side = max(crop.shape[0], crop.shape[1])
    crop = cv2.copyMakeBorder(crop, 0, side - crop.shape[0], 0, side - crop.shape[1], cv2.BORDER_REPLICATE)
    return cv2.resize(crop, (size, size), interpolation=cv2.INTER_LINEAR if side < size else cv2.INTER_AREA)
