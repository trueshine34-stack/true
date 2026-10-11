# Generează miniaturi JPG (128 px, fundal „mist”) pentru emailuri din imaginile WebP de catalog.
import glob, os
from PIL import Image
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
n = 0
for f in glob.glob(os.path.join(ROOT, "public/img/products/*/*-480.webp")):
    out = f.replace("-480.webp", "-email.jpg")
    if os.path.exists(out) and os.path.getmtime(out) > os.path.getmtime(f):
        continue
    im = Image.open(f).convert("RGBA").resize((128, 128), Image.LANCZOS)
    bg = Image.new("RGBA", im.size, (244, 245, 241, 255))
    bg.alpha_composite(im)
    bg.convert("RGB").save(out, "JPEG", quality=86, optimize=True, progressive=True)
    n += 1
print("miniaturi email:", n)
