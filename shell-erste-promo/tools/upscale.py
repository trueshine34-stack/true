"""4x photo upscaling with Real-ESRGAN x4plus (ONNX, fixed 128px tiles, feathered overlap).

python3 tools/upscale.py in.jpg out.png [model.onnx]
"""
import sys, numpy as np, cv2, onnxruntime as ort

src, dst = sys.argv[1], sys.argv[2]
model = sys.argv[3] if len(sys.argv) > 3 else '/tmp/claude-0/-home-user-true/cc49b574-8635-53b5-ac4d-659709415653/scratchpad/models/esrgan/real_esrgan_x4plus-onnx-float/real_esrgan_x4plus.onnx'
T, OV, S = 128, 24, 4
img = cv2.cvtColor(cv2.imread(src), cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
h, w = img.shape[:2]
pad = cv2.copyMakeBorder(img, OV, OV + T, OV, OV + T, cv2.BORDER_REFLECT)
sess = ort.InferenceSession(model, providers=['CPUExecutionProvider'])
iname = sess.get_inputs()[0].name
out = np.zeros(((h + 2 * OV + T) * S, (w + 2 * OV + T) * S, 3), np.float32)
wsum = np.zeros(out.shape[:2] + (1,), np.float32)
ramp = np.minimum(1, (np.arange(T * S) + 0.5) / (OV * S))
win = np.minimum(ramp, ramp[::-1])
win = np.outer(win, win)[..., None].astype(np.float32) + 1e-4
step = T - OV
for y in range(0, h + 2 * OV - OV, step):
    for x in range(0, w + 2 * OV - OV, step):
        tile = pad[y:y + T, x:x + T]
        if tile.shape[0] < T or tile.shape[1] < T:
            continue
        r = sess.run(None, {iname: tile.transpose(2, 0, 1)[None]})[0][0].transpose(1, 2, 0)
        out[y * S:(y + T) * S, x * S:(x + T) * S] += r * win
        wsum[y * S:(y + T) * S, x * S:(x + T) * S] += win
res = out / np.maximum(wsum, 1e-6)
res = res[OV * S:(OV + h) * S, OV * S:(OV + w) * S]
cv2.imwrite(dst, cv2.cvtColor((np.clip(res, 0, 1) * 255 + 0.5).astype(np.uint8), cv2.COLOR_RGB2BGR))
print(dst, res.shape)
