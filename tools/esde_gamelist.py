"""Writes ES-DE's gamelist.xml for the CHGame system from the games' own .CHG files.

    python esde_gamelist.py <folder of .CHG files> <existing gamelist.xml or -> <out gamelist.xml>

For each game: the name, developer and description from its header and record (a port's
description says what it was ported from), the publisher (who made the CHGame version) and,
kept from the existing gamelist, everything ES-DE recorded itself (play count, play time,
last played, favourites, ratings...). Games in the old list that are not in the folder stay.
"""
import json, os, struct, sys, zlib
import xml.etree.ElementTree as ET

folder, old_path, out = sys.argv[1:4]


def info(path):
    d = open(path, "rb").read()
    cs = lambda b: b.split(b"\0")[0].decode("utf-8", "replace").strip()
    title, author, version = cs(d[0x20:0x40]), cs(d[0x40:0x50]), cs(d[0x50:0x58])
    game = {}
    at, n, crc = struct.unpack_from("<III", d, 0x6C)
    if at and n and at + n <= len(d) and zlib.crc32(d[at:at + n]) & 0xFFFFFFFF == crc:
        try:
            game = json.loads(d[at:at + n]).get("game", {})
        except ValueError:
            game = {}
    return title, game.get("author") or author, version, game


games = {}
if old_path != "-" and os.path.isfile(old_path):
    for g in ET.parse(old_path).getroot().findall("game"):
        games[g.findtext("path")] = g

for f in sorted(os.listdir(folder), key=str.lower):
    if not f.lower().endswith(".chg"):
        continue
    title, author, version, game = info(os.path.join(folder, f))
    path = "./" + f
    g = games.get(path)
    if g is None:
        g = ET.Element("game")
        ET.SubElement(g, "path").text = path
        games[path] = g
    desc = game.get("description") or (f"A game for the CHGame handheld by {author}." if author else "A game for the CHGame handheld.")
    if version:
        desc += f" Version {version}."
    porter = desc.split("ported to CHGame by ")[-1].rstrip(".").split(".")[0] if "ported to CHGame by" in desc else author
    for tag, value in (("name", title or os.path.splitext(f)[0]), ("desc", desc), ("developer", author),
                       ("publisher", porter)):
        if not value:
            continue
        e = g.find(tag)
        if e is None:
            e = ET.SubElement(g, tag)
        e.text = value

root = ET.Element("gameList")
for g in games.values():
    root.append(g)
ET.indent(root, "\t")
ET.ElementTree(root).write(out, encoding="unicode", xml_declaration=False)
s = open(out, encoding="utf-8").read()
open(out, "w", encoding="utf-8", newline="").write('<?xml version="1.0"?>\n' + s + "\n")
print(f"{len(games)} games -> {out}")
