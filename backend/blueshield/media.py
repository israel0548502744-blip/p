"""FFmpeg / FFprobe helpers: probing, streaming decode, streaming encode."""

from __future__ import annotations

import functools
import json
import shutil
import subprocess
from dataclasses import asdict, dataclass
from fractions import Fraction
from pathlib import Path
from typing import Iterator, Optional

import numpy as np

FFMPEG = shutil.which("ffmpeg") or "ffmpeg"
FFPROBE = shutil.which("ffprobe") or "ffprobe"

# Codecs/containers that every modern browser can play natively.
BROWSER_VIDEO_CODECS = {"h264", "vp8", "vp9", "av1"}
BROWSER_CONTAINERS = {"mp4", "mov", "webm", "m4v"}


class MediaError(RuntimeError):
    pass


@dataclass
class VideoInfo:
    path: str
    duration: float
    width: int  # display width (after rotation)
    height: int  # display height (after rotation)
    fps: float
    fps_str: str  # exact rational, e.g. "30000/1001"
    frames: int
    codec: str
    container: str
    has_audio: bool
    audio_codec: Optional[str]
    size: int
    rotation: int
    bitrate: Optional[int]

    @property
    def browser_playable(self) -> bool:
        ext = Path(self.path).suffix.lower().lstrip(".")
        return self.codec in BROWSER_VIDEO_CODECS and ext in BROWSER_CONTAINERS

    def to_dict(self) -> dict:
        d = asdict(self)
        d.pop("path")
        d["browser_playable"] = self.browser_playable
        return d


def ffmpeg_available() -> bool:
    return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


def _parse_rate(rate: Optional[str]) -> Optional[Fraction]:
    if not rate or rate in ("0/0", "0"):
        return None
    try:
        f = Fraction(rate)
    except (ValueError, ZeroDivisionError):
        return None
    return f if f > 0 else None


def probe(path: str | Path) -> VideoInfo:
    path = str(path)
    cmd = [
        FFPROBE, "-v", "error", "-print_format", "json",
        "-show_format", "-show_streams", path,
    ]
    try:
        out = subprocess.run(cmd, capture_output=True, check=True, timeout=60).stdout
    except subprocess.CalledProcessError as e:
        raise MediaError(f"ffprobe could not read this file: {e.stderr.decode(errors='ignore')[-400:]}")
    data = json.loads(out or b"{}")
    streams = data.get("streams", [])
    fmt = data.get("format", {})
    video = next((s for s in streams if s.get("codec_type") == "video"
                  and not s.get("disposition", {}).get("attached_pic")), None)
    if video is None:
        raise MediaError("No video stream found in this file.")
    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)

    rate = _parse_rate(video.get("avg_frame_rate")) or _parse_rate(video.get("r_frame_rate"))
    r_rate = _parse_rate(video.get("r_frame_rate"))
    # avg_frame_rate can be odd for VFR; prefer r_frame_rate when they're close.
    if rate and r_rate and abs(float(rate) - float(r_rate)) / float(r_rate) < 0.02:
        rate = r_rate
    if rate is None or float(rate) > 240:
        rate = Fraction(30)
    rate = rate.limit_denominator(1001)

    duration = 0.0
    for src in (video.get("duration"), fmt.get("duration")):
        try:
            if src is not None and float(src) > 0:
                duration = float(src)
                break
        except ValueError:
            pass

    rotation = 0
    tags = video.get("tags") or {}
    if "rotate" in tags:
        try:
            rotation = int(tags["rotate"])
        except ValueError:
            pass
    for sd in video.get("side_data_list") or []:
        if "rotation" in sd:
            try:
                rotation = int(sd["rotation"])
            except (TypeError, ValueError):
                pass
    rotation %= 360

    w, h = int(video.get("width", 0)), int(video.get("height", 0))
    if rotation in (90, 270):
        w, h = h, w
    if w <= 0 or h <= 0:
        raise MediaError("Could not determine the video resolution.")

    frames = int(round(duration * float(rate))) if duration > 0 else 0
    if frames <= 0:
        try:
            frames = int(video.get("nb_frames") or 0)
        except ValueError:
            frames = 0

    try:
        bitrate = int(fmt.get("bit_rate")) if fmt.get("bit_rate") else None
    except ValueError:
        bitrate = None

    return VideoInfo(
        path=path,
        duration=duration,
        width=w,
        height=h,
        fps=float(rate),
        fps_str=f"{rate.numerator}/{rate.denominator}",
        frames=max(frames, 1),
        codec=str(video.get("codec_name", "unknown")),
        container=str(fmt.get("format_name", "unknown")),
        has_audio=audio is not None,
        audio_codec=audio.get("codec_name") if audio else None,
        size=int(fmt.get("size") or Path(path).stat().st_size),
        rotation=rotation,
        bitrate=bitrate,
    )


