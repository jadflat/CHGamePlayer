"""Draws the Android TV banner (320x180): the launcher icon (res/mipmap-xxxhdpi, whichever make_icon.py
made, so a public copy gets its neutral one) beside the app's name in Press Start 2P, on the app's dark
background. Run after make_icon.py."""
import os
from PIL import Image, ImageDraw, ImageFont

here = os.path.dirname(os.path.abspath(__file__))
BG, FG, ACCENT = (0x11, 0x13, 0x18), (0xE3, 0xE2, 0xE6), (0xFF, 0xD2, 0x5A)
W, H = 320, 180
SS = 4

icon = Image.open(os.path.join(here, "res", "mipmap-xxxhdpi", "ic_launcher.png")).convert("RGBA")
im = Image.new("RGBA", (W * SS, H * SS), BG + (255,))
g = ImageDraw.Draw(im)
size = 80 * SS
im.alpha_composite(icon.resize((size, size), Image.LANCZOS), (24 * SS, (H * SS - size) // 2))
font = ImageFont.truetype(os.path.join(here, "assets", "fonts", "PressStart2P-Regular.ttf"), 14 * SS)
g.text((118 * SS, 62 * SS), "CHGame", font=font, fill=ACCENT)
g.text((118 * SS, 88 * SS), "Player", font=font, fill=FG)
d = os.path.join(here, "res", "drawable-xhdpi")
os.makedirs(d, exist_ok=True)
im.convert("RGB").resize((W, H), Image.LANCZOS).save(os.path.join(d, "banner.png"))
print("ok")
