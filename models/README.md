# Committed model files

| File | What | Origin | License |
|---|---|---|---|
| `gender_faceres_fp16.tflite` | Face gender classifier (input 224×224 RGB 0–255, output P(male)) | "FaceRes" age/gender MobileNet by Andrey Savchenko — [HSE_FaceRec_tf](https://github.com/HSE-asavchenko/HSE_FaceRec_tf) — as packaged in the MIT-licensed npm package [`@vladmandic/human-models`](https://www.npmjs.com/package/@vladmandic/human-models) (`models/faceres.json`), converted to TFLite float16 with `shared/tools/convert_tfjs_to_tflite.py` | MIT (npm package). Check the upstream HSE_FaceRec_tf repository's terms before commercial redistribution. |

All other models are downloaded from their original sources (see `shared/models.json`).
