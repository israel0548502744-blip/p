"""FastAPI server: REST API for the React UI + static hosting of the built frontend.

It binds to 127.0.0.1 by default, so videos never leave the machine.
"""

from __future__ import annotations

import logging
import shutil
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import __version__, media, models
from .config import ALLOWED_EXTENSIONS, FRONTEND_DIST, WORK_DIR
from .jobs import Manager, new_upload_path
from .pipeline import CensorSettings

log = logging.getLogger("blueshield.server")

app = FastAPI(title="BlueShield", version=__version__)
app.add_middleware(
    CORSMiddleware,
    allow_origin_regex=r"https?://(localhost|127\.0\.0\.1)(:\d+)?",
    allow_methods=["*"],
    allow_headers=["*"],
)
manager = Manager()
# analysis mask files from a previous run of the server are no longer referenced
for _stale in WORK_DIR.glob("*.masks"):
    _stale.unlink(missing_ok=True)


class SettingsIn(BaseModel):
    color: str = "#FFFFFF"
    sensitivity: int = Field(60, ge=0, le=100)
    softness: int = Field(20, ge=0, le=100)
    aggressive: bool = False
    animated: bool = False
    include_face: bool = False
    speed: str = "balanced"
    quality: str = "balanced"
    keep_audio: bool = True
    target: str = "female"
    gender_threshold: int = Field(70, ge=51, le=99)
    uncertain_policy: str = "censor"


class RenderIn(BaseModel):
    overrides: dict[str, str] = {}
    settings: SettingsIn | None = None


class JobIn(BaseModel):
    video_id: str
    settings: SettingsIn = SettingsIn()


@app.get("/api/health")
def health() -> dict:
    return {
        "ok": True,
        "version": __version__,
        "ffmpeg": media.ffmpeg_available(),
        "models": models.models_status(),
        "acceleration": models.gpu_summary(),
        "encoders": media.available_encoders(),
        "local_only": True,
    }


@app.post("/api/videos")
async def upload_video(file: UploadFile = File(...)) -> dict:
    name = file.filename or "video.mp4"
    ext = Path(name).suffix.lower()
    if ext and ext not in ALLOWED_EXTENSIONS:
        raise HTTPException(415, f"Unsupported file type '{ext}'.")
    dest = new_upload_path(name)
    try:
        with open(dest, "wb") as f:
            while chunk := await file.read(8 << 20):  # stream to disk in 8 MB chunks
                f.write(chunk)
        rec = manager.register_video(name, dest)
    except media.MediaError as e:
        dest.unlink(missing_ok=True)
        raise HTTPException(422, str(e))
    finally:
        await file.close()
    return rec.to_dict()


def _video(video_id: str):
    rec = manager.videos.get(video_id)
    if rec is None:
        raise HTTPException(404, "Video not found")
    return rec


@app.get("/api/videos/{video_id}")
def get_video(video_id: str) -> dict:
    return _video(video_id).to_dict()


@app.get("/api/videos/{video_id}/source")
def video_source(video_id: str) -> FileResponse:
    rec = _video(video_id)
    return FileResponse(rec.path, filename=rec.name, content_disposition_type="inline")


@app.get("/api/videos/{video_id}/preview")
def video_preview(video_id: str) -> FileResponse:
    rec = _video(video_id)
    if rec.info.browser_playable:
        return FileResponse(rec.path, media_type="video/mp4" if rec.path.suffix != ".webm" else "video/webm")
    if rec.proxy_state == "ready" and rec.proxy:
        return FileResponse(rec.proxy, media_type="video/mp4")
    raise HTTPException(409, f"Preview not ready ({rec.proxy_state})")


@app.get("/api/videos/{video_id}/thumbnail")
def video_thumbnail(video_id: str) -> FileResponse:
    rec = _video(video_id)
    if not rec.thumbnail:
        raise HTTPException(404, "No thumbnail")
    return FileResponse(rec.thumbnail, media_type="image/jpeg")


