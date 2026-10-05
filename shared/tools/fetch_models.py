#!/usr/bin/env python3
"""Fetch every model listed in shared/models.json into a directory.

    python shared/tools/fetch_models.py --dest android/app/src/main/assets/models --target android

Sources: Google's public MediaPipe model bucket, the NudeNet wheel on PyPI, and
files committed to this repository (models/). Only the standard library is used.
"""

from __future__ import annotations

import argparse
import io
import json
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "shared" / "models.json"


def _get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "BlueShield-fetch/1.0"})
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


def _from_pypi(package: str, member: str) -> bytes:
    meta = json.loads(_get(f"https://pypi.org/pypi/{package}/json"))
    wheel = next(u["url"] for u in meta["urls"] if u["filename"].endswith(".whl"))
    with zipfile.ZipFile(io.BytesIO(_get(wheel))) as z:
        return z.read(member)


def fetch(dest: Path, target: str | None) -> list[Path]:
    dest.mkdir(parents=True, exist_ok=True)
    out = []
    for m in json.loads(MANIFEST.read_text())["models"]:
        if target and target not in m["used_by"]:
            continue
        path = dest / m["file"]
        if path.exists() and path.stat().st_size > 0:
            print(f"  [cached] {m['file']}")
            out.append(path)
            continue
        print(f"  [fetch ] {m['file']}  ({m['stage']})")
        if "url" in m:
            data = _get(m["url"])
        elif "pypi" in m:
            data = _from_pypi(m["pypi"]["package"], m["pypi"]["member"])
        else:
            data = (ROOT / m["repo_path"]).read_bytes()
        tmp = path.with_suffix(path.suffix + ".part")
        tmp.write_bytes(data)
        tmp.replace(path)
        out.append(path)
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--dest", required=True, type=Path)
    ap.add_argument("--target", choices=["desktop", "android"], default=None)
    a = ap.parse_args()
    try:
        files = fetch(a.dest, a.target)
    except Exception as e:  # noqa: BLE001
        sys.exit(f"Model download failed: {e}")
    print(f"{len(files)} model(s) ready in {a.dest}")
