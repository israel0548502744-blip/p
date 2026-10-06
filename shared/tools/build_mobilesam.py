"""Build the MobileSAM person-outline models used by BlueShield (Android + desktop).

MobileSAM (https://github.com/ChaoningZhang/MobileSAM, Apache-2.0, weights `weights/mobile_sam.pt`) is
exported as
  mobilesam_encoder_<S>.onnx : image [1,3,S,S] RGB 0..255, letterboxed top-left, padding = SAM pixel mean
                               (123.675, 116.28, 103.53) -> embeddings [1,256,S/16,S/16], image_pe (same shape)
  mobilesam_decoder.onnx     : embeddings, image_pe, boxes [N,4] (x1,y1,x2,y2 / S) -> logits [N,1,S/4,S/4], iou [N,1]
for S = 512 (video) and 1024 (photos). The TinyViT encoder is rebuilt for S (its feature reshape is patched
from a fixed 64x64 to S/16). Weights are stored as float16 with a Cast back to float32, so the files are half
the size while the arithmetic stays float32 (masks identical to the float32 export, IoU 1.000).

Usage:
  git clone https://github.com/ChaoningZhang/MobileSAM   (weights are in the repo)
  pip install torch timm onnx onnxruntime
  python build_mobilesam.py MobileSAM out_dir
"""
import sys

import numpy as np
import onnx
import torch
from onnx import TensorProto, helper, numpy_helper


def build(repo, size):
    sys.path.insert(0, repo)
    import mobile_sam.modeling.tiny_vit_sam as tv
    from mobile_sam.modeling import MaskDecoder, PromptEncoder, Sam, TinyViT, TwoWayTransformer
    src = open(tv.__file__).read()
    if "x.view(B, 64, 64, C)" in src:  # fixed 1024-px input upstream
        open(tv.__file__, "w").write(src.replace("x.view(B, 64, 64, C)", "x.view(B, self.img_size // 16, self.img_size // 16, C)"))
        raise SystemExit("patched TinyViT for variable input size - run again")
    sam = Sam(
        image_encoder=TinyViT(img_size=size, in_chans=3, num_classes=1000, embed_dims=[64, 128, 160, 320], depths=[2, 2, 6, 2],
                              num_heads=[2, 4, 5, 10], window_sizes=[7, 7, 14, 7], mlp_ratio=4., drop_rate=0., drop_path_rate=0.0,
                              use_checkpoint=False, mbconv_expand_ratio=4.0, local_conv_size=3, layer_lr_decay=0.8),
        prompt_encoder=PromptEncoder(embed_dim=256, image_embedding_size=(size // 16, size // 16), input_image_size=(size, size), mask_in_chans=16),
        mask_decoder=MaskDecoder(num_multimask_outputs=3, transformer=TwoWayTransformer(depth=2, embedding_dim=256, mlp_dim=2048, num_heads=8),
                                 transformer_dim=256, iou_head_depth=3, iou_head_hidden_dim=256),
        pixel_mean=[123.675, 116.28, 103.53], pixel_std=[58.395, 57.12, 57.375])
    sam.load_state_dict(torch.load(f"{repo}/weights/mobile_sam.pt", map_location="cpu"), strict=False)
    return sam.eval()


class Enc(torch.nn.Module):
    def __init__(self, sam):
        super().__init__()
        self.sam = sam
        self.register_buffer("mean", torch.tensor([123.675, 116.28, 103.53]).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor([58.395, 57.12, 57.375]).view(1, 3, 1, 1))

    def forward(self, image):
        return self.sam.image_encoder((image - self.mean) / self.std), self.sam.prompt_encoder.get_dense_pe()


class Dec(torch.nn.Module):
    def __init__(self, sam):
        super().__init__()
        self.sam = sam

    def forward(self, embeddings, image_pe, boxes):
        pe = self.sam.prompt_encoder
        e = pe.pe_layer._pe_encoding(boxes.reshape(-1, 2, 2))  # box corners, already 0..1
        e[:, 0, :] += pe.point_embeddings[2].weight
        e[:, 1, :] += pe.point_embeddings[3].weight
        dense = pe.no_mask_embed.weight.reshape(1, -1, 1, 1).expand(boxes.shape[0], -1, embeddings.shape[2], embeddings.shape[3])
        masks, iou = self.sam.mask_decoder.predict_masks(image_embeddings=embeddings, image_pe=image_pe,
                                                         sparse_prompt_embeddings=e, dense_prompt_embeddings=dense)
        return masks[:, 0:1], iou[:, 0:1]


def fp16_storage(path):
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


def main(repo, out):
    for size in (512, 1024):
        path = f"{out}/mobilesam_encoder_{size}.onnx"
        torch.onnx.export(Enc(build(repo, size)), torch.zeros(1, 3, size, size), path, input_names=["image"],
                          output_names=["embeddings", "image_pe"], opset_version=17, dynamo=False)
        fp16_storage(path)
    path = f"{out}/mobilesam_decoder.onnx"
    z = torch.zeros(1, 256, 64, 64)
    torch.onnx.export(Dec(build(repo, 1024)), (z, z, torch.tensor([[0.1, 0.1, 0.5, 0.9]])), path,
                      input_names=["embeddings", "image_pe", "boxes"], output_names=["logits", "iou"],
                      dynamic_axes={"embeddings": {2: "h", 3: "w"}, "image_pe": {2: "h", 3: "w"}, "boxes": {0: "n"},
                                    "logits": {0: "n", 2: "mh", 3: "mw"}, "iou": {0: "n"}}, opset_version=17, dynamo=False)
    fp16_storage(path)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
