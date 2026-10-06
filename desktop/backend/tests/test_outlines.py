"""Per-person outlines (ownership labels) and colour-fitted skin edges."""

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from blueshield import outlines  # noqa: E402
from blueshield.pipeline import FrameRecord, compose_mask  # noqa: E402


def test_owner_is_highest_outline_and_clear_background_is_clipped():
    a = np.array([[5.0, -1.0, -5.0]], np.float32)
    b = np.array([[1.0, 3.0, -4.0]], np.float32)
    assert outlines.owners([a, b], 0.0, -2.0).tolist() == [[1, 2, -1]]
    # someone without an outline yet: never clip (their skin may be anywhere)
    assert outlines.owners([a, None], 0.0, -2.0).tolist() == [[1, 0, 0]]


def test_stored_owner_label_beats_box_geometry():
    # two overlapping boxes; the skin blob sits nearer the man's box centre but its stored label says woman (#1)
    skin = np.zeros((100, 100), np.uint8)
    skin[40:60, 30:45] = 1
    persons = np.array([[7, 0.25, 0.0, 0.95, 1.0], [9, 0.0, 0.0, 0.55, 1.0]], np.float32)
    rec = FrameRecord(persons, np.zeros((0, 6), np.float32))
    out = compose_mask(skin, rec, {7: True, 9: False}, False)
    assert out[50, 37] == 255
    unknown = np.where(skin > 0, 255, 0).astype(np.uint8)  # no outline: nearest box centre (the man) wins
    assert compose_mask(unknown, rec, {7: True, 9: False}, False)[50, 37] == 0


def test_recolour_moves_a_blobby_boundary_onto_the_arm():
    rgb = np.full((120, 120, 3), 235, np.uint8)  # white dress
    rgb[:, 40:80] = (205, 150, 125)  # bare arm
    p = np.zeros((120, 120), np.float32)
    p[:, 30:90] = 1.0  # coarse model blob, 10 px too wide on both sides
    q = outlines.recolour(p, rgb, 12)
    assert q[60, 60] > 0.9 and q[60, 35] < 0.5 and q[60, 85] < 0.5
