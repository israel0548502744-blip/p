# Committed model files

## `gender_faceres_fp16.tflite` (desktop)
Face gender classifier: input 224×224 RGB (0–255), output P(male).
"FaceRes" age/gender MobileNet by Andrey Savchenko — [HSE_FaceRec_tf](https://github.com/HSE-asavchenko/HSE_FaceRec_tf) —
as packaged in the MIT-licensed npm package [`@vladmandic/human-models`](https://www.npmjs.com/package/@vladmandic/human-models)
(`models/faceres.json`), converted to TFLite float16 with `shared/tools/convert_tfjs_to_tflite.py`.
License: MIT (npm package). Check the upstream HSE_FaceRec_tf repository's terms before commercial redistribution.

## `faceapi_agegender.tflite` (desktop) / `onnx/faceapi_agegender.onnx` (Android)
Second face model: P(male) and age, input 112×112 RGB (0–255), from an eye-aligned face crop.
face-api.js `AgeGenderNet` (TinyXception, trained on UTKFace — which includes children) by Vincent Mühler,
as packaged in the MIT-licensed npm package [`@vladmandic/face-api`](https://www.npmjs.com/package/@vladmandic/face-api)
(`model/age_gender_model.*`). The network is rebuilt from those weights with
`shared/tools/build_faceapi_agegender.py` (TensorFlow → TFLite, and SavedModel → `tf2onnx --opset 17`;
ONNX vs. TensorFlow max abs diff 1e-5). License: MIT.
Used together with FaceRes: P(male) is the mean of both models' log-odds (much steadier than either alone,
e.g. on older women), and the age decides "adult woman" vs. "girl".

## `onnx/` (Android — copied into the APK's assets by Gradle)
| File | Converted from | Tool | Check vs. original |
|---|---|---|---|
| `selfie_multiclass_256x256.onnx` | MediaPipe Selfie Multiclass (TFLite, Apache-2.0) | `tf2onnx --opset 17` | max abs diff 5e-5 |
| `yolox_tiny.onnx` | [YOLOX](https://github.com/Megvii-BaseDetection/YOLOX) release 0.1.1rc0 `yolox_tiny.onnx` (Apache-2.0), person class only | weights stored fp16 (`fp16_storage` in `shared/tools/build_mobilesam.py`) | max score diff 9e-6 |
| `blaze_face_short_range.onnx` | MediaPipe BlazeFace short range (TFLite, Apache-2.0) | `tf2onnx` | max abs diff 3e-4 (logits) |
| `gender_faceres.onnx` | `gender_faceres_fp16.tflite` above | `tf2onnx` | max abs diff 1e-7 |
| `nudenet_320n.onnx` | NudeNet v3 320n (MIT), unchanged | — | — |
| `faceapi_agegender.onnx` | face-api.js AgeGenderNet (see above) | `tf2onnx --opset 17` (SavedModel) | max abs diff 1e-5 |
| `mobilesam_encoder_512.onnx`, `mobilesam_encoder_1024.onnx`, `mobilesam_decoder.onnx` | [MobileSAM](https://github.com/ChaoningZhang/MobileSAM) `weights/mobile_sam.pt` (Apache-2.0) | `shared/tools/build_mobilesam.py` (torch.onnx, opset 17, weights stored fp16) | masks identical to PyTorch (IoU 1.000) |

Reproduce: `pip install tensorflow tf2onnx onnx`, then
`python -m tf2onnx.convert --tflite <model>.tflite --output <model>.onnx --opset 17`
(and for EfficientDet: `python shared/tools/fix_onnx_resize.py in.onnx out.onnx`).
