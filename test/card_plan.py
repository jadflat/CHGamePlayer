"""Plans bringing a CHGame SD card up to date with the newest build of every game here.

    python card_plan.py <Arduino folder> <card root> [--do <backup folder>]

Games are matched by the title in their header (case-insensitive), not by file name.
For each title the newest local build wins: the highest version, then the newest file.
Only each project's own dist/ folder counts (and out/card/*.DAT when dist/ has no .DAT) (not build outputs, the GBA project's test
builds, the emulator's games folder or the original PICO-8 folders). .DAT files: each
project's dist/*.DAT against the card root.

Without --do it only prints the plan. With --do every card file it replaces or removes
is first copied to <backup folder>, then the new files are copied and checked by SHA-256.
"""
import hashlib, os, re, shutil, struct, sys

SKIP_DIRS = {"StarLiftGBAFS", "CHGame_Emulator-windows-latest_x86_64", "pico 8 port original folders",
             "CHGameRetro", "CHGameGBA", "CHGameMiSTer", "tools-sd", "CHSDtoUSB"}
EXTRA = {}   # title -> path, for builds outside a dist/ folder (filled from argv below)


def header(path):
    d = open(path, "rb").read(512)
    if len(d) < 512 or d[:4] != b"CHG1":
        return None
    cs = lambda b: b.split(b"\0")[0].decode("utf-8", "replace").strip()
    at, n, _ = struct.unpack_from("<III", d, 0x60)
    return {"title": cs(d[0x20:0x40]), "author": cs(d[0x40:0x50]), "version": cs(d[0x50:0x58]),
            "picture": bool(at and n)}


def vkey(v):
    return [int(x) for x in re.findall(r"\d+", v)] or [-1]


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def main():
    root, card = sys.argv[1], sys.argv[2]
    do = sys.argv[sys.argv.index("--do") + 1] if "--do" in sys.argv else None
    games = os.path.join(card, "GAMES", "GAMES")

    best, dats = {}, {}
    for proj in sorted(os.listdir(root)):
        dist = os.path.join(root, proj, "dist")
        if proj in SKIP_DIRS or not os.path.isdir(dist):
            continue
        for f in os.listdir(dist):
            p = os.path.join(dist, f)
            if f.lower().endswith(".chg"):
                h = header(p)
                if not h:
                    continue
                k = h["title"].lower()
                cand = (vkey(h["version"]), os.path.getmtime(p), p, h)
                if k not in best or cand[:2] > best[k][:2]:
                    best[k] = cand
            elif f.lower().endswith(".dat"):
                dats[f.upper()] = p
        # a project with no .DAT in dist/: the one its build writes for the card
        card_out = os.path.join(root, proj, "out", "card")
        if os.path.isdir(card_out):
            for f in os.listdir(card_out):
                if f.lower().endswith(".dat") and f.upper() not in dats:
                    dats[f.upper()] = os.path.join(card_out, f)
    for t, p in EXTRA.items():
        best[t.lower()] = ([9999], 0, p, header(p))

    card_files = {}
    for f in sorted(os.listdir(games)):
        p = os.path.join(games, f)
        if f.lower().endswith(".chg") and os.path.isfile(p):
            h = header(p)
            if h:
                card_files.setdefault(h["title"].lower(), []).append((f, p, h))

    copies, removes, same, keep = [], [], [], []
    for k, (_, _, src, h) in sorted(best.items()):
        name = os.path.basename(src)
        on_card = card_files.get(k, [])
        target = os.path.join(games, name)
        if any(f.lower() == name.lower() and sha(p) == sha(src) for f, p, _ in on_card) and len(on_card) == 1:
            same.append(name)
            continue
        copies.append((src, target, h, [f"{f} ({hh['version'] or '-'}{', no picture' if not hh['picture'] else ''})" for f, _, hh in on_card]))
        for f, p, _ in on_card:
            if f.lower() != name.lower():
                removes.append(p)
    for k, lst in card_files.items():
        if k not in best:
            keep += [f for f, _, _ in lst]
    dat_copies = []
    for name, src in sorted(dats.items()):
        target = os.path.join(card, name)
        if os.path.isfile(target) and sha(target) == sha(src):
            same.append(name)
        else:
            dat_copies.append((src, target))

    print(f"Already the newest ({len(same)}): " + ", ".join(sorted(same)))
    print(f"\nCopy to the card ({len(copies)}):")
    for src, target, h, was in copies:
        print(f"  {os.path.relpath(src, root):42} {h['title']} {h['version'] or '-'}"
              f"{'' if h['picture'] else ' (NO PICTURE)'}  replaces: {', '.join(was) or 'nothing (new)'}")
    print(f"\nRemove from the card, superseded ({len(removes)}): " + ", ".join(os.path.basename(p) for p in removes))
    print(f"\n.DAT to the card root ({len(dat_copies)}): " + ", ".join(os.path.relpath(s, root) for s, _ in dat_copies))
    print(f"\nLeft as they are, no local build ({len(keep)}): " + ", ".join(sorted(keep)))

    if not do:
        return
    os.makedirs(do, exist_ok=True)
    for src, target, _, _ in copies:
        if os.path.exists(target):
            shutil.copy2(target, os.path.join(do, os.path.basename(target)))
    for p in removes:
        shutil.copy2(p, os.path.join(do, os.path.basename(p)))
    for _, target in dat_copies:
        if os.path.exists(target):
            shutil.copy2(target, os.path.join(do, os.path.basename(target)))
    bad = 0
    for src, target in [(s, t) for s, t, _, _ in copies] + dat_copies:
        shutil.copyfile(src, target)
        ok = sha(src) == sha(target)
        bad += not ok
        print(f"{'ok      ' if ok else 'MISMATCH'} {sha(src)[:16]} {os.path.basename(target)}")
    for p in removes:
        os.remove(p)
        print(f"removed  {os.path.basename(p)} (backed up)")
    print(f"\n{len(copies) + len(dat_copies)} copied, {bad} mismatches, {len(removes)} removed; backups in {do}")


if __name__ == "__main__":
    args = sys.argv
    while "--extra" in args:
        i = args.index("--extra")
        t, p = args[i + 1].split("=", 1)
        EXTRA[t] = p
        del args[i:i + 2]
    main()
