# Test fixtures

Short clips cut and re-encoded from Intel's IoT DevKit sample videos
(https://github.com/intel-iot-devkit/sample-videos), licensed
**CC-BY-4.0** © Intel Corporation.

| File | Source | Content | Ground truth |
|---|---|---|---|
| `woman_and_man.mp4` | head-pose-face-detection-female-and-male.mp4 (15–20 s) | a woman (left, sleeveless) and a man (right, t-shirt) | woman → censored, man → not censored |
| `men_classroom.mp4` | classroom.mp4 (0–4 s), scaled to 960 px, no audio | four men in a classroom | nobody censored |
| `tiny_portrait.mp4` | woman_and_man.mp4 (0–1.2 s), portrait crop, 200×112 coded + 90° rotation, 17 fps, no audio | tiny phone clip (encoder minimum-size regression) | renders without a codec error |

Used by the desktop tests (`desktop/backend/tests`) and intended for Android
instrumented tests.
