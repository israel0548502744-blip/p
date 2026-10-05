"""In-process video registry and a single-worker job queue."""

from __future__ import annotations

import logging
import queue
import threading
import time
import traceback
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import cv2
import numpy as np

from . import media
from .config import OUTPUT_DIR, UPLOAD_DIR
from .pipeline import Cancelled, CensorSettings, Control, Engine, Progress, run_pipeline

log = logging.getLogger("blueshield.jobs")


@dataclass
class VideoRecord:
    id: str
    name: str
    path: Path
    info: media.VideoInfo
    thumbnail: Optional[Path] = None
    proxy: Optional[Path] = None
    proxy_state: str = "none"  # none|building|ready|failed
    created: float = field(default_factory=time.time)

    def to_dict(self) -> dict:
        d = {"id": self.id, "name": self.name, **self.info.to_dict()}
        d["preview_ready"] = self.info.browser_playable or self.proxy_state == "ready"
        d["proxy_state"] = self.proxy_state
        d["source_url"] = f"/api/videos/{self.id}/source"
        d["preview_url"] = f"/api/videos/{self.id}/preview"
        d["thumbnail_url"] = f"/api/videos/{self.id}/thumbnail"
        return d


@dataclass
class Job:
    id: str
    video: VideoRecord
    settings: CensorSettings
    control: Control = field(default_factory=Control)
    progress: Progress = field(default_factory=Progress)
    output: Optional[Path] = None
    result: Optional[dict] = None
    error: Optional[str] = None
    preview_jpeg: Optional[bytes] = None
    created: float = field(default_factory=time.time)

    @property
    def state(self) -> str:
        return self.progress.stage

    def to_dict(self) -> dict:
        p = self.progress
        return {
            "id": self.id,
            "video_id": self.video.id,
            "stage": p.stage,
            "paused": self.control.paused and p.stage not in ("complete", "error", "cancelled"),
            "percent": p.percent,
            "frame": p.frame,
            "total_frames": p.total_frames,
            "pass_index": p.pass_index,
            "eta_seconds": p.eta_seconds,
            "fps": p.fps,
            "elapsed": round(p.elapsed, 1),
            "message": p.message,
            "timeline": p.timeline,
            "settings": self.settings.to_dict(),
            "error": self.error,
            "result": self.result,
            "output_url": f"/api/jobs/{self.id}/output" if p.stage == "complete" else None,
            "download_url": f"/api/jobs/{self.id}/download" if p.stage == "complete" else None,
            "has_preview": self.preview_jpeg is not None,
        }


class Manager:
    def __init__(self) -> None:
        self.videos: dict[str, VideoRecord] = {}
        self.jobs: dict[str, Job] = {}
        self.engine = Engine()
        self._queue: "queue.Queue[Job]" = queue.Queue()
        self._worker = threading.Thread(target=self._loop, name="blueshield-worker", daemon=True)
        self._worker.start()

    # ── videos ──
    def register_video(self, name: str, path: Path) -> VideoRecord:
        info = media.probe(path)
        vid = path.stem
        rec = VideoRecord(id=vid, name=name, path=path, info=info)
        thumb = UPLOAD_DIR / f"{vid}.thumb.jpg"
        if media.make_thumbnail(info, thumb):
            rec.thumbnail = thumb
        self.videos[vid] = rec
        if not info.browser_playable:
            rec.proxy_state = "building"
            threading.Thread(target=self._build_proxy, args=(rec,), daemon=True).start()
        return rec

    def _build_proxy(self, rec: VideoRecord) -> None:
        proxy = UPLOAD_DIR / f"{rec.id}.preview.mp4"
        ok = media.make_preview_proxy(rec.info, proxy)
        rec.proxy = proxy if ok else None
        rec.proxy_state = "ready" if ok else "failed"

    # ── jobs ──
    def submit(self, video: VideoRecord, settings: CensorSettings) -> Job:
        job = Job(id=uuid.uuid4().hex[:12], video=video, settings=settings.validate())
        job.progress.total_frames = video.info.frames
        self.jobs[job.id] = job
        self._queue.put(job)
        return job

    def _loop(self) -> None:
        while True:
            job = self._queue.get()
            if job.control.cancelled:
                job.progress.stage = "cancelled"
                continue
            self._run(job)

    def _run(self, job: Job) -> None:
        stem = Path(job.video.name).stem or "video"
        out = OUTPUT_DIR / f"{job.id}_{_safe(stem)}_blueshield.mp4"

        def on_preview(img: np.ndarray) -> None:
            h, w = img.shape[:2]
            s = min(1.0, 640 / max(w, h))
            if s < 1:
                img = cv2.resize(img, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA)
            ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 78])
            if ok:
                job.preview_jpeg = buf.tobytes()

        try:
            job.result = run_pipeline(self.engine, job.video.info, job.settings, out, job.control,
                                      job.progress, on_preview, job.id)
            job.output = out
            job.progress.stage = "complete"
            job.progress.message = "Censorship complete."
        except Cancelled:
            job.progress.stage = "cancelled"
            job.progress.message = "Processing cancelled."
            out.unlink(missing_ok=True)
        except Exception as e:  # noqa: BLE001
            log.error("Job %s failed:\n%s", job.id, traceback.format_exc())
            job.error = str(e) or e.__class__.__name__
            job.progress.stage = "error"
            job.progress.message = "Processing failed."
            out.unlink(missing_ok=True)


def _safe(s: str) -> str:
    keep = "".join(ch if ch.isalnum() or ch in "-_" else "_" for ch in s)
    return keep[:60] or "video"


def new_upload_path(filename: str) -> Path:
    ext = Path(filename).suffix.lower() or ".mp4"
    return UPLOAD_DIR / f"{uuid.uuid4().hex[:12]}{ext}"
