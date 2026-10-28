"""Copies an APK and adds files to it: add_to_apk.py in.apk out.apk name=path...
.so files are stored uncompressed (the app maps them straight from the APK); the rest deflated.
Entry names are made '/'-separated: aapt2 on Windows writes assets as "assets/fonts\\x.ttf",
which Android's asset manager cannot find."""
import sys
import zipfile

src, dst, adds = sys.argv[1], sys.argv[2], [a.split("=", 1) for a in sys.argv[3:]]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        item.filename = item.filename.replace("\\", "/")
        zout.writestr(item, data)
    for name, path in adds:
        method = zipfile.ZIP_STORED if name.endswith(".so") else zipfile.ZIP_DEFLATED
        zout.write(path, name.replace("\\", "/"), compress_type=method)
