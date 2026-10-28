"""Lists each .CHG's title, author and version beside what its project says.

    python chg_credits.py <Arduino folder> <folder of .CHG files>

For each file it finds the project whose dist/ holds the same file (by SHA-256), or else one
whose dist/ has a .CHG with the same title, and shows: chgame.json's title/author/version (if
any), the newest git tag, and the latest CHANGELOG heading.
"""
import hashlib, json, os, re, subprocess, sys

root, folder = sys.argv[1], sys.argv[2]


def head(p):
    d = open(p, "rb").read(512)
    cs = lambda b: b.split(b"\0")[0].decode("utf-8", "replace").strip()
    return cs(d[0x20:0x40]), cs(d[0x40:0x50]), cs(d[0x50:0x58])


sha = lambda p: hashlib.sha256(open(p, "rb").read()).hexdigest()
by_sha, by_title = {}, {}
for proj in sorted(os.listdir(root)):
    dist = os.path.join(root, proj, "dist")
    if not os.path.isdir(dist) or proj.startswith("StarLiftGBA"):
        continue
    for f in os.listdir(dist):
        if f.lower().endswith(".chg"):
            p = os.path.join(dist, f)
            by_sha[sha(p)] = proj
            by_title.setdefault(head(p)[0].lower(), proj)


def meta(proj):
    out = {}
    for j in [os.path.join(root, proj, "chgame.json")] + [os.path.join(root, proj, d, "chgame.json")
                                                          for d in os.listdir(os.path.join(root, proj))
                                                          if os.path.isdir(os.path.join(root, proj, d))]:
        if os.path.isfile(j):
            try:
                c = json.load(open(j, encoding="utf-8"))
                out["json"] = f"{c.get('title', '')} | {c.get('author', '')} | {c.get('version', '')}"
                break
            except Exception:
                pass
    try:
        tag = subprocess.run(["git", "-C", os.path.join(root, proj), "describe", "--tags", "--abbrev=0"],
                             capture_output=True, text=True).stdout.strip()
        out["tag"] = tag
    except Exception:
        pass
    ch = os.path.join(root, proj, "CHANGELOG.md")
    if os.path.isfile(ch):
        for line in open(ch, encoding="utf-8", errors="replace"):
            m = re.match(r"#+\s*\[?v?(\d+(?:\.\d+)+)", line)
            if m:
                out["changelog"] = m.group(1)
                break
    return out


for f in sorted(os.listdir(folder), key=str.lower):
    p = os.path.join(folder, f)
    if not f.lower().endswith(".chg") or not os.path.isfile(p):
        continue
    t, a, v = head(p)
    proj = by_sha.get(sha(p)) or by_title.get(t.lower())
    m = meta(proj) if proj else {}
    print(f"{f:22} | {t:24} | {a or '-':16} | {v or '-':8} || {proj or '(no project)':16} "
          f"json: {m.get('json', '-')}  tag: {m.get('tag') or '-'}  changelog: {m.get('changelog', '-')}")
