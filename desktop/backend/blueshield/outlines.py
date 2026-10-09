"""Per-person outlines and colour-fitted skin edges (same algorithms as the Android core).

* ``PersonMasks``: MobileSAM (Apache-2.0) prompted with each person's box. Where people overlap (a man behind
  a woman, a child in front of her mother) the outlines say whose arm a skin pixel is — boxes can't — and
  their silhouette keeps the censor off the background. See ``android/.../ml/PersonMasks.kt``.
* ``recolour`` / ``guide``: pixels near the coarse skin boundary decided by a colour model of the frame's own
  skin vs. its surroundings, then a guided filter whose guide includes a skin-tone channel. See ``EdgeSnap.kt``.
"""
from __future__ import annotations

import cv2
import numpy as np

from . import models

SAM_DIR = models.ROOT_DIR.parent / "models" / "onnx"
SAM_MEAN = (123.675, 116.28, 103.53)


class PersonMasks:
    def __init__(self, size: int):
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.log_severity_level = 3
        enc = SAM_DIR / f"mobilesam_encoder_{1024 if size >= 1024 else 512}.onnx"
        self.size = 1024 if size >= 1024 else 512
        # never in 16-bit floats (CoreML, as the Android app's fp16 engines): the encoder's neck squares values up to
        # ~3.5e5, past the fp16 maximum, and the outline vanishes (EngineCheck.FP32_ONLY on Android)
        enc_providers = [p for p in models.onnx_providers() if p != "CoreMLExecutionProvider"] or ["CPUExecutionProvider"]
        self.enc = ort.InferenceSession(str(enc), sess_options=so, providers=enc_providers)
        self.dec = ort.InferenceSession(str(SAM_DIR / "mobilesam_decoder.onnx"), sess_options=so, providers=models.onnx_providers())

    def masks(self, bgr: np.ndarray, boxes: list) -> list[np.ndarray]:
        """Mask logits (frame size, > 0 = person) for each box (frame coordinates), in the same order."""
        if not boxes:
            return []
        h, w = bgr.shape[:2]
        s = self.size / max(h, w)
        nw, nh = max(1, round(w * s)), max(1, round(h * s))
        x = np.empty((self.size, self.size, 3), np.float32)
        x[:] = SAM_MEAN  # letterboxed top-left; padding = SAM's pixel mean
        x[:nh, :nw] = cv2.resize(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB), (nw, nh), interpolation=cv2.INTER_AREA)
        emb, pe = self.enc.run(None, {"image": x.transpose(2, 0, 1)[None]})
        b = np.clip(np.asarray(boxes, np.float32).reshape(-1, 4) * s / self.size, 0, 1).astype(np.float32)
        logits, _ = self.dec.run(None, {"embeddings": emb, "image_pe": pe, "boxes": b})
        out = []
        for m in logits[:, 0]:
            full = cv2.resize(m, (self.size, self.size), interpolation=cv2.INTER_LINEAR)[:nh, :nw]
            out.append(cv2.resize(full, (w, h), interpolation=cv2.INTER_LINEAR))
        return out


def owners(logits: list, min_logit: float, clip_logit: float) -> np.ndarray:
    """Per-pixel owner: index into ``logits`` + 1; 0 = nobody clearly; -1 = clearly outside every outline.

    ``logits`` may contain None (a person without an outline yet)."""
    known = [(k, m) for k, m in enumerate(logits) if m is not None]
    if not known:
        return None
    stack = np.stack([m for _, m in known])
    idx = np.array([k for k, _ in known])
    best = stack.argmax(0)
    best_v = np.take_along_axis(stack, best[None], 0)[0]
    out = np.where(best_v > min_logit, idx[best] + 1, 0).astype(np.int32)
    if len(known) == len(logits):
        out[best_v < clip_logit] = -1
    return out


REMOVE_BELOW = 0.25
ADD_ABOVE = 0.8
MAJORITY = 0.75
KEEP_MARGIN = 3.0


