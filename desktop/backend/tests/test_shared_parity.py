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


def test_gender_constants_match():
    g = SPEC["gender"]
    assert gender.MAX_LOGIT == g["max_logit"] and gender.MIN_VOTES == g["min_votes"]
    est = gender.GenderEstimate()
    est.add(0.2)  # one vote: logit = factor * ln(0.8/0.2)
    import math
    assert abs(est.logit - g["vote_factor"] * math.log(4)) < 1e-6
