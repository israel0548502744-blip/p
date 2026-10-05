#!/usr/bin/env bash
# BlueShield one-command launcher (macOS / Linux).
set -euo pipefail
cd "$(dirname "$0")"

command -v ffmpeg >/dev/null || { echo "FFmpeg is required: https://ffmpeg.org/download.html (brew install ffmpeg / sudo apt install ffmpeg)"; exit 1; }
PY=${PYTHON:-python3}

if [ ! -d .venv ]; then
  echo "› Creating Python virtual environment…"
  "$PY" -m venv .venv
fi
# shellcheck disable=SC1091
source .venv/bin/activate
if [ ! -f .venv/.deps-installed ] || [ backend/requirements.txt -nt .venv/.deps-installed ]; then
  echo "› Installing Python dependencies…"
  pip install --upgrade pip >/dev/null
  pip install -r backend/requirements.txt
  touch .venv/.deps-installed
fi

if [ ! -f frontend/dist/index.html ] || [ -n "$(find frontend/src -newer frontend/dist/index.html -print -quit 2>/dev/null)" ]; then
  command -v npm >/dev/null || { echo "Node.js 18+ is required to build the UI: https://nodejs.org"; exit 1; }
  echo "› Building the interface…"
  (cd frontend && npm install --no-audit --no-fund && npm run build)
fi

echo "› Checking models…"
python scripts/download_models.py
cd backend
exec python run.py --open "$@"
