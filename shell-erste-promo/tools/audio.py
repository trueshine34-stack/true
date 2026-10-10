"""Sound mix for the 15" spot: score edit + VO + SFX, ducking, limiting.

Writes build/mix.wav (48 kHz stereo). Loudness is normalised later by ffmpeg (loudnorm).
"""
import json, os, subprocess
import numpy as np
from scipy.signal import butter, sosfilt, fftconvolve

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
A = lambda *p: os.path.join(ROOT, 'assets', *p)
SR = 48000
DUR = 15.0
N = int(SR * DUR)


def load(path):
    raw = subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-i', path, '-f', 'f32le', '-ac', '2', '-ar', str(SR), '-'],
                         capture_output=True).stdout
    return np.frombuffer(raw, np.float32).reshape(-1, 2).copy()


def db(x):
    return 10 ** (x / 20)


def place(bus, clip, at, gain_db=0.0, fade_out=0.0):
    i = int(round(at * SR))
    c = clip * db(gain_db)
    if fade_out > 0:
        k = int(fade_out * SR)
        c[-k:] *= np.linspace(1, 0, min(k, len(c)))[:, None]
    if i < 0:
        c = c[-i:]; i = 0
    j = min(len(bus), i + len(c))
    bus[i:j] += c[: j - i]


def filt(x, kind, f, order=2):
    sos = butter(order, f, btype=kind, fs=SR, output='sos')
    return sosfilt(sos, x, axis=0)


def peak_time(clip):
    env = np.abs(clip).max(axis=1)
    return int(np.argmax(env)) / SR


# ---------------- score ----------------
# "Golden Storm" (Mixkit): the breakdown → build → drop. Drop (track 45.366 s) lands on the end card at 11.006 s.
music = load(A('music', 'golden_storm.mp3'))
off = 45.366 - 11.006
seg = music[int(off * SR): int(off * SR) + N].copy()
seg[: int(0.35 * SR)] *= np.linspace(0, 1, int(0.35 * SR))[:, None] ** 2
# button ending: low-pass sweep and fade over the last bar
t = np.arange(N) / SR
tail0 = 14.32
k0 = int(tail0 * SR)
tail = seg[k0:].copy()
# progressive LPF by crossfading between filtered versions
lp1 = filt(tail, 'low', 2500); lp2 = filt(tail, 'low', 600)
w = np.linspace(0, 1, len(tail))[:, None]
tail = tail * (1 - w) ** 2 + lp1 * 2 * w * (1 - w) + lp2 * w ** 2
tail *= (1 - w) ** 1.6
seg[k0:] = tail

# ---------------- VO ----------------
def vo(name, at):
    x = load(A('vo', name))
    x = filt(x, 'high', 90)
    # presence + gentle compression
    pres = filt(x, 'band', [2500, 5500])
    x = x + 0.35 * pres
    env = np.abs(x).max(axis=1)
    thr = np.percentile(env[env > 1e-4], 85) if np.any(env > 1e-4) else 1
    g = np.where(env > thr, (thr / np.maximum(env, 1e-9)) ** 0.45, 1.0)
    g = filt(g[:, None], 'low', 30)[:, 0]
    x = x * g[:, None]
    return x, at

vos = [vo('vo1.mp3', 0.25), vo('vo2.mp3', 3.55), vo('vo3a.mp3', 11.25), vo('vo3b.mp3', 12.60)]
vbus = np.zeros((N, 2), np.float32)
for x, at in vos:
    x = x / (np.sqrt(np.mean(x[np.abs(x).max(axis=1) > 0.01] ** 2)) + 1e-9) * db(-17)
    place(vbus, x, at)

