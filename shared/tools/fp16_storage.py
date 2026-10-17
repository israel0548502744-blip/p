"""Store an ONNX model's large float32 weights as float16 (cast back to float32 inside the graph at load).

Halves the file (and the APK) at no cost when the weights came from a float16 model, and at ~1e-4 max abs
output difference otherwise. Same transform as build_mobilesam.py's; the computation stays float32.

    python shared/tools/fp16_storage.py models/onnx/gender_faceres.onnx [more.onnx ...]
"""

import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


def fp16_storage(path: str) -> None:
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


if __name__ == "__main__":
    for p in sys.argv[1:]:
        fp16_storage(p)
