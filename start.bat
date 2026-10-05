@echo off
REM BlueShield one-command launcher (Windows)
cd /d "%~dp0"
where ffmpeg >nul 2>nul || (echo FFmpeg is required. Install it with:  winget install Gyan.FFmpeg & exit /b 1)
if not exist .venv (
  echo Creating Python virtual environment...
  py -3 -m venv .venv || python -m venv .venv
)
call .venv\Scripts\activate.bat
if not exist .venv\.deps-installed (
  echo Installing Python dependencies...
  python -m pip install --upgrade pip
  pip install -r backend\requirements.txt || exit /b 1
  type nul > .venv\.deps-installed
)
if not exist frontend\dist\index.html (
  echo Building the interface...
  pushd frontend
  call npm install --no-audit --no-fund || exit /b 1
  call npm run build || exit /b 1
  popd
)
python scripts\download_models.py || exit /b 1
cd backend
python run.py --open %*
