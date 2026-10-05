#!/usr/bin/env python3
"""Start the BlueShield local engine (API + UI) on http://127.0.0.1:8765."""

import argparse
import logging
import os
import threading
import webbrowser

import uvicorn


def main() -> None:
    ap = argparse.ArgumentParser(description="BlueShield local video censorship engine")
    ap.add_argument("--host", default=os.environ.get("BLUESHIELD_HOST", "127.0.0.1"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("BLUESHIELD_PORT", 8765)))
    ap.add_argument("--open", action="store_true", help="open the UI in the default browser")
    ap.add_argument("--skip-model-check", action="store_true")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    from blueshield import media, models

    if not media.ffmpeg_available():
        raise SystemExit("FFmpeg/FFprobe not found on PATH. Please install FFmpeg first (see README).")
    if not args.skip_model_check:
        logging.info("Checking models…")
        models.ensure_all()

    url = f"http://{args.host}:{args.port}"
    if args.open:
        threading.Timer(1.5, lambda: webbrowser.open(url)).start()
    print(f"\n  BlueShield is running at {url}\n  Your videos are processed locally and are not uploaded.\n")
    uvicorn.run("blueshield.server:app", host=args.host, port=args.port, log_level="warning")


if __name__ == "__main__":
    main()