@app.post("/api/jobs")
def create_job(body: JobIn) -> dict:
    rec = _video(body.video_id)
    try:
        settings = CensorSettings(**body.settings.model_dump()).validate()
    except ValueError as e:
        raise HTTPException(422, str(e))
    return manager.submit(rec, settings).to_dict()


def _job(job_id: str):
    job = manager.jobs.get(job_id)
    if job is None:
        raise HTTPException(404, "Job not found")
    return job


@app.get("/api/jobs/{job_id}")
def get_job(job_id: str) -> dict:
    return _job(job_id).to_dict()


@app.post("/api/jobs/{job_id}/pause")
def pause_job(job_id: str) -> dict:
    job = _job(job_id)
    job.control.pause()
    return job.to_dict()


@app.post("/api/jobs/{job_id}/resume")
def resume_job(job_id: str) -> dict:
    job = _job(job_id)
    job.control.resume()
    return job.to_dict()


@app.post("/api/jobs/{job_id}/cancel")
def cancel_job(job_id: str) -> dict:
    job = _job(job_id)
    job.control.cancel()
    if job.progress.stage == "queued" and job.kind == "full":
        job.progress.stage = "cancelled"
    return job.to_dict()


@app.post("/api/jobs/{job_id}/render")
def rerender_job(job_id: str, body: RenderIn) -> dict:
    """Re-render with manual per-person overrides (and/or new render settings) — no re-analysis."""
    job = _job(job_id)
    try:
        overrides = {int(k): v for k, v in body.overrides.items()}
        settings = CensorSettings(**body.settings.model_dump()).validate() if body.settings else None
        return manager.rerender(job, overrides, settings).to_dict()
    except ValueError as e:
        raise HTTPException(409, str(e))


@app.get("/api/jobs/{job_id}/people/{person_id}.jpg")
def person_thumbnail(job_id: str, person_id: int) -> Response:
    job = _job(job_id)
    t = job.analysis.people.get(person_id) if job.analysis else None
    if t is None or t.thumb is None:
        raise HTTPException(404, "No thumbnail")
    return Response(t.thumb, media_type="image/jpeg", headers={"Cache-Control": "max-age=3600"})


@app.get("/api/jobs/{job_id}/preview.jpg")
def job_preview(job_id: str) -> Response:
    job = _job(job_id)
    if not job.preview_jpeg:
        raise HTTPException(404, "No preview yet")
    return Response(job.preview_jpeg, media_type="image/jpeg", headers={"Cache-Control": "no-store"})


def _output(job_id: str) -> Path:
    job = _job(job_id)
    if job.progress.stage != "complete" or not job.output or not job.output.exists():
        raise HTTPException(409, "Output not ready")
    return job.output


@app.get("/api/jobs/{job_id}/output")
def job_output(job_id: str) -> FileResponse:
    return FileResponse(_output(job_id), media_type="video/mp4")


@app.get("/api/jobs/{job_id}/download")
def job_download(job_id: str) -> FileResponse:
    job = _job(job_id)
    path = _output(job_id)
    name = f"{Path(job.video.name).stem}_blueshield.mp4"
    return FileResponse(path, media_type="video/mp4", filename=name)


@app.exception_handler(Exception)
async def unhandled(_: Request, exc: Exception) -> JSONResponse:
    log.exception("Unhandled error")
    return JSONResponse({"detail": str(exc)}, status_code=500)


if FRONTEND_DIST.exists():
    app.mount("/assets", StaticFiles(directory=FRONTEND_DIST / "assets"), name="assets")

    @app.get("/{path:path}", include_in_schema=False)
    def spa(path: str) -> FileResponse:
        target = (FRONTEND_DIST / path).resolve()
        if path and target.is_file() and FRONTEND_DIST.resolve() in target.parents:
            return FileResponse(target)
        return FileResponse(FRONTEND_DIST / "index.html")
