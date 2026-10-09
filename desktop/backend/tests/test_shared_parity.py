"""Desktop constants must match shared/pipeline.json (the Android app reads that file)."""

import json
import sys
from dataclasses import asdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from blueshield import gender  # noqa: E402
from blueshield.detectors import AGGRESSIVE_LABELS, SENSITIVE_LABELS  # noqa: E402
from blueshield.pipeline import SPEED_PRESETS, CensorSettings  # noqa: E402

SPEC = json.loads((Path(__file__).resolve().parents[3] / "shared" / "pipeline.json").read_text())


def test_defaults_match():
    d = asdict(CensorSettings())
    for k, v in SPEC["defaults"].items():
        assert d[k] == v, k


def test_presets_and_labels_match():
    assert SPEED_PRESETS == SPEC["speed_presets"]
    assert SENSITIVE_LABELS == set(SPEC["sensitive_labels"])
    assert AGGRESSIVE_LABELS == SENSITIVE_LABELS | set(SPEC["aggressive_extra_labels"])


def test_mask_constants_match():
    from blueshield import config, pipeline
    assert pipeline.UNASSIGNED_MIN_AREA == SPEC["unassigned_min_area"]
    assert config.ANALYSIS_MAX_SIDE == SPEC["analysis_max_side"]
    assert config.MASK_MAX_SIDE == SPEC["mask_max_side"]
    from blueshield import detectors
    assert (detectors.ROI_MAX_FRAME_RATIO, detectors.ROI_FULL_EVERY_DET) == (
        SPEC["roi"]["max_frame_ratio"], SPEC["roi"]["full_every_det"])
    assert pipeline.OWNER_REACH == SPEC["owner_reach"]
    assert pipeline.UNASSIGNED_MIN_AREA_WITH_PEOPLE == SPEC["unassigned_min_area_with_people"]
    assert pipeline.PERSON_GATE == SPEC["thresholds"]["person_gate"]
    assert pipeline.MIN_BODY_AREA == SPEC["thresholds"]["min_body_area"]
    assert (pipeline.REFINE_RADIUS, pipeline.REFINE_EPS, pipeline.REFINE_COLOR_BAND, pipeline.REFINE_COLOR_KEEP) == (
        SPEC["refine"]["radius"], SPEC["refine"]["eps"], SPEC["refine"]["color_band"], SPEC["refine"]["color_keep"])
    pm = SPEC["person_masks"]
    assert (pipeline.PM_VIDEO_SIZE, pipeline.PM_PHOTO_SIZE, pipeline.PM_EVERY_SEC, list(pipeline.PM_SPEEDS),
            pipeline.PM_OWNER_MIN_LOGIT, pipeline.PM_CLIP_LOGIT) == (
        pm["video_size"], pm["photo_size"], pm["every_sec"], pm["speeds"], pm["owner_min_logit"], pm["clip_logit"])
    t = SPEC["tracking"]
    assert (pipeline.FUSER_MEMORY, pipeline.FUSER_LIFT, pipeline.FUSER_LIFT_AGGRESSIVE) == (
        t["fuser_memory"], t["fuser_lift"], t["fuser_lift_aggressive"])


def test_gender_constants_match():
    g = SPEC["gender"]
    assert gender.MAX_LOGIT == g["max_logit"] and gender.MIN_VOTES == g["min_votes"] and gender.MIN_WEIGHT == g["min_weight"]
    assert (gender.MALE_MIN_CONFIDENCE, gender.CHILD_MAX_AGE, gender.MIN_AGE_VOTES) == (
        g["male_min_confidence"], g["child_max_age"], g["min_age_votes"])
    from blueshield.detectors import FACE_EXCLUSION
    assert FACE_EXCLUSION == SPEC["thresholds"]["face_exclusion"]
    est = gender.GenderEstimate()
    est.add(0.2)  # one vote: logit = factor * ln(0.8/0.2)
    import math
    assert abs(est.logit - g["vote_factor"] * math.log(4)) < 1e-6


def test_neckline_constants_match():
    from blueshield import pipeline as p
    n = SPEC["neckline"]
    assert (p.NECK_BAND_HALF_WIDTH, p.NECK_BAND_HEIGHT, p.NECK_PROBE_HALF_WIDTH, p.NECK_PROBE_HEIGHT, p.CLEAVAGE_MIN_FILL,
            p.CLEAVAGE_START, p.SPECK_PERSON_FRAC, p.SPECK_FRAME_FRAC) == (
        n["band_half_width"], n["band_height"], n["probe_half_width"], n["probe_height"], n["cleavage_min_fill"],
        n["cleavage_start"], n["speck_person_frac"], n["speck_frame_frac"])



def test_clothes_veto_constants_match():
    from blueshield import pipeline as p
    v = SPEC["clothes_veto"]
    assert (p.VETO_ENABLED, p.VETO_CLOTHES_MIN, p.VETO_SKIN_MAX, p.VETO_VIDEO_EVERY, p.VETO_BLOB_SHARE, p.VETO_BLOB_RIM) == (
        v["enabled"], v["clothes_min"], v["skin_max"], v["video_every"], v["blob_share"], v["blob_rim"])
    assert v["tiles"] is False  # the desktop runs the clothes model on the whole-person crops only
    assert p.ROI_MIN_SIDE == SPEC["roi"]["min_side_px"]
