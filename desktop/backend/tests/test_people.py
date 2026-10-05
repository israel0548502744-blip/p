"""Female-only censorship: person detection, gender classification, ownership, overrides."""

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from blueshield import media  # noqa: E402
from blueshield.config import ANALYSIS_MAX_SIDE  # noqa: E402
from blueshield.gender import GenderEstimate, censor_decision  # noqa: E402
from blueshield.pipeline import (CensorSettings, Control, Engine, FrameRecord, Progress, analyze,  # noqa: E402
                                 compose_mask, decisions_for, render)

FIXTURES = Path(__file__).resolve().parents[3] / "tests" / "fixtures"
WOMAN_AND_MAN = FIXTURES / "woman_and_man.mp4"
MEN = FIXTURES / "men_classroom.mp4"


# ── pure logic ──────────────────────────────────────────────────────────────

def test_gender_estimate_needs_votes_and_confidence():
    g = GenderEstimate()
    assert g.label(0.7) == "uncertain"  # no evidence at all
    g.add(0.1)
    g.add(0.1)
    assert g.label(0.7) == "uncertain"  # still fewer than MIN_VOTES
    g.add(0.1)
    assert g.label(0.7) == "female" and g.p_female > 0.7
    m = GenderEstimate()
    for _ in range(5):
        m.add(0.95)
    assert m.label(0.7) == "male"
    mixed = GenderEstimate()
    for p in (0.2, 0.8, 0.3, 0.7):
        mixed.add(p)
    assert mixed.label(0.7) == "uncertain"


def test_censor_decision_policy_and_overrides():
    assert censor_decision("female", "female", "censor")
    assert not censor_decision("male", "female", "censor")
    assert censor_decision("uncertain", "female", "censor")  # safe fallback
    assert not censor_decision("uncertain", "female", "keep")
    assert censor_decision("male", "everyone", "keep")
    assert censor_decision("male", "female", "keep", override="censor")
    assert not censor_decision("female", "female", "censor", override="keep")


def test_compose_mask_censors_only_the_owner():
    skin = np.zeros((100, 200), np.uint8)
    skin[40:60, 20:60] = 255  # inside person 1 (left)
    skin[40:60, 140:180] = 255  # inside person 2 (right)
    persons = np.array([[1, 0.05, 0.1, 0.4, 0.9], [2, 0.6, 0.1, 0.95, 0.9]], np.float32)
    regions = np.array([[2, 0.75, 0.3, 0.85, 0.5, 1.0]], np.float32)  # sensitive region owned by #2
    rec = FrameRecord(persons, regions)
    m = compose_mask(skin, rec, {1: True, 2: False}, unassigned_censor=True)
    assert m[50, 40] == 255 and m[50, 160] == 0
    m2 = compose_mask(skin, rec, {1: False, 2: True}, unassigned_censor=False)
    assert m2[50, 40] == 0 and m2[50, 160] == 255 and m2[40, 160] > 0


def test_compose_mask_outstretched_arm_follows_its_body():
    skin = np.zeros((100, 200), np.uint8)
    skin[45:50, 10:100] = 255  # one arm that starts inside person 1's box and extends far outside it
    persons = np.array([[1, 0.0, 0.1, 0.25, 0.9]], np.float32)
    m = compose_mask(skin, FrameRecord(persons, np.zeros((0, 6), np.float32)), {1: False}, unassigned_censor=True)
    assert m.max() == 0  # whole component belongs to the (uncensored) person, not "unassigned"


