"""Package the skin / clothes / hair segmenter used as the clothes veto (Android: models/onnx).

Source: Kazuhito Takahashi, Skin-Clothes-Hair-Segmentation-using-SMP (MIT),
https://github.com/Kazuhito00/Skin-Clothes-Hair-Segmentation-using-SMP
file 02.model/DeepLabV3Plus(timm-mobilenetv3_small_100)_1366_2.16M_0.8297/best_model_simplifier.onnx
(sha256 ccdbbc56bb6b6fc665e2d51dfda31c58707229e271b3753aa2b446c720d73324).

Input  x.1 [1,3,512,512]: RGB / 255, normalised with the ImageNet mean (0.485, 0.456, 0.406) and std
                          (0.229, 0.224, 0.225), NCHW.
Output [1,3,512,512]:     sigmoid probabilities, channels skin, clothes, hair.

The graph is unchanged; only the weights are stored as float16 with a Cast back to float32 (half the file
size, float32 arithmetic). The script checks the result against the original on random inputs.

Usage:
  git clone https://github.com/Kazuhito00/Skin-Clothes-Hair-Segmentation-using-SMP kz
  pip install onnx onnxruntime numpy
  python build_skin_clothes_hair.py "kz/02.model/DeepLabV3Plus(timm-mobilenetv3_small_100)_1366_2.16M_0.8297/best_model_simplifier.onnx" \
      models/onnx/skin_clothes_hair_mnv3s_512.onnx
"""
import hashlib
import shutil
import sys

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper

SHA256 = "ccdbbc56bb6b6fc665e2d51dfda31c58707229e271b3753aa2b446c720d73324"


def fp16_storage(path):
    """Weights of 1024+ values stored as float16, cast back to float32 on load (same as build_mobilesam.py)."""
    m = onnx.load(path)
    g = m.graph
    inits, casts = [], []
    for init in list(g.initializer):
        if init.data_type == TensorProto.FLOAT and np.prod(init.dims) >= 1024:
            inits.append(numpy_helper.from_array(numpy_helper.to_array(init).astype(np.float16), init.name + "__fp16"))
            casts.append(helper.make_node("Cast", [init.name + "__fp16"], [init.name], to=TensorProto.FLOAT))
            g.initializer.remove(init)
    g.initializer.extend(inits)
    for c in reversed(casts):
        g.node.insert(0, c)
    onnx.checker.check_model(m)
    onnx.save(m, path)


def main(src, out):
    digest = hashlib.sha256(open(src, "rb").read()).hexdigest()
    if digest != SHA256:
        print(f"warning: {src} has sha256 {digest}, expected {SHA256}")
    shutil.copyfile(src, out)
    fp16_storage(out)
    a = ort.InferenceSession(src, providers=["CPUExecutionProvider"])
    b = ort.InferenceSession(out, providers=["CPUExecutionProvider"])
    rnd = np.random.default_rng(1)
    worst = 0.0
    for _ in range(3):
        x = ((rnd.random((1, 3, 512, 512), np.float32) - np.array([0.485, 0.456, 0.406], np.float32)[:, None, None])
             / np.array([0.229, 0.224, 0.225], np.float32)[:, None, None]).astype(np.float32)
        ya = a.run(None, {a.get_inputs()[0].name: x})[0]
        yb = b.run(None, {b.get_inputs()[0].name: x})[0]
        worst = max(worst, float(np.abs(ya - yb).max()))
    print(f"{out}: max abs diff vs original {worst:.2e}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
