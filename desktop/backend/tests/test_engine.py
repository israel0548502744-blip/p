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
    f = TemporalFuser(on_threshold=0.5)
    on = np.full((20, 20), 0.9, np.float32)
    assert f.update(on, flow, True).all()
    # a dip below the on-threshold but above the off-threshold stays censored
    assert f.update(np.full((20, 20), 0.35, np.float32), flow, True).all()
    # skin that is gone from the current measurement is released at once (no trail behind moving arms)
    assert not f.update(np.zeros((20, 20), np.float32), flow, True).any()


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


def test_partial_duplicate_detections_are_dropped():
    from blueshield.persons import PersonBox, suppress_contained
    body = PersonBox((100, 50, 260, 400), 0.8)
    upper = PersonBox((110, 55, 250, 200), 0.7)
    other = PersonBox((300, 50, 420, 400), 0.6)
    assert suppress_contained([upper, body, other]) == [body, other]
    # a much more confident inner box is kept (could be someone standing in front)
    sure = PersonBox((110, 55, 250, 200), 0.99)
    assert len(suppress_contained([PersonBox(body.box, 0.5), sure])) == 2


def test_duplicate_tracks_merge_and_keep_their_evidence():
    from blueshield.people import PersonTracker
    from blueshield.persons import PersonBox
    img = np.full((400, 400, 3), 120, np.uint8)
    body = PersonBox((100, 50, 260, 400), 0.8)
    upper = PersonBox((105, 55, 255, 210), 0.9)
    t = PersonTracker(max_misses=5)
    t.update(img, [body, upper], 0)
    assert len(t.active) == 1  # a partial box of someone already tracked is not a new person
    t2 = PersonTracker(max_misses=5)
    t2.update(img, [upper], 0)
    t2.update(img, [body, upper], 2)
    assert len(t2.active) == 2
    for tr in t2.active:
        for _ in range(3):
            tr.gender.add(0.1)
    for k in range(2, 5):
        t2.update(img, [body, upper], k * 2)
    assert len(t2.active) == 1
    survivor = t2.active[0]
    assert survivor.gender.votes == 6 and list(t2.aliases.values()) == [survivor.tid]


def test_children_are_not_censored_when_only_women_are():
    from blueshield.gender import GenderEstimate, censor_decision
    g = GenderEstimate()
    for _ in range(8):
        g.add(0.1)
        g.add_age(9)
    assert g.is_child
    assert not censor_decision(g.label(0.7), "female", "censor", "auto", g.is_child)
    assert censor_decision(g.label(0.7), "everyone", "censor", "auto", g.is_child)
    few = GenderEstimate()
    for _ in range(3):
        few.add_age(8)
    assert not few.is_child  # too little evidence: treated as an adult (the safe side)


def test_male_needs_stronger_evidence():
    from blueshield.gender import GenderEstimate
    g = GenderEstimate()
    for _ in range(8):
        g.add(0.62)
    assert g.p_female < 0.3 and g.label(0.7) == "uncertain"


def test_neck_is_free_but_a_low_neckline_is_censored():
    from blueshield.pipeline import CLEAVAGE_START, apply_neckline
    face = (70, 40, 130, 110)

    def body(cleavage):
        m = np.zeros((300, 200), bool)
        m[40:(251 if cleavage else 152), 75:126] = True
        return m
    plain = body(False)
    apply_neckline(plain, face)
    assert not plain.any()
    low = body(True)
    apply_neckline(low, face)
    start = int(110 + CLEAVAGE_START * 70)
    assert not low[start - 2, 100] and low[start + 3, 100] and low[200, 100]
