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
from .pipeline import (Analysis, Cancelled, CensorSettings, Control, Engine, Progress, person_summary, render,
                       run_pipeline)

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
    analysis: Optional[Analysis] = None  # kept so overrides only need a re-render
    overrides: dict[int, str] = field(default_factory=dict)
    version: int = 0
    kind: str = "full"  # full | render

    def people(self) -> list[dict]:
        if self.analysis is None:
            return (self.result or {}).get("people", [])
        fps = self.video.info.fps
        out = [person_summary(t, self.settings, self.overrides.get(t.tid, "auto"), fps)
               for t in self.analysis.people.values()]
        for p in out:
            p["thumbnail_url"] = f"/api/jobs/{self.id}/people/{p['id']}.jpg" if p["has_thumbnail"] else None
        return sorted(out, key=lambda p: (-p["frames"], p["id"]))

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
            "output_url": f"/api/jobs/{self.id}/output?v={self.version}" if p.stage == "complete" else None,
            "download_url": f"/api/jobs/{self.id}/download" if p.stage == "complete" else None,
            "has_preview": self.preview_jpeg is not None,
            "people": self.people(),
            "overrides": {str(k): v for k, v in self.overrides.items()},
            "can_rerender": self.analysis is not None,
            "kind": self.kind,
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
                job.progress.stage = "complete" if job.kind == "render" and job.output else "cancelled"
                continue
            self._run(job)

    def rerender(self, job: Job, overrides: dict[int, str], settings: Optional[CensorSettings] = None) -> Job:
        if job.analysis is None:
            raise ValueError("This job's analysis is no longer available; please process the video again.")
        if job.progress.stage not in ("complete", "error", "cancelled"):
            raise ValueError("The job is still running.")
        job.overrides = {int(k): v for k, v in overrides.items() if v in ("censor", "keep")}
        if settings is not None:
            # analysis-time settings can't change without re-analysing
            a = job.analysis.settings
            settings.sensitivity, settings.aggressive, settings.include_face, settings.speed = (
                a.sensitivity, a.aggressive, a.include_face, a.speed)
            job.settings = settings.validate()
        job.kind = "render"
        job.error = None
        job.control.reset()
        job.progress = Progress(stage="queued", total_frames=job.analysis.total)
        self._queue.put(job)
        return job

    def _drop_old_analyses(self, keep: Job) -> None:
        """Analyses hold a mask file on disk; keep only the most recent few."""
        done = [j for j in self.jobs.values() if j.analysis is not None and j is not keep]
        for j in sorted(done, key=lambda j: j.created)[:-2]:
            j.analysis.close()
            j.analysis = None

    def _run(self, job: Job) -> None:
        stem = Path(job.video.name).stem or "video"
        job.version += 1
        out = OUTPUT_DIR / f"{job.id}_v{job.version}_{_safe(stem)}_blueshield.mp4"

        def on_preview(img: np.ndarray) -> None:
            h, w = img.shape[:2]
            s = min(1.0, 640 / max(w, h))
            if s < 1:
                img = cv2.resize(img, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA)
            ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 78])
            if ok:
                job.preview_jpeg = buf.tobytes()

        previous = job.output
        try:
            if job.kind == "render" and job.analysis is not None:
                res = render(job.analysis, job.settings, job.overrides, out, job.control, job.progress, on_preview)
                res["people"] = job.people()
                job.result = res
            else:
                job.result, job.analysis = run_pipeline(self.engine, job.video.info, job.settings, out, job.control,
                                                        job.progress, on_preview, job.id, keep_analysis=True)
            job.output = out
            if previous and previous != out:
                previous.unlink(missing_ok=True)
            job.progress.stage = "complete"
            job.progress.percent = 100.0
            job.progress.message = "Censorship complete."
            self._drop_old_analyses(job)
        except Cancelled:
            job.progress.stage = "cancelled"
            job.progress.message = "Processing cancelled."
            out.unlink(missing_ok=True)
            if job.kind == "render" and previous and previous.exists():
                # a cancelled re-render leaves the previous result usable
                job.progress.stage = "complete"
                job.progress.message = "Re-render cancelled — previous result kept."
                job.version -= 1
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
