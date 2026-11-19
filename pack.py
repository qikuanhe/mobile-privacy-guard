# -*- coding: utf-8 -*-
"""把 classes.dex 注入 APK（保持其余条目原样）"""
import sys
import zipfile

src, dst, dex = sys.argv[1], sys.argv[2], sys.argv[3]

with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    for item in zin.infolist():
        if item.filename == "classes.dex":
            continue
        zout.writestr(item, zin.read(item.filename))
    with open(dex, "rb") as f:
        zout.writestr("classes.dex", f.read())

print("dex injected ->", dst)
