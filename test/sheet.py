"""Joins a run's frame_NNN.ppm into one contact sheet PNG (each frame 2x, labelled by second)."""
import sys, glob, os
from PIL import Image, ImageDraw
out = sys.argv[-1]
rows = []
for d in sys.argv[1:-1]:
    frames = sorted(glob.glob(os.path.join(d, "frame_*.ppm")))
    rows.append((os.path.basename(d), [Image.open(f).convert("RGB") for f in frames]))
cols = max(len(r[1]) for r in rows)
W, H = 256 + 4, 256 + 18
sheet = Image.new("RGB", (cols * W, len(rows) * H), (32, 32, 32))
dr = ImageDraw.Draw(sheet)
for y, (name, frames) in enumerate(rows):
    for x, im in enumerate(frames):
        sheet.paste(im.resize((256, 256), Image.NEAREST), (x * W, y * H + 16))
        dr.text((x * W + 2, y * H + 2), f"{name} {x + 1}s", fill=(220, 220, 220))
sheet.save(out)
for f in glob.glob(os.path.join(sys.argv[1], "..", "*", "frame_*.ppm")):
    Image.open(f).save(f[:-4] + ".png")
    os.remove(f)
