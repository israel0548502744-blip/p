"""Engine tests: run with `python -m pytest backend/tests` (requires FFmpeg + models)."""

import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from blueshield import media  # noqa: E402
from blueshield.censor import BlueCensor, hex_to_bgr  # noqa: E402
from blueshield.maskstore import MaskStore  # noqa: E402
from blueshield.pipeline import CensorSettings, Control, Engine, Progress, run_pipeline  # noqa: E402
from blueshield.tracking import BoxTracker, TemporalFuser, FlowEstimator  # noqa: E402
from blueshield.detectors import Detection  # noqa: E402


@pytest.fixture(scope="module")
def sample_video(tmp_path_factory) -> Path:
    out = tmp_path_factory.mktemp("v") / "sample.mp4"
    subprocess.run(
        [media.FFMPEG, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=320x180:r=25:d=2",
         "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-c:v", "libx264", "-pix_fmt", "yuv420p",
         "-c:a", "aac", "-shortest", str(out)],
        check=True,
    )
    return out


def test_probe(sample_video):
    info = media.probe(sample_video)
    assert (info.width, info.height) == (320, 180)
    assert info.fps == 25 and info.has_audio and info.browser_playable
    assert 49 <= info.frames <= 51


def test_hex_color():
    assert hex_to_bgr("#1E4DFF") == (255, 77, 30)
    assert hex_to_bgr("00f") == (255, 0, 0)
    with pytest.raises(ValueError):
        hex_to_bgr("#12")


def test_censor_only_touches_mask():
    frame = np.full((100, 200, 3), 128, np.uint8)
    mask = np.zeros((50, 100), np.uint8)
    mask[10:30, 20:40] = 255
    c = BlueCensor(200, 100, "#0000FF", softness=0, aggressive=False, animated=False, fps=25)
    out, cov = c.apply(frame, mask, 0)
    assert cov > 0
    assert tuple(out[40, 60]) == (255, 0, 0)  # centre of the region is pure blue (BGR)
    assert tuple(out[90, 190]) == (128, 128, 128)  # far away untouched
    empty, cov0 = c.apply(frame, np.zeros_like(mask), 0)
    assert cov0 == 0 and empty is frame


def test_mask_store_roundtrip(tmp_path):
    s = MaskStore(tmp_path / "m.bin", 64, 32)
    a = np.zeros((32, 64), np.uint8)
    b = a.copy()
    b[5:10, 5:10] = 255
    s.append(a)
    s.append(b)
    assert len(s) == 2 and not s.get(0).any() and (s.get(1) == b).all() and not s.get(5).any()
    s.close()
    assert not (tmp_path / "m.bin").exists()


def test_tracker_keeps_region_through_short_miss():
    flow = FlowEstimator(100, 100)
    flow.update(np.zeros((100, 100, 3), np.uint8))
    t = BoxTracker(max_misses=2)
    t.update([Detection("BUTTOCKS_EXPOSED", 0.9, (10, 10, 40, 40))])
    t.update([])  # a missed detection round
    assert t.render((100, 100))[25, 25] > 0
    t.update([])
    t.update([])
    assert t.render((100, 100)).max() == 0


def test_fuser_hysteresis():
    flow = FlowEstimator(20, 20)
    f = TemporalFuser(release=0.5, on_threshold=0.5)
    on = np.full((20, 20), 0.9, np.float32)
    assert f.update(on, flow, True).all()
    # a dip below the on-threshold but above the off-threshold stays censored
    assert f.update(np.full((20, 20), 0.35, np.float32), flow, True).all()


def test_full_pipeline(sample_video, tmp_path):
    info = media.probe(sample_video)
    out = tmp_path / "out.mp4"
    prog = Progress()
    res = run_pipeline(Engine(), info, CensorSettings(), out, Control(), prog, lambda _: None, "t")
    assert res["frames"] >= 49 and prog.percent == 100.0
    oi = media.probe(out)
    assert (oi.width, oi.height, oi.fps, oi.codec, oi.has_audio) == (320, 180, 25.0, "h264", True)
    reader = media.FrameReader(oi, oi.width, oi.height)
    frames = sum(1 for _ in reader)
    reader.close()
    assert frames == res["frames"]


def test_segmenter_finds_skin():
    from blueshield.detectors import SkinSegmenter

    img = np.zeros((256, 256, 3), np.uint8)
    seg = SkinSegmenter().segment(img)
    assert seg.skin.shape == (256, 256) and float(seg.skin.max()) < 0.5  # nothing on a black frame
    _ = cv2  # keep import used
