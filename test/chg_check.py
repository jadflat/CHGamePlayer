"""Lists .CHG files and whether each carries a valid cartridge picture (CHGame spec/chg.md).
usage: chg_check.py <file or folder>...   folders are searched for *.CHG (not build/ or node_modules)"""
import os, struct, sys, zlib

def check(path):
    d = open(path, "rb").read()
    if len(d) < 512 or d[:4] != b"CHG1":
        return "not a CHG", "", ""
    title = d[0x20:0x40].split(b"\0")[0].decode("utf-8", "replace")
    ver = d[0x50:0x60].split(b"\0")[0].decode("utf-8", "replace")
    at, n, crc = struct.unpack_from("<III", d, 0x60)
    if not (at or n or crc):
        return "NO PICTURE", title, ver
    pic = d[at:at + n]
    ok = n == 8704 and at % 512 == 0 and at < 0x20000 and len(pic) == n and pic[:4] == b"CHB1" \
        and zlib.crc32(pic) & 0xFFFFFFFF == crc
    return ("picture" if ok else "PICTURE DAMAGED"), title, ver

files = []
for a in sys.argv[1:]:
    if os.path.isfile(a):
        files.append(a)
        continue
    for root, dirs, names in os.walk(a):
        dirs[:] = [x for x in dirs if x not in ("build", "node_modules", ".git", "out")]
        files += [os.path.join(root, f) for f in names if f.lower().endswith(".chg")]
for f in sorted(files, key=str.lower):
    try:
        st, title, ver = check(f)
    except OSError as e:
        st, title, ver = "unreadable", "", ""
    print(f"{st:16} | {title:24} | {ver:10} | {os.path.relpath(f, sys.argv[1]) if os.path.isdir(sys.argv[1]) else f}")
