"""Model management: locating / downloading the open-source models BlueShield uses.

Models
------
1. MediaPipe "Selfie Multiclass" image segmenter (Apache-2.0, Google).
   Per-pixel classes: background, hair, body-skin, face-skin, clothes, others.
   Downloaded once from Google's public model bucket into ``backend/models``.

2. MediaPipe "EfficientDet-Lite0" object detector (Apache-2.0, Google), COCO
   classes — only the ``person`` class is used, for person-level detection/tracking.
   Downloaded once from Google's public model bucket into ``backend/models``.

3. MediaPipe "BlazeFace short range" face detector (Apache-2.0, Google) — finds faces
   inside each person's upper-body crop. Downloaded from Google's model bucket.

4. "FaceRes" gender/age MobileNet by A. Savchenko (HSE_FaceRec_tf), as packaged in
   the MIT-licensed ``@vladmandic/human-models`` npm package and converted to TFLite
   (float16) with ``shared/tools/convert_tfjs_to_tflite.py``. The converted file is
   committed at ``models/gender_faceres_fp16.tflite`` and copied into place.

5. NudeNet v3 detector "320n" (MIT, notAI-tech) — a YOLOv8 ONNX model that
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

from .config import MODELS_DIR, ROOT_DIR

log = logging.getLogger("blueshield.models")

SEGMENTER_FILE = MODELS_DIR / "selfie_multiclass_256x256.tflite"
SEGMENTER_URL = (
    "https://storage.googleapis.com/mediapipe-models/image_segmenter/"
    "selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite"
)
PERSON_FILE = MODELS_DIR / "efficientdet_lite0_float32.tflite"
PERSON_URL = (
    "https://storage.googleapis.com/mediapipe-models/object_detector/"
    "efficientdet_lite0/float32/latest/efficientdet_lite0.tflite"
)
FACE_FILE = MODELS_DIR / "blaze_face_short_range.tflite"
FACE_URL = (
    "https://storage.googleapis.com/mediapipe-models/face_detector/"
    "blaze_face_short_range/float16/latest/blaze_face_short_range.tflite"
)
GENDER_FILE = MODELS_DIR / "gender_faceres_fp16.tflite"
GENDER_BUNDLED = ROOT_DIR.parent / "models" / "gender_faceres_fp16.tflite"
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


def ensure_person_detector() -> Path:
    with _lock:
        if not PERSON_FILE.exists():
            _download(PERSON_URL, PERSON_FILE)
    return PERSON_FILE


def ensure_face_detector() -> Path:
    with _lock:
        if not FACE_FILE.exists():
            _download(FACE_URL, FACE_FILE)
    return FACE_FILE


def ensure_gender_model() -> Path:
    with _lock:
        if not GENDER_FILE.exists():
            if not GENDER_BUNDLED.exists():
                raise RuntimeError(f"Gender model missing: expected {GENDER_BUNDLED} (part of the repository)")
            shutil.copyfile(GENDER_BUNDLED, GENDER_FILE)
    return GENDER_FILE


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
        "persons": {"name": "MediaPipe EfficientDet-Lite0 (person detector)", "ready": PERSON_FILE.exists(),
                    "source": PERSON_URL, "license": "Apache-2.0"},
        "faces": {"name": "MediaPipe BlazeFace (short range)", "ready": FACE_FILE.exists(),
                  "source": FACE_URL, "license": "Apache-2.0"},
        "gender": {"name": "FaceRes gender classifier (TFLite fp16)", "ready": GENDER_FILE.exists() or GENDER_BUNDLED.exists(),
                   "source": "repository: models/gender_faceres_fp16.tflite", "license": "see models/README.md"},
        "nudenet": {"name": "NudeNet v3 (320n)", "ready": NUDENET_FILE.exists(),
                    "source": "pip package 'nudenet' (fallback: " + NUDENET_URL + ")", "license": "MIT"},
    }


def ensure_all() -> None:
    ensure_segmenter()
    ensure_person_detector()
    ensure_face_detector()
    ensure_gender_model()
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