def test_tiny_unassigned_blobs_are_ignored():
    skin = np.zeros((100, 200), np.uint8)
    skin[2:5, 150:154] = 255  # 12 px speck far from anyone (e.g. a lamp)
    skin[40:60, 20:60] = 255  # a real arm inside person 1
    rec = FrameRecord(np.array([[1, 0.05, 0.1, 0.4, 0.9]], np.float32), np.zeros((0, 6), np.float32))
    m = compose_mask(skin, rec, {1: True}, unassigned_censor=True)
    assert m[50, 40] == 255 and m[3, 152] == 0
    big = np.zeros((100, 200), np.uint8)
    big[10:40, 120:160] = 255  # a large unattributed blob (an undetected person) stays censored
    assert compose_mask(big, FrameRecord(np.zeros((0, 5), np.float32), np.zeros((0, 6), np.float32)), {}, True)[20, 140] == 255


def test_unassigned_skin_follows_fallback():
    skin = np.full((20, 20), 255, np.uint8)
    empty = FrameRecord(np.zeros((0, 5), np.float32), np.zeros((0, 6), np.float32))
    assert compose_mask(skin, empty, {}, unassigned_censor=True).all()
    assert not compose_mask(skin, empty, {}, unassigned_censor=False).any()


# ── models on real footage ─────────────────────────────────────────────────

@pytest.fixture(scope="module")
def engine():
    return Engine()


@pytest.fixture(scope="module")
def woman_and_man(engine):
    info = media.probe(WOMAN_AND_MAN)
    a = analyze(engine, info, CensorSettings(), Control(), Progress(), lambda _: None, "pytest_wm")
    yield a
    a.close()


def _people_by_side(a):
    out = {}
    for t in a.people.values():
        if t.frames < a.total // 2:
            continue
        aw = media.scaled_size(a.info.width, a.info.height, ANALYSIS_MAX_SIDE)[0]
        cx = (t.box[0] + t.box[2]) / 2 / aw
        out["left" if cx < 0.5 else "right"] = t
    return out


def test_woman_classified_female_man_male(woman_and_man):
    sides = _people_by_side(woman_and_man)
    assert set(sides) == {"left", "right"}
    assert sides["left"].gender.label(0.7) == "female"
    assert sides["right"].gender.label(0.7) == "male"


def test_render_censors_woman_not_man(woman_and_man, tmp_path):
    a = woman_and_man
    settings = CensorSettings()
    out = tmp_path / "wm.mp4"
    res = render(a, settings, {}, out, Control(), Progress(), lambda _: None)
    assert res["censored_frames"] >= a.total * 0.9
    info = media.probe(out)
    assert info.has_audio and (info.width, info.height) == (a.info.width, a.info.height)
    # compare halves: blue pixels must appear on the woman's (left) side only
    blue_left = blue_right = 0
    reader = media.FrameReader(info, info.width, info.height)
    for i, f in enumerate(reader):
        if i % 10:
            continue
        b, g, r = f[..., 0].astype(int), f[..., 1].astype(int), f[..., 2].astype(int)
        blue = (b > 200) & (r < 80) & (g < 120)
        half = info.width // 2
        blue_left += int(blue[:, :half].sum())
        blue_right += int(blue[:, half:].sum())
    reader.close()
    assert blue_left > 2000
    assert blue_right < blue_left * 0.02


def test_override_inverts_decision(woman_and_man):
    a = woman_and_man
    sides = _people_by_side(a)
    s = CensorSettings()
    d = decisions_for(a, s, {sides["left"].tid: "keep", sides["right"].tid: "censor"})
    assert d[sides["left"].tid] is False and d[sides["right"].tid] is True
    d_all = decisions_for(a, CensorSettings(target="everyone"), {})
    assert all(d_all.values())


def test_men_only_clip_is_not_censored(engine, tmp_path):
    info = media.probe(MEN)
    a = analyze(engine, info, CensorSettings(), Control(), Progress(), lambda _: None, "pytest_men")
    try:
        assert len([t for t in a.people.values() if t.frames > a.total // 2]) >= 3
        res = render(a, CensorSettings(), {}, tmp_path / "men.mp4", Control(), Progress(), lambda _: None)
        assert res["censored_frames"] <= a.total * 0.1
    finally:
        a.close()
