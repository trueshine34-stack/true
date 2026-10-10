"""Builds the graded 1920x1080 picture plates for every shot of the spot.

Video shots: decode -> retime -> upscale (Lanczos + light sharpening) -> grade.
Key-visual shots: 2.5D camera move driven by a monocular depth map, then grade.
Output: build/plates/<shot>/0000.jpg ... (exact frame counts, 25 fps).
"""
import json, os, subprocess, sys
import numpy as np
import cv2

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
A = lambda *p: os.path.join(ROOT, 'assets', *p)
OUT = os.path.join(ROOT, 'build', 'plates')
W, H, FPS = 1920, 1080, 25

# Cut points sit on the music's beat grid (126 BPM, drop at 11.0 s): see src/main.js
SHOTS = [
    dict(name='A_lake', kind='video', src='lake_sunset_drone.mp4', t0=0.20, speed=1.0, frames=39,
         grade=dict(wb=(1.04, 1.0, 0.95), exp=0.05, con=1.06, sat=1.08, warm=0.10, teal=0.10)),
    dict(name='B_suv', kind='video', src='suv_sunset_tracking.mp4', t0=2.00, speed=1.0, frames=47,
         grade=dict(wb=(1.0, 1.0, 1.0), exp=0.08, con=1.04, sat=1.0, warm=0.06, teal=0.10, lift=0.012)),
    dict(name='C_station', kind='kv', src='station_a', frames=24,
         move=dict(z0=1.00, z1=1.075, dolly=0.11, px0=-18, px1=22, py0=10, py1=-6),
         grade=dict(wb=(1.0, 0.99, 0.97), exp=0.0, con=1.04, sat=0.98, warm=0.05, teal=0.06)),
    dict(name='D_station', kind='kv', src='station_b', frames=24,
         move=dict(z0=1.05, z1=1.09, dolly=0.06, px0=40, px1=-40, py0=0, py1=0, truck=26),
         grade=dict(wb=(1.0, 0.99, 0.97), exp=0.0, con=1.04, sat=0.98, warm=0.05, teal=0.06)),
    dict(name='E_nozzle', kind='video', src='fuel_nozzle_hand.mp4', t0=3.90, speed=1.5, frames=47,
         grade=dict(wb=(1.10, 1.0, 0.84), exp=-0.05, con=1.14, sat=0.92, warm=0.16, teal=0.12, lift=0.01)),
    dict(name='F_bg', kind='video', src='fuel_nozzle_hand.mp4', t0=0.10, speed=0.5, frames=47, blur=34,
         grade=dict(wb=(1.16, 1.0, 0.78), exp=-0.55, con=1.05, sat=1.1, warm=0.2, teal=0.05)),
    dict(name='G_door', kind='video', src='woman_gets_in_car.mp4', t0=13.45, speed=1.667, frames=36,
         grade=dict(wb=(1.17, 1.01, 0.80), exp=-0.12, con=1.12, sat=0.86, warm=0.20, teal=0.10, lift=0.012)),
    dict(name='H_drive', kind='video', src='suv_sunset_tracking.mp4', t0=9.00, speed=1.0, frames=11,
         grade=dict(wb=(1.0, 1.0, 1.0), exp=0.1, con=1.04, sat=1.0, warm=0.06, teal=0.10)),
    dict(name='P_bg', kind='video', src='lake_sunset_drone.mp4', t0=30.0, speed=1.0, frames=100, blur=46,
         grade=dict(wb=(1.04, 1.0, 0.97), exp=-0.25, con=1.0, sat=1.15, warm=0.1, teal=0.0)),
]


def decode(path, t0, dur):
    probe = json.loads(subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
                                       'stream=width,height,r_frame_rate', '-of', 'json', path],
                                      capture_output=True, text=True).stdout)['streams'][0]
    w, h = probe['width'], probe['height']
    num, den = map(int, probe['r_frame_rate'].split('/'))
    fps = num / den
    raw = subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-ss', f'{t0:.3f}', '-i', path, '-t', f'{dur + 0.2:.3f}',
                          '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], capture_output=True).stdout
    n = len(raw) // (w * h * 3)
    return np.frombuffer(raw, np.uint8)[: n * w * h * 3].reshape(n, h, w, 3), fps


def to_hd(img):
    h, w = img.shape[:2]
    if (w, h) == (W, H):
        return img
    up = cv2.resize(img, (W, H), interpolation=cv2.INTER_LANCZOS4)
    blur = cv2.GaussianBlur(up, (0, 0), 1.3)
    return cv2.addWeighted(up, 1.35, blur, -0.35, 0)


