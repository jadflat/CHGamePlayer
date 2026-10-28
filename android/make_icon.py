"""Draws the launcher icon: a small screen with a play arrow, in the app's dark and yellow,
in the mipmap sizes."""
import os
from PIL import Image, ImageDraw

here = os.path.dirname(os.path.abspath(__file__))
BG, SCREEN, ARROW = (0x11, 0x13, 0x18), (0x27, 0x2A, 0x2F), (0xFF, 0xD2, 0x5A)
SS = 4    # drawn this many times larger, then scaled down: smooth edges

for name, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    n = size * SS
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    g = ImageDraw.Draw(im)
    g.rounded_rectangle((0, 0, n - 1, n - 1), radius=n // 5, fill=BG)
    m = n * 0.2
    g.rounded_rectangle((m, m, n - m, n - m), radius=n // 16, fill=SCREEN)
    c, r = n / 2, n * 0.17
    g.polygon([(c - r * 0.7, c - r), (c - r * 0.7, c + r), (c + r, c)], fill=ARROW)
    d = os.path.join(here, "res", "mipmap-" + name)
    os.makedirs(d, exist_ok=True)
    im.resize((size, size), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))
print("ok")
