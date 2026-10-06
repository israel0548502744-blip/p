"""Filesystem locations and global tunables."""

from __future__ import annotations

import os
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent
ROOT_DIR = BACKEND_DIR.parent

DATA_DIR = Path(os.environ.get("BLUESHIELD_DATA_DIR", BACKEND_DIR / "data"))
UPLOAD_DIR = DATA_DIR / "uploads"
OUTPUT_DIR = DATA_DIR / "outputs"
WORK_DIR = DATA_DIR / "work"
MODELS_DIR = Path(os.environ.get("BLUESHIELD_MODELS_DIR", BACKEND_DIR / "models"))
FRONTEND_DIST = ROOT_DIR / "frontend" / "dist"

for _d in (UPLOAD_DIR, OUTPUT_DIR, WORK_DIR, MODELS_DIR):
    _d.mkdir(parents=True, exist_ok=True)

# Analysis frames are downscaled so their long side is at most this many pixels.
ANALYSIS_MAX_SIDE = int(os.environ.get("BLUESHIELD_ANALYSIS_SIDE", 768))
# Masks are stored on disk at this (long side) resolution between the two passes.
MASK_MAX_SIDE = int(os.environ.get("BLUESHIELD_MASK_SIDE", 768))

ALLOWED_EXTENSIONS = {
    ".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi", ".wmv", ".flv", ".mpg",
    ".mpeg", ".3gp", ".ts", ".mts", ".m2ts", ".ogv",
}