def scaled_size(w: int, h: int, max_side: int) -> tuple[int, int]:
    """Size with long side <= max_side, both dimensions even."""
    s = min(1.0, max_side / max(w, h))
    sw = max(2, int(round(w * s / 2)) * 2)
    sh = max(2, int(round(h * s / 2)) * 2)
    return sw, sh


class FrameReader:
    """Streams decoded BGR frames from FFmpeg through a pipe (never the whole video in RAM)."""

    def __init__(self, info: VideoInfo, width: int, height: int, threads: int = 0):
        self.info = info
        self.width = width
        self.height = height
        self.frame_bytes = width * height * 3
        vf = f"scale={width}:{height}:flags=area" if (width, height) != (info.width, info.height) else None
        cmd = [FFMPEG, "-v", "error", "-nostdin", "-threads", str(threads), "-i", info.path, "-an", "-sn"]
        filters = [f"fps={info.fps_str}"]
        if vf:
            filters.append(vf)
        cmd += ["-vf", ",".join(filters), "-f", "rawvideo", "-pix_fmt", "bgr24", "pipe:1"]
        self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     bufsize=self.frame_bytes * 2)

    def __iter__(self) -> Iterator[np.ndarray]:
        assert self.proc.stdout is not None
        while True:
            buf = self.proc.stdout.read(self.frame_bytes)
            if not buf or len(buf) < self.frame_bytes:
                break
            yield np.frombuffer(buf, np.uint8).reshape(self.height, self.width, 3)

    def close(self) -> None:
        if self.proc.poll() is None:
            self.proc.kill()
        try:
            self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass
        for s in (self.proc.stdout, self.proc.stderr):
            if s:
                s.close()


@functools.lru_cache(maxsize=1)
def available_encoders() -> list[str]:
    """H.264 encoders that actually work on this machine, best first."""
    candidates = ["h264_nvenc", "h264_videotoolbox", "h264_qsv", "h264_amf"]
    try:
        listing = subprocess.run([FFMPEG, "-hide_banner", "-encoders"], capture_output=True,
                                 timeout=20).stdout.decode(errors="ignore")
    except Exception:
        listing = ""
    working = []
    for enc in candidates:
        if enc not in listing:
            continue
        test = [FFMPEG, "-v", "error", "-f", "lavfi", "-i", "color=c=blue:s=256x256:d=0.2",
                "-c:v", enc, "-f", "null", "-"]
        try:
            if subprocess.run(test, capture_output=True, timeout=20).returncode == 0:
                working.append(enc)
        except Exception:
            pass
    working.append("libx264")
    return working


QUALITY_CRF = {"high": 17, "balanced": 20, "small": 25}


