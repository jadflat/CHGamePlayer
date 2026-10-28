"""Marks a .CHG as a port: adds (or updates) its record (CHGame spec/chg.md, "The record")
with the game's full credits and a description that starts "PICO-8 port:" or "Pokitto port:".

    python chg_mark_port.py <CHGame repo>/tools in.CHG out.CHG --from PICO-8 --porter Jadflat [--json chgame.json]
                            [--author "full author"] [--id id] [--license text] [--original "title"]

The record's game keys are the spec's own (id, title, author, version, description, license);
the program, picture and header are copied unchanged. With --json the id, author (without its
"(port: ...)" note) and licence come from the project's chgame.json.
"""
import argparse, json, re, sys

a = argparse.ArgumentParser()
for k in ("tools", "src", "out"):
    a.add_argument(k)
a.add_argument("--from", dest="origin", required=True)
a.add_argument("--porter", required=True)
a.add_argument("--json")
a.add_argument("--author")
a.add_argument("--id")
a.add_argument("--license")
a.add_argument("--original")
args = a.parse_args()
sys.path.insert(0, args.tools)
import chgpack

data = open(args.src, "rb").read()
info = chgpack.parse(data)
payload = data[chgpack.HEADER_BYTES:chgpack.HEADER_BYTES + info["payload_bytes"]]
picture = chgpack.read_picture(data)
try:
    old = chgpack.read_record(data)
except chgpack.ChgError:
    old = None
rec = json.loads(old) if old else {"chgRecord": 1, "game": {}}
game = rec["game"]

meta = json.load(open(args.json, encoding="utf-8")) if args.json else {}
author = args.author or re.sub(r"\s*\(port:[^)]*\)\s*$", "", meta.get("author", "")) or game.get("author") or info["author"]
gid = args.id or meta.get("id") or game.get("id") or re.sub(r"[^a-z0-9]+", "-", info["title"].lower()).strip("-")
lic = args.license or meta.get("license") or game.get("license")
original = args.original or meta.get("title") or info["title"]

game.update({"id": gid, "title": info["title"], "author": author,
             "description": f"{args.origin} port: {original} by {author}, ported to CHGame by {args.porter}."})
if info["version"]:
    game["version"] = info["version"]
if lic:
    game["license"] = lic
if "binaryBytes" not in rec:
    # the binary before padding: the payload less the 0xFF fill to a multiple of 4
    n = len(payload)
    while n > len(payload) - 3 and payload[n - 1] == 0xFF:
        n -= 1
    rec["binaryBytes"] = n
record = json.dumps(rec, sort_keys=True, separators=(",", ":")).encode("ascii")

new = chgpack.pack(payload, info["title"], info["author"], info["version"], info["app_version"],
                   picture, record, info["target"])
check = chgpack.parse(new)
assert new[chgpack.HEADER_BYTES:chgpack.HEADER_BYTES + check["payload_bytes"]] == payload
assert chgpack.read_picture(new) == picture and chgpack.read_record(new) == record
open(args.out, "wb").write(new)
print(f"{args.out}: {game['description']}")
