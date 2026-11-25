#!/usr/bin/env python3
import filecmp
import os
import shutil
import subprocess
import sys
import zipfile

APKS = [
    ("YouTubeTV", "YouTubeTV.apk"),
    ("SpotifyTV", "SpotifyTV.apk"),
    ("CustomLauncher", "CustomLauncher.apk"),
]

def needs_skip_checks(apk_path: str, zipalign_bin: str) -> bool:
    if os.path.exists(zipalign_bin):
        res = subprocess.run(
            [zipalign_bin, "-c", "-p", "4", apk_path],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if res.returncode != 0:
            return True
    with zipfile.ZipFile(apk_path) as zf:
        for info in zf.infolist():
            if (
                info.filename.startswith("lib/")
                and info.filename.endswith(".so")
                and info.compress_type != zipfile.ZIP_STORED
            ):
                return True
    return False

def main() -> None:
    src_dir = sys.argv[1]
    dst_dir = sys.argv[2]
    zipalign_bin = sys.argv[3]

    if not os.path.isdir(src_dir):
        fallback = "/home/relt/custom_apks"
        if os.path.isdir(fallback):
            src_dir = fallback

    os.makedirs(dst_dir, exist_ok=True)
    flags = []

    for mod_name, filename in APKS:
        key = mod_name.upper()
        src = os.path.join(src_dir, filename)
        dst = os.path.join(dst_dir, filename)
        if os.path.isfile(src):
            if not os.path.isfile(dst) or not filecmp.cmp(src, dst, shallow=False):
                shutil.copy2(src, dst)
            skip = needs_skip_checks(dst, zipalign_bin)
            flags.append(f"HAS_{key}=true")
            flags.append(f"{key}_SKIP={'true' if skip else 'false'}")
        else:
            if os.path.exists(dst):
                os.remove(dst)
            flags.append(f"HAS_{key}=false")
            flags.append(f"{key}_SKIP=false")

    print(" ".join(flags))

if __name__ == "__main__":
    main()
