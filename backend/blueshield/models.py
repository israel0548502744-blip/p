"""Model management: locating / downloading the open-source models BlueShield uses.

Models
------
1. MediaPipe "Selfie Multiclass" image segmenter (Apache-2.0, Google).
   Per-pixel classes: background, hair, body-skin, face-skin, clothes, others.
   Downloaded once from Google's public model bucket into ``backend/models``.

2. NudeNet v3 detector "320n" (MIT, notAI-tech) — a YOLOv8 ONNX model that
   localises exposed sensitive body regions. It ships inside the ``nudenet``
   pip package (installed from requirements.txt); a copy is placed in
   ``backend/models`` on first run.
"""

from __future__ import annotations

import logging
import shutil
import threading
import urllib.request
from pathlib import Path

from .config import MODELS_DIR

log = logging.getLogger("blueshield.models")

SEGMENTER_FILE = MODELS_DIR / "selfie_multiclass_256x256.tflite"
SEGMENTER_URL = (
    "https://storage.googleapis.com/mediapipe-models/image_segmenter/"
    "selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite"
)
NUDENET_FILE = MODELS_DIR / "nudenet_320n.onnx"
NUDENET_URL = "https://github.com/notAI-tech/NudeNet/releases/download/v3.4-weights/320n.onnx"

_lock = threading.Lock()


def _download(url: str, dest: Path) -> None:
    log.info("Downloading %s -> %s", url, dest)
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "BlueShield/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 20)
    if tmp.stat().st_size < 100_000:
        tmp.unlink(missing_ok=True)
        raise RuntimeError(f"Download from {url} looks truncated")
    tmp.replace(dest)


def ensure_segmenter() -> Path:
    with _lock:
        if not SEGMENTER_FILE.exists():
            _download(SEGMENTER_URL, SEGMENTER_FILE)
    return SEGMENTER_FILE


def ensure_nudenet() -> Path:
    with _lock:
        if NUDENET_FILE.exists():
            return NUDENET_FILE
        try:
            import nudenet  # type: ignore

            bundled = Path(nudenet.__file__).parent / "320n.onnx"
            if bundled.exists():
                shutil.copyfile(bundled, NUDENET_FILE)
                return NUDENET_FILE
        except Exception:  # pragma: no cover - fall back to download
            pass
        _download(NUDENET_URL, NUDENET_FILE)
    return NUDENET_FILE


def models_status() -> dict:
    return {
        "segmenter": {"name": "MediaPipe Selfie Multiclass 256", "ready": SEGMENTER_FILE.exists(),
                      "source": SEGMENTER_URL, "license": "Apache-2.0"},
        "nudenet": {"name": "NudeNet v3 (320n)", "ready": NUDENET_FILE.exists(),
                    "source": "pip package 'nudenet' (fallback: " + NUDENET_URL + ")", "license": "MIT"},
    }


def ensure_all() -> None:
    ensure_segmenter()
    ensure_nudenet()


def onnx_providers() -> list[str]:
    import onnxruntime as ort

    preferred = ["TensorrtExecutionProvider", "CUDAExecutionProvider", "CoreMLExecutionProvider",
                 "DmlExecutionProvider", "ROCMExecutionProvider", "CPUExecutionProvider"]
    available = set(ort.get_available_providers())
    # TensorRT needs an engine build step that's slow on first run; skip unless CUDA is absent.
    chosen = [p for p in preferred if p in available and p != "TensorrtExecutionProvider"]
    return chosen or ["CPUExecutionProvider"]


def gpu_summary() -> dict:
    providers = onnx_providers()
    gpu = [p for p in providers if p != "CPUExecutionProvider"]
    return {"onnx_providers": providers, "gpu": bool(gpu), "gpu_name": gpu[0] if gpu else None}