# ---------------- SFX ----------------
sbus = np.zeros((N, 2), np.float32)
S = lambda n: load(A('sfx', n))
wc, wa, wt = S('whoosh_cinematic.mp3'), S('whoosh_air.mp3'), S('whoosh_tunnel.mp3')
place(sbus, wa, 1.555 - peak_time(wa), -16)                     # leak into the SUV shot
place(sbus, wc, 3.448 - peak_time(wc), -4)                      # whip to the station
place(sbus, wa, 4.400 - peak_time(wa), -18)
place(sbus, wa, 5.340 - peak_time(wa), -6)                      # whip to the nozzle
click = S('tap_click.mp3')
place(sbus, filt(click, 'low', 3000), 6.20, 2)                  # nozzle unhooked
place(sbus, wa, 7.233 - peak_time(wa) + 0.05, -15)              # rack focus
place(sbus, S('confirmation_tone.mp3'), 7.62, -9)               # contactless accepted
tick = S('tech_select.mp3')[: int(0.06 * SR)]
tick *= np.linspace(1, 0, len(tick))[:, None]
for k in range(18):                                             # counter ticking up, easing out
    q = k / 17
    tt = 7.75 + (1 - (1 - q) ** (1 / 3)) * 1.08
    place(sbus, tick, tt, -20 + 4 * q)
place(sbus, S('correct_tone.mp3'), 8.84, -8)                    # 25,00 L
place(sbus, wa, 9.125 - peak_time(wa), -14)
door = S('car_door_close.mp3')
door = door[: int(1.6 * SR)]
door[int(1.0 * SR):] *= np.linspace(1, 0, len(door) - int(1.0 * SR))[:, None]
place(sbus, door, 10.50 - peak_time(door), -6)                  # door shuts
eng = S('car_engine_start.mp3')
place(sbus, eng, 10.56, -15, fade_out=0.4)
rev = wt[: int(1.6 * SR)][::-1].copy()                          # reversed swell into the drop
place(sbus, rev, 11.006 - len(rev) / SR + 0.02, -10)
pop = filt(S('tap_click.mp3'), 'high', 600)
place(sbus, pop, 11.65, -10)                                    # INGYEN! stamp
place(sbus, pop, 12.47, -14)                                    # date badge

# ---------------- mix ----------------
# side-chain the score under the voice
venv = np.abs(vbus).max(axis=1)
venv = filt(venv[:, None], 'low', 8)[:, 0]
active = (venv > 0.004).astype(np.float32)
# hold through short gaps between words, then smooth (≈60 ms attack / 300 ms release)
hold = int(0.25 * SR)
act = np.convolve(active, np.ones(hold, np.float32), 'same') > 0
g = np.empty(N, np.float32); cur = 0.0
a_up, a_dn = 1 - np.exp(-1 / (0.06 * SR)), 1 - np.exp(-1 / (0.30 * SR))
for i0 in range(0, N, 48):
    tgt = 1.0 if act[i0] else 0.0
    a = a_up if tgt > cur else a_dn
    cur += (tgt - cur) * (1 - (1 - a) ** 48)
    g[i0:i0 + 48] = cur
duck = db(-10) ** g
mus = seg / (np.sqrt(np.mean(seg[: int(11 * SR)] ** 2)) + 1e-9) * db(-21)
mus = mus * duck[:, None]
# small stereo room on the SFX bus
ir_len = int(0.6 * SR)
rng = np.random.default_rng(3)
ir = rng.standard_normal((ir_len, 2)) * np.exp(-np.linspace(0, 7, ir_len))[:, None]
ir /= np.abs(ir).sum(axis=0)
wet = np.stack([fftconvolve(sbus[:, c], ir[:, c])[:N] for c in range(2)], axis=1)
sfx = sbus + wet * 6

mix = mus + vbus + sfx * db(-2)
# soft limiter
peak = np.abs(mix).max()
mix = mix / peak * 0.98
mix = np.tanh(mix * 1.4) / np.tanh(1.4)
os.makedirs(os.path.join(ROOT, 'build'), exist_ok=True)
out = os.path.join(ROOT, 'build', 'mix_raw.f32')
mix.astype(np.float32).tofile(out)
subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-f', 'f32le', '-ar', str(SR), '-ac', '2', '-i', out,
                os.path.join(ROOT, 'build', 'mix.wav')], check=True)
os.remove(out)
print('mix written', mix.shape)
