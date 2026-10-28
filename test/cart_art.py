"""Decodes a .CHG's cartridge picture the way the app's Cart.java does, to PNG (3x)."""
import struct, sys
from PIL import Image
for path, out in zip(sys.argv[1::2], sys.argv[2::2]):
    d = open(path, "rb").read()
    title = d[0x20:0x40].split(b"\0")[0].decode(); author = d[0x40:0x50].split(b"\0")[0].decode()
    ver = d[0x50:0x60].split(b"\0")[0].decode()
    at, n, _ = struct.unpack_from("<III", d, 0x60)
    assert n == 8704 and d[at:at + 4] == b"CHB1"
    pal = []
    for i in range(16):
        c = struct.unpack_from("<H", d, at + 8 + 2 * i)[0]
        pal.append(((c >> 11 & 31) * 255 // 31, (c >> 5 & 63) * 255 // 63, (c & 31) * 255 // 31))
    pal[15] = pal[11]
    im = Image.new("RGB", (128, 128))
    for y in range(128):
        for x in range(64):
            v = d[at + 512 + y * 64 + x]
            im.putpixel((2 * x, y), pal[v >> 4]); im.putpixel((2 * x + 1, y), pal[v & 15])
    im.resize((384, 384), 0).save(out)
    print(path.split("/")[-1], "|", title, "|", author, "|", ver)
