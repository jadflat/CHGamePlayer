"""Sets a .CHG's title, author or version, keeping everything else as it was.

    python chg_header.py <CHGame repo>/tools in.CHG out.CHG [--title T] [--author A] [--version V]

The program, app version, target, picture and record are copied from in.CHG unchanged
(CHGame's own chgpack makes the new header). Fields not given keep their old value.
"""
import argparse, sys

a = argparse.ArgumentParser()
a.add_argument("tools")
a.add_argument("src")
a.add_argument("out")
a.add_argument("--title")
a.add_argument("--author")
a.add_argument("--version")
args = a.parse_args()
sys.path.insert(0, args.tools)
import chgpack

data = open(args.src, "rb").read()
info = chgpack.parse(data)
payload = data[chgpack.HEADER_BYTES:chgpack.HEADER_BYTES + info["payload_bytes"]]
picture = chgpack.read_picture(data)
try:
    record = chgpack.read_record(data)
except chgpack.ChgError:
    record = None
title = args.title if args.title is not None else info["title"]
author = args.author if args.author is not None else info["author"]
version = args.version if args.version is not None else info["version"]
new = chgpack.pack(payload, title, author, version, info["app_version"], picture, record, info["target"])

check = chgpack.parse(new)
assert new[chgpack.HEADER_BYTES:chgpack.HEADER_BYTES + check["payload_bytes"]] == payload
assert chgpack.read_picture(new) == picture
assert (title, author, version) == (check["title"], check["author"], check["version"])
open(args.out, "wb").write(new)
print(f"{args.out}: {info['title']!r}/{info['author']!r}/{info['version']!r} -> {title!r}/{author!r}/{version!r}; "
      f"program, picture{' and record' if record else ''} unchanged")
