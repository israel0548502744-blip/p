# Extra models (fetched by `.github/workflows/fetch-models.yml`)

The development sandbox can't reach some model hosts, so this workflow downloads them on GitHub's runners,
converts them to ONNX and publishes them on the `extra-models` branch.

| Model | Source | Licence | Use |
|---|---|---|---|
| `pulc_person_attribute.onnx` | PaddleClas PULC `person_attribute_infer.tar` (PP-LCNet x1.0), converted with paddle2onnx | Code and weights Apache-2.0; trained on PA-100K (CC BY 4.0) | Gender and front/side/back from the whole body — for people whose face isn't visible |

Input `x`: 1×3×256×192 RGB, scaled to 0..1 and normalised with ImageNet mean/std. Output: 26 attribute logits
(PaddleClas applies a sigmoid); index 22 = Female, 23/24/25 = Front/Side/Back, 2/3 = Short/Long sleeve,
12 = Shorts, 13 = Skirt&Dress (order from `ppcls/data/postprocess/attr_rec.py`).

## Evaluation (not used in the app)

Checked on clips with known people (YOLOX boxes, one crop every 20 frames): good on dancing women
(mostly > 0.9 female), but the woman + man fixture's **man** came out 0.82–0.89 female, two of the four men in
the classroom fixture 0.8–0.9 female, and in a crowded graduation photo two women seen from the side came out
0.16–0.17 female (i.e. male). PA-100K is low-resolution surveillance footage; on close, indoor, crowded crops
the model is not reliable, and a wrong "male" would leave a woman uncensored — so it is not wired in.
