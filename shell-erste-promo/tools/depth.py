# depth.py in.jpg out_depth.png  — Depth Anything V2 (small) relative inverse depth, 16-bit PNG
import sys, numpy as np, cv2, onnxruntime as ort
src, dst = sys.argv[1], sys.argv[2]
model = sys.argv[3] if len(sys.argv) > 3 else '/tmp/claude-0/-home-user-true/cc49b574-8635-53b5-ac4d-659709415653/scratchpad/models/da2s.onnx'
img = cv2.cvtColor(cv2.imread(src), cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
h, w = img.shape[:2]
S = 518
nh = S; nw = int(round(w * S / h / 14)) * 14
x = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_CUBIC)
x = (x - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]
x = x.transpose(2, 0, 1)[None].astype(np.float32)
sess = ort.InferenceSession(model, providers=['CPUExecutionProvider'])
name = sess.get_inputs()[0].name
d = sess.run(None, {name: x})[0][0]
d = cv2.resize(d, (w, h), interpolation=cv2.INTER_CUBIC)
d = (d - d.min()) / (d.max() - d.min() + 1e-6)
cv2.imwrite(dst, (d * 65535).astype(np.uint16))
print(src, d.shape, float(d.mean()))
