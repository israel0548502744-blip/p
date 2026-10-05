"""Fix tf2onnx Resize scales whose float rounding loses an output pixel (3*1.6666666 -> 4 instead of 5)."""
import sys, numpy as np, onnx
from onnx import numpy_helper
m = onnx.load(sys.argv[1])
inits = {i.name: i for i in m.graph.initializer}
fixed = 0
for n in m.graph.node:
    if n.op_type == "Resize" and len(n.input) >= 3 and n.input[2] in inits:
        arr = numpy_helper.to_array(inits[n.input[2]]).copy()
        if np.any(np.abs(arr - np.round(arr)) > 1e-6):
            arr = np.where(np.abs(arr - np.round(arr)) > 1e-6, arr + 1e-3, arr).astype(np.float32)
            inits[n.input[2]].CopyFrom(numpy_helper.from_array(arr, n.input[2]))
            fixed += 1
onnx.save(m, sys.argv[2])
print("patched", fixed, "Resize node(s)")
