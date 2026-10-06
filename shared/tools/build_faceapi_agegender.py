"""Rebuild face-api.js AgeGenderNet (TinyXception) from its weights and export TFLite + a SavedModel.

    npm pack @vladmandic/face-api@1.7.15 && mkdir faceapi && tar xzf vladmandic-face-api-*.tgz -C faceapi
    python build_faceapi_agegender.py            # → faceapi_agegender.tflite, faceapi_sm/
    python -m tf2onnx.convert --saved-model faceapi_sm --output faceapi_agegender.onnx --opset 17

Architecture and preprocessing follow face-api.js (TinyXception, 2 middle blocks, input RGB 0–255 minus the
mean [122.782, 117.001, 104.298], divided by 255; outputs age (regression) and softmax [male, female]).
"""
import json, numpy as np, tensorflow as tf, sys
base='faceapi/package/model/'
man=json.load(open(base+'age_gender_model-weights_manifest.json'))
W={}
for g in man:
    buf=b''.join(open(base+p,'rb').read() for p in g['paths']); off=0
    for w in g['weights']:
        n=int(np.prod(w['shape'])); q=w.get('quantization')
        if q:
            raw=np.frombuffer(buf,np.uint8,n,off); off+=n
            arr=raw.astype(np.float32)*q['scale']+q['min']
        else:
            arr=np.frombuffer(buf,np.float32,n,off); off+=n*4
        W[w['name']]=tf.constant(arr.reshape(w['shape']))
def conv(x,p,s): return tf.nn.conv2d(x,W[p+'/filters'],s,'SAME')+W[p+'/bias']
def sep(x,p): return tf.nn.separable_conv2d(x,W[p+'/depthwise_filter'],W[p+'/pointwise_filter'],[1,1,1,1],'SAME')+W[p+'/bias']
def red(x,p,act=True):
    r=tf.nn.relu(x) if act else x
    r=sep(r,p+'/separable_conv0'); r=sep(tf.nn.relu(r),p+'/separable_conv1')
    r=tf.nn.max_pool2d(r,3,2,'SAME')
    return r+conv(x,p+'/expansion_conv',2)
def main(x,p):
    e=sep(tf.nn.relu(x),p+'/separable_conv0'); e=sep(tf.nn.relu(e),p+'/separable_conv1'); e=sep(tf.nn.relu(e),p+'/separable_conv2'); return e+x
@tf.function(input_signature=[tf.TensorSpec([1,112,112,3],tf.float32)])
def net(x):  # x: RGB 0..255
    x=(x-tf.constant([122.782,117.001,104.298]))/255.
    s=tf.nn.relu(conv(x,'entry_flow/conv_in',2))
    s=red(s,'entry_flow/reduction_block_0',False); s=red(s,'entry_flow/reduction_block_1')
    s=main(s,'middle_flow/main_block_0'); s=main(s,'middle_flow/main_block_1')
    s=red(s,'exit_flow/reduction_block')
    s=tf.nn.relu(sep(s,'exit_flow/separable_conv'))
    p=tf.reshape(tf.nn.avg_pool2d(s,7,2,'VALID'),[1,-1])
    age=tf.matmul(p,W['fc/age/weights'])+W['fc/age/bias']
    g=tf.nn.softmax(tf.matmul(p,W['fc/gender/weights'])+W['fc/gender/bias'])
    return {'age':age,'gender':g}
conv_=tf.lite.TFLiteConverter.from_concrete_functions([net.get_concrete_function()])
open('faceapi_agegender.tflite','wb').write(conv_.convert())
m=tf.Module(); m.f=net; tf.saved_model.save(m,'faceapi_sm',signatures={'serving_default':net})
print('ok')
