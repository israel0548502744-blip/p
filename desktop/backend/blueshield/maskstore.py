"""Compact on-disk storage for per-frame masks between the analysis and render passes.

Masks are tiny and mostly empty, so each frame is zlib-compressed; only an
offset index lives in RAM. This keeps memory flat even for hour-long videos.
"""

from __future__ import annotations

import zlib
from pathlib import Path

import numpy as np


class MaskStore:
    def __init__(self, path: Path, width: int, height: int) -> None:
        self.path = Path(path)
        self.width, self.height = width, height
        self.index: list[tuple[int, int]] = []
        self._fh = open(self.path, "w+b")
        self._empty = np.zeros((height, width), np.uint8)

    def append(self, mask: np.ndarray) -> None:
        if not mask.any():
            self.index.append((0, 0))
            return
        data = zlib.compress(np.ascontiguousarray(mask, np.uint8).tobytes(), 1)
        off = self._fh.seek(0, 2)
        self._fh.write(data)
        self.index.append((off, len(data)))

    def __len__(self) -> int:
        return len(self.index)

    def get(self, i: int) -> np.ndarray:
        if i < 0 or i >= len(self.index):
            return self._empty
        off, n = self.index[i]
        if n == 0:
            return self._empty
        self._fh.seek(off)
        raw = zlib.decompress(self._fh.read(n))
        return np.frombuffer(raw, np.uint8).reshape(self.height, self.width)

    def close(self, delete: bool = True) -> None:
        self._fh.close()
        if delete:
            self.path.unlink(missing_ok=True)