def grade(img, wb=(1, 1, 1), exp=0.0, con=1.0, sat=1.0, warm=0.0, teal=0.0, lift=0.0):
    x = img.astype(np.float32) / 255.0
    x = x * np.array(wb, np.float32) * (2.0 ** exp)
    # soft highlight roll-off
    x = np.where(x > 0.8, 0.8 + (1 - np.exp(-(x - 0.8) * 2.5)) * 0.2 / (1 - np.exp(-0.5)), x)
    x = np.clip(x, 0, 1)
    luma = (x @ np.array([0.2126, 0.7152, 0.0722], np.float32))[..., None]
    x = luma + (x - luma) * sat
    # filmic S-curve around mid grey
    x = np.clip(0.5 + (x - 0.5) * con, 0, 1)
    x = x * x * (3 - 2 * x) * 0.35 + x * 0.65
    # split toning: teal shadows, amber highlights
    luma = (x @ np.array([0.2126, 0.7152, 0.0722], np.float32))[..., None]
    sh = (1 - luma) ** 2
    hi = luma ** 2
    x = x + sh * np.array([-0.03, 0.01, 0.035], np.float32) * teal * 2.0
    x = x + hi * np.array([0.05, 0.018, -0.045], np.float32) * warm * 2.0
    x = lift + x * (1 - lift)
    return (np.clip(x, 0, 1) * 255 + 0.5).astype(np.uint8)


def parallax_frames(src, n, move):
    img = cv2.cvtColor(cv2.imread(A('kv', f'{src}.jpg')), cv2.COLOR_BGR2RGB)
    dep = cv2.imread(A('kv', f'{src}_depth.png'), -1).astype(np.float32) / 65535.0
    # work at 1.25x output size for headroom, upscale with Lanczos + sharpening
    S = 1.25
    bw, bh = int(W * S), int(H * S)
    big = cv2.resize(img, (bw, bh), interpolation=cv2.INTER_LANCZOS4)
    bl = cv2.GaussianBlur(big, (0, 0), 1.6)
    big = cv2.addWeighted(big, 1.45, bl, -0.45, 0)
    d = cv2.resize(dep, (bw, bh), interpolation=cv2.INTER_CUBIC)
    # grow foreground into background so edges smear the far side, not the subject
    d = cv2.dilate(d, np.ones((9, 9), np.uint8))
    d = cv2.GaussianBlur(d, (0, 0), 3)
    d0 = float(np.percentile(d, 40))
    ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
    frames = []
    for i in range(n):
        u = i / max(1, n - 1)
        e = u * u * (3 - 2 * u) * 0.5 + u * 0.5
        z = move['z0'] + (move['z1'] - move['z0']) * e
        px = move['px0'] + (move['px1'] - move['px0']) * e
        py = move['py0'] + (move['py1'] - move['py0']) * e
        dolly = move.get('dolly', 0) * e
        truck = move.get('truck', 0) * (e - 0.5) * 2
        # depth sampled at the output pixel (approximation; motion is small)
        mx = (xs - W / 2) / (z * S) + bw / (2 * S)
        my = (ys - H / 2) / (z * S) + bh / (2 * S)
        dd = cv2.remap(d, mx * S, my * S, cv2.INTER_LINEAR)
        k = 1 + dolly * (dd - d0)
        sx = ((xs - W / 2) / (z * k) + px + truck * (dd - d0) * 4) * S + bw / 2
        sy = ((ys - H / 2) / (z * k) + py) * S + bh / 2
        frames.append(cv2.remap(big, sx, sy, cv2.INTER_CUBIC, borderMode=cv2.BORDER_REFLECT))
    return frames


def build(shot):
    out = os.path.join(OUT, shot['name'])
    os.makedirs(out, exist_ok=True)
    n = shot['frames']
    if shot['kind'] == 'video':
        dur = n / FPS * shot['speed']
        src, fps = decode(A('stock', shot['src']), shot['t0'], dur)
        frames = []
        for i in range(n):
            ts = i / FPS * shot['speed']
            j = min(len(src) - 1, int(round(ts * fps)))
            frames.append(to_hd(src[j]))
    else:
        frames = parallax_frames(shot['src'], n, shot['move'])
    for i, f in enumerate(frames):
        if shot.get('blur'):
            f = cv2.GaussianBlur(f, (0, 0), shot['blur'])
        g = grade(f, **shot['grade'])
        cv2.imwrite(os.path.join(out, f'{i:04d}.jpg'), cv2.cvtColor(g, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 94])
    print(f"{shot['name']}: {len(frames)} frames")


if __name__ == '__main__':
    only = set(sys.argv[1:])
    for s in SHOTS:
        if not only or s['name'] in only:
            build(s)
