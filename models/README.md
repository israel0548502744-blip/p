# Committed model files

## `gender_faceres_fp16.tflite` (desktop)
Face gender classifier: input 224×224 RGB (0–255), output P(male).
"FaceRes" age/gender MobileNet by Andrey Savchenko — [HSE_FaceRec_tf](https://github.com/HSE-asavchenko/HSE_FaceRec_tf) —
as packaged in the MIT-licensed npm package [`@vladmandic/human-models`](https://www.npmjs.com/package/@vladmandic/human-models)
(`models/faceres.json`), converted to TFLite float16 with `shared/tools/convert_tfjs_to_tflite.py`.
License: MIT (npm package). Check the upstream HSE_FaceRec_tf repository's terms before commercial redistribution.

## `onnx/` (Android — copied into the APK's assets by Gradle)
| File | Converted from | Tool | Check vs. original |
|---|---|---|---|
| `selfie_multiclass_256x256.onnx` | MediaPipe Selfie Multiclass (TFLite, Apache-2.0) | `tf2onnx --opset 17` | max abs diff 5e-5 |
| `efficientdet_lite0.onnx` | MediaPipe EfficientDet-Lite0 float32 (TFLite, Apache-2.0) | `tf2onnx` + `shared/tools/fix_onnx_resize.py` | max abs diff 8e-6 |
| `blaze_face_short_range.onnx` | MediaPipe BlazeFace short range (TFLite, Apache-2.0) | `tf2onnx` | max abs diff 3e-4 (logits) |
| `gender_faceres.onnx` | `gender_faceres_fp16.tflite` above | `tf2onnx` | max abs diff 1e-7 |
| `nudenet_320n.onnx` | NudeNet v3 320n (MIT), unchanged | — | — |

Reproduce: `pip install tensorflow tf2onnx onnx`, then
`python -m tf2onnx.convert --tflite <model>.tflite --output <model>.onnx --opset 17`
(and for EfficientDet: `python shared/tools/fix_onnx_resize.py in.onnx out.onnx`).
