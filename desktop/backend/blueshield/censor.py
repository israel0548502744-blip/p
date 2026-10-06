"""The blue censor renderer: mask upscaling, feathering and blue compositing."""

from __future__ import annotations

import math

import cv2
import numpy as np


def hex_to_bgr(color: str) -> tuple[int, int, int]:
    c = color.strip().lstrip("#")
    if len(c) == 3:
        c = "".join(ch * 2 for ch in c)
    if len(c) != 6:
        raise ValueError(f"Invalid color: {color}")
    r, g, b = int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16)
    return b, g, r


class BlueCensor:
    """Composites a solid (or gently animated) blue fill over masked pixels.

    The mask is processed only inside the bounding box of the censored region,
    so frames with small or no detections are nearly free.
    """

    def __init__(self, width: int, height: int, color: str, softness: float, aggressive: bool,
                 animated: bool, fps: float) -> None:
        self.w, self.h = width, height
        self.bgr = np.array(hex_to_bgr(color), np.float32)
        diag = math.hypot(width, height)
        # softness 0..100 -> feather radius up to ~0.8% of the diagonal
        self.feather = max(0.0, softness / 100.0 * 0.008 * diag)
        # grow masks slightly so feathering never exposes the original edge (the skin edges are already fitted to
        # the frame by colour during analysis, so the margin is small)
        grow = self.feather * 0.5 + (0.004 * diag if aggressive else 0.001 * diag)
        self.grow = int(round(grow))
        self.margin = int(self.grow + 3 * self.feather + 4)
        self.animated = animated
        self.fps = max(fps, 1.0)
        if animated:
            yy, xx = np.mgrid[0:height, 0:width].astype(np.float32)
            self._phase = (xx * 0.9 + yy * 0.6) * (2 * math.pi / max(240.0, diag * 0.25))
        self._kernel = (cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * self.grow + 1, 2 * self.grow + 1))
                        if self.grow > 0 else None)

    def apply(self, frame: np.ndarray, mask_small: np.ndarray, frame_index: int) -> tuple[np.ndarray, float]:
        """Return (censored frame, censored area fraction)."""
        if not mask_small.any():
            return frame, 0.0
        mh, mw = mask_small.shape
        sx, sy = self.w / mw, self.h / mh
        x, y, bw, bh = cv2.boundingRect((mask_small > 0).astype(np.uint8))
        x1 = max(0, int(x * sx) - self.margin)
        y1 = max(0, int(y * sy) - self.margin)
        x2 = min(self.w, int((x + bw) * sx) + self.margin)
        y2 = min(self.h, int((y + bh) * sy) + self.margin)

        # Upscale only the ROI of the low-res mask (with sub-pixel accurate mapping).
        mx1, my1 = x1 / sx, y1 / sy
        M = np.float32([[1 / sx, 0, mx1], [0, 1 / sy, my1]])
        alpha = cv2.warpAffine(mask_small, M, (x2 - x1, y2 - y1),
                               flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP, borderMode=cv2.BORDER_CONSTANT)
        alpha = (alpha > 96).astype(np.uint8) * 255
        if self._kernel is not None:
            alpha = cv2.dilate(alpha, self._kernel)
        alpha = alpha.astype(np.float32) * (1.0 / 255.0)
        if self.feather >= 0.5:
            alpha = cv2.GaussianBlur(alpha, (0, 0), self.feather)
        coverage = float(alpha.sum()) / (self.w * self.h)

        out = frame.copy() if not frame.flags.writeable else frame
        roi = out[y1:y2, x1:x2].astype(np.float32)
        if self.animated:
            t = frame_index / self.fps
            wave = np.sin(self._phase[y1:y2, x1:x2] + t * 2.4)
            shade = (1.0 + 0.12 * wave + 0.04 * math.sin(t * 3.1))[..., None]
            fill = np.clip(self.bgr[None, None, :] * shade + 18 * (wave[..., None] > 0.85), 0, 255)
        else:
            fill = self.bgr[None, None, :]
        alpha *= 1.0 - text_mask(frame[y1:y2, x1:x2])  # on-screen text always stays visible
        a = alpha[..., None]
        roi += (fill - roi) * a
        out[y1:y2, x1:x2] = np.clip(roi, 0, 255).astype(np.uint8)
        return out, coverage


TEXT_R = 2
TEXT_BRIGHT = 0.80
TEXT_DARK = 0.25
TEXT_MAX_SAT = 0.25


def text_mask(bgr: np.ndarray) -> np.ndarray:
    """1.0 on burned-in captions / on-screen text: a very bright, unsaturated pixel with a very dark one
    ``TEXT_R`` px away (white text with a dark outline), or that dark outline itself. Same test as the Android
    ``TextGuard`` and GPU shader."""
    f = bgr.astype(np.float32) / 255.0
    lum = f[..., 2] * 0.299 + f[..., 1] * 0.587 + f[..., 0] * 0.114
    sat = f.max(-1) - f.min(-1)
    bright = (lum > TEXT_BRIGHT) & (sat < TEXT_MAX_SAT)
    dark = lum < TEXT_DARK
    h, w = lum.shape
    near_dark = np.zeros_like(bright)
    near_bright = np.zeros_like(bright)
    pad_d = np.pad(dark, TEXT_R, mode="edge")
    pad_b = np.pad(bright, TEXT_R, mode="edge")
    for dy, dx in ((0, TEXT_R), (0, -TEXT_R), (TEXT_R, 0), (-TEXT_R, 0),
                   (TEXT_R, TEXT_R), (-TEXT_R, TEXT_R), (TEXT_R, -TEXT_R), (-TEXT_R, -TEXT_R)):
        sl = (slice(TEXT_R + dy, TEXT_R + dy + h), slice(TEXT_R + dx, TEXT_R + dx + w))
        near_dark |= pad_d[sl]
        near_bright |= pad_b[sl]
    return ((bright & near_dark) | (dark & near_bright)).astype(np.float32)