def encoder_args(encoder: str, quality: str) -> list[str]:
    crf = QUALITY_CRF.get(quality, 20)
    if encoder == "libx264":
        return ["-c:v", "libx264", "-preset", "medium" if quality == "high" else "veryfast",
                "-crf", str(crf), "-profile:v", "high"]
    if encoder == "h264_nvenc":
        return ["-c:v", "h264_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", str(crf + 2), "-b:v", "0"]
    if encoder == "h264_videotoolbox":
        return ["-c:v", "h264_videotoolbox", "-q:v", str(max(40, 85 - crf * 2))]
    if encoder == "h264_qsv":
        return ["-c:v", "h264_qsv", "-global_quality", str(crf + 2)]
    return ["-c:v", encoder, "-qp_i", str(crf), "-qp_p", str(crf + 2)]


class FrameWriter:
    """Pipes BGR frames into FFmpeg, encoding H.264 MP4 and muxing the original audio."""

    def __init__(self, info: VideoInfo, out_path: str | Path, encoder: str = "libx264",
                 quality: str = "balanced", keep_audio: bool = True):
        self.width, self.height = info.width, info.height
        self.out_path = str(out_path)
        cmd = [
            FFMPEG, "-v", "error", "-nostdin", "-y",
            "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{self.width}x{self.height}",
            "-framerate", info.fps_str, "-i", "pipe:0",
        ]
        if keep_audio and info.has_audio:
            cmd += ["-i", info.path, "-map", "0:v:0", "-map", "1:a:0?"]
            cmd += ["-c:a", "aac", "-b:a", "192k", "-shortest"]
        else:
            cmd += ["-map", "0:v:0"]
        # libx264 + yuv420p requires even dimensions.
        if self.width % 2 or self.height % 2:
            cmd += ["-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2"]
        cmd += encoder_args(encoder, quality)
        cmd += ["-pix_fmt", "yuv420p", "-r", info.fps_str, "-movflags", "+faststart", self.out_path]
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.PIPE)

    def write(self, frame: np.ndarray) -> None:
        assert self.proc.stdin is not None
        try:
            self.proc.stdin.write(np.ascontiguousarray(frame).tobytes())
        except BrokenPipeError:
            raise MediaError(f"Encoder stopped unexpectedly: {self._stderr()}")

    def _stderr(self) -> str:
        try:
            return (self.proc.stderr.read() or b"").decode(errors="ignore")[-600:] if self.proc.stderr else ""
        except Exception:
            return ""

    def finish(self) -> None:
        if self.proc.stdin:
            try:
                self.proc.stdin.close()
            except BrokenPipeError:
                pass
        rc = self.proc.wait()
        if rc != 0:
            raise MediaError(f"FFmpeg encoding failed: {self._stderr()}")

    def abort(self) -> None:
        if self.proc.poll() is None:
            self.proc.kill()
            self.proc.wait()
        Path(self.out_path).unlink(missing_ok=True)


def make_thumbnail(info: VideoInfo, out_path: Path, width: int = 640) -> bool:
    t = min(max(info.duration * 0.1, 0.0), 3.0)
    cmd = [FFMPEG, "-v", "error", "-y", "-ss", f"{t:.2f}", "-i", info.path, "-frames:v", "1",
           "-vf", f"scale={width}:-2", "-q:v", "4", str(out_path)]
    return subprocess.run(cmd, capture_output=True, timeout=60).returncode == 0


def make_preview_proxy(info: VideoInfo, out_path: Path) -> bool:
    """Browser-playable H.264 proxy (≤720p) for formats browsers can't play directly."""
    w, h = scaled_size(info.width, info.height, 1280)
    tmp = out_path.with_suffix(".part.mp4")
    cmd = [FFMPEG, "-v", "error", "-y", "-nostdin", "-i", info.path, "-map", "0:v:0", "-map", "0:a:0?",
           "-vf", f"fps={info.fps_str},scale={w}:{h}", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
           "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", str(tmp)]
    ok = subprocess.run(cmd, capture_output=True).returncode == 0
    if ok:
        tmp.replace(out_path)
    else:
        tmp.unlink(missing_ok=True)
    return ok
