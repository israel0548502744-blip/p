"""Convert a TF.js graph-model (model.json + .bin shards) to TFLite.

Used once to produce models/gender_faceres_fp16.tflite from the MIT-licensed
`@vladmandic/human-models` npm package (models/faceres.json):

    pip install tensorflow
    npm pack @vladmandic/human-models && tar xzf vladmandic-human-models-*.tgz
    python convert_tfjs_to_tflite.py package/models/faceres.json gender_faceres_fp16.tflite \
        "gender_pred/Sigmoid:0" '[{"size":"224"},{"size":"224"},{"size":"3"}]' --fp16

TF.js fused ops (_FusedMatMul, _FusedConv2D, FusedDepthwiseConv2dNative) are
un-fused into standard ops first, because the TFLite converter can't read them.
"""
import json, sys, os
import numpy as np
import tensorflow as tf
from google.protobuf import json_format
from tensorflow.core.framework import graph_pb2

src, out = sys.argv[1], sys.argv[2]
d = json.load(open(src))
topo = d["modelTopology"]
gd = graph_pb2.GraphDef()
json_format.ParseDict({"node": topo["node"], "versions": topo.get("versions", {}), "library": topo.get("library", {})}, gd, ignore_unknown_fields=True)
base = os.path.dirname(src)
weights = {}
for group in d["weightsManifest"]:
    buf = b"".join(open(os.path.join(base, p), "rb").read() for p in group["paths"])
    off = 0
    for w in group["weights"]:
        shape = w["shape"]; n = int(np.prod(shape)) if shape else 1
        q = w.get("quantization")
        if q:
            qdt = {"uint8": np.uint8, "uint16": np.uint16, "float16": np.float16}[q["dtype"]]
            raw = np.frombuffer(buf, qdt, n, off); off += n * np.dtype(qdt).itemsize
            arr = raw.astype(np.float32) if q["dtype"] == "float16" else raw.astype(np.float32) * q["scale"] + q["min"]
        else:
            dt = {"float32": np.float32, "int32": np.int32}[w["dtype"]]
            arr = np.frombuffer(buf, dt, n, off); off += n * 4
        weights[w["name"]] = arr.reshape(shape)
for node in gd.node:
    if node.name in weights and node.op == "Const":
        node.attr["value"].tensor.CopyFrom(tf.make_tensor_proto(weights[node.name]))
        node.attr["dtype"].type = node.attr["value"].tensor.dtype
# Un-fuse TF.js fused ops (not understood by the TFLite converter).
from tensorflow.core.framework import node_def_pb2
new_nodes = []
for node in gd.node:
    if node.op not in ("_FusedMatMul", "_FusedConv2D", "FusedDepthwiseConv2dNative"):
        new_nodes.append(node); continue
    fused = [x.decode() for x in node.attr["fused_ops"].list.s]
    base = node_def_pb2.NodeDef(); base.name = node.name + "/base"
    base.op = {"_FusedMatMul": "MatMul", "_FusedConv2D": "Conv2D", "FusedDepthwiseConv2dNative": "DepthwiseConv2dNative"}[node.op]
    base.input.extend(node.input[:2])
    for k, v in node.attr.items():
        if k in ("T", "transpose_a", "transpose_b", "strides", "padding", "data_format", "dilations", "explicit_paddings"):
            base.attr[k].CopyFrom(v)
    new_nodes.append(base); prev = base.name
    for i, f in enumerate(fused):
        n = node_def_pb2.NodeDef(); last = i == len(fused) - 1
        n.name = node.name if last else f"{node.name}/{f}"
        n.attr["T"].CopyFrom(node.attr["T"])
        if f == "BiasAdd":
            n.op = "BiasAdd"; n.input.extend([prev, node.input[2]])
        elif f in ("Relu", "Relu6", "Elu", "Sigmoid", "Tanh"):
            n.op = f; n.input.append(prev)
        else:
            raise SystemExit("unsupported fused op " + f)
        new_nodes.append(n); prev = n.name
del gd.node[:]
gd.node.extend(new_nodes)
inputs = [n.name for n in gd.node if n.op == "Placeholder"]
outs = sys.argv[3].split(",")
print("inputs", inputs, "outputs", outs)

def imported(*args):
    tf.compat.v1.import_graph_def(gd, name="")
wrapped = tf.compat.v1.wrap_function(imported, [])
g = wrapped.graph
fn = wrapped.prune([g.as_graph_element(i + ":0") for i in inputs], [g.as_graph_element(o) for o in outs])
shape = [1, *[int(x["size"]) for x in json.loads(sys.argv[4])]] if len(sys.argv) > 4 else None
conc = tf.function(lambda x: fn(x)).get_concrete_function(tf.TensorSpec(shape, tf.float32))
conv = tf.lite.TFLiteConverter.from_concrete_functions([conc], wrapped)
if "--fp16" in sys.argv:
    conv.optimizations = [tf.lite.Optimize.DEFAULT]; conv.target_spec.supported_types = [tf.float16]
open(out, "wb").write(conv.convert())
print("wrote", out, os.path.getsize(out))
