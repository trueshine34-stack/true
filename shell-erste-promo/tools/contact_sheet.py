"""Storyboard sheet: one frame from every shot with its timecode."""
import subprocess, sys
from PIL import Image, ImageDraw, ImageFont
src, dst = sys.argv[1], sys.argv[2]
times = [0.8, 2.3, 3.8, 4.8, 5.8, 6.8, 8.0, 8.95, 9.9, 10.75, 11.7, 14.5]
labels = ['Balaton, golden hour', 'SUV — sun on the body', 'Shell station', 'Shell station, V-Power', '25 LITER…',
          '…INGYEN!', 'Erste card tap', '25,00 L for 0 Ft', 'Getting in', 'Door shut, drive off', 'End card builds', 'End card']
tw, th = 640, 360
sheet = Image.new('RGB', (3 * tw, 4 * (th + 34)), (14, 14, 18))
d = ImageDraw.Draw(sheet)
try:
    font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 18)
except OSError:
    font = ImageFont.load_default()
for i, (t, lab) in enumerate(zip(times, labels)):
    raw = subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-ss', str(t), '-i', src, '-frames:v', '1',
                          '-vf', f'scale={tw}:{th}', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], capture_output=True).stdout
    im = Image.frombytes('RGB', (tw, th), raw)
    x, y = (i % 3) * tw, (i // 3) * (th + 34)
    sheet.paste(im, (x, y))
    d.text((x + 10, y + th + 7), f'{t:05.2f}s  {lab}', fill=(251, 206, 7), font=font)
sheet.save(dst, quality=88)
print('storyboard ->', dst)
