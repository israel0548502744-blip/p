#!/usr/bin/env python3
"""Download / install the models BlueShield needs (also done automatically on first start)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backend"))

from blueshield import models  # noqa: E402

if __name__ == "__main__":
    print("Installing models into", models.MODELS_DIR)
    models.ensure_all()
    for key, st in models.models_status().items():
        print(f"  [{'ok' if st['ready'] else 'missing'}] {st['name']}  ({st['license']})  <- {st['source']}")