def follow_arms(own: np.ndarray, skin: np.ndarray, logits: list) -> np.ndarray:
    """A hand belongs to the arm it's attached to (see PersonMasks.followArms on Android): within each connected
    skin region, when one person owns >= MAJORITY of the labelled pixels, the rest goes to them too - except
    pixels another outline claims by more than KEEP_MARGIN logits."""
    count, comp = cv2.connectedComponents(skin.astype(np.uint8), connectivity=8)
    k = len(logits) + 1
    lab = skin & (own > 0)
    votes = np.bincount(comp[lab] * k + own[lab], minlength=count * k).reshape(count, k)
    total = votes[:, 1:].sum(1)
    best = votes[:, 1:].argmax(1) + 1
    major = np.where((total > 0) & (votes[np.arange(count), best] >= MAJORITY * total), best, 0)
    major[0] = 0
    m = major[comp]
    cand = skin & (m > 0) & (own != m)
    if not cand.any():
        return own
    stack = np.stack([l if l is not None else np.full(skin.shape, -np.inf, np.float32) for l in logits])
    mine = np.take_along_axis(stack, np.clip(m - 1, 0, None)[None], 0)[0]
    theirs = np.where(own > 0, np.take_along_axis(stack, np.clip(own - 1, 0, None)[None], 0)[0], -np.inf)
    flip = cand & np.isfinite(mine) & (theirs <= mine + KEEP_MARGIN)
    own = own.copy()
    own[flip] = m[flip]
    return own


def guide(rgb: np.ndarray) -> np.ndarray:
    """Brightness plus a skin-tone channel (red minus green), so skin against a white dress has an edge."""
    f = rgb.astype(np.float32) / 255.0
    r, g, b = f[..., 0], f[..., 1], f[..., 2]
    return 0.299 * r + 0.587 * g + 0.114 * b + 1.5 * np.maximum(0.0, r - g)


def _blur3(hist: np.ndarray) -> np.ndarray:
    a = hist.reshape(16, 16, 16)
    for axis in range(3):
        a = np.moveaxis(a, axis, 0)
        o = 2 * a
        o[1:] += a[:-1]
        o[:-1] += a[1:]
        a = np.moveaxis(o, 0, axis)
    return a.reshape(-1)


def recolour(p: np.ndarray, rgb: np.ndarray, band: int, keep_above: float = 2.0) -> np.ndarray:
    """Decide the pixels within ``band`` of the coarse boundary of ``p`` by colour (sure skin deep inside vs.
    the sure surroundings just outside). Pixels the model is sure of (``p`` >= ``keep_above``) are never removed:
    one colour model of the whole frame calls the shaded side of an arm, or skin under coloured light,
    background. Returns a new probability map."""
    inside = (p > 0.5).astype(np.uint8)
    if not inside.any():
        return p
    k = lambda r: np.ones((2 * r + 1, 2 * r + 1), np.uint8)  # noqa: E731 - square, like MaskOps.dilate
    core = cv2.erode(inside, k(band)) > 0
    if core.sum() < 50:  # thin limbs (crowds, far people): the whole mask is the skin sample
        core = inside > 0
    outer = cv2.dilate(inside, k(band)) > 0
    ring = cv2.dilate(inside, k(2 * band)) > 0
    q = (rgb >> 4).astype(np.int32)
    bins = (q[..., 0] * 16 + q[..., 1]) * 16 + q[..., 2]
    skin = np.bincount(bins[core], minlength=4096).astype(np.float32)
    other = np.bincount(bins[ring & ~outer], minlength=4096).astype(np.float32)
    if skin.sum() < 50 or other.sum() < 50:
        return p
    sk, ot = _blur3(skin), _blur3(other)
    sk /= sk.sum()
    ot /= ot.sum()
    sel = outer & ~core
    a, o = sk[bins[sel]], ot[bins[sel]]
    like = (a + 1e-6) / (a + o + 2e-6)
    # only clear colour evidence moves the boundary (see EdgeSnap.recolour): remove inside pixels whose colour is
    # clearly not this skin, add outside pixels whose colour clearly is; ambiguous colours keep the model's opinion
    in_mask = inside[sel] > 0
    vals = p[sel]
    move = (in_mask & (like < REMOVE_BELOW) & (vals < keep_above)) | (~in_mask & (like > ADD_ABOVE))
    out = p.copy()
    vals[move] = 0.75 * like[move] + 0.25 * vals[move]
    out[sel] = vals
    return out
