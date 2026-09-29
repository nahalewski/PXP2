import os
import shutil
import zipfile
import subprocess
from pathlib import Path

BASE_DIR = Path(r"c:\Users\Ben\Desktop\pxp2\themes")
CACHE_DIR = BASE_DIR / "_cache"
OFFICIAL_DIR = BASE_DIR / "official"
CTF_DIR = BASE_DIR / "ctf"
TEMP_DIR = BASE_DIR / "_extracted_tmp"

OFFICIAL_DIR.mkdir(parents=True, exist_ok=True)
CTF_DIR.mkdir(parents=True, exist_ok=True)
TEMP_DIR.mkdir(parents=True, exist_ok=True)

SEVEN_ZIP = r"C:\Program Files\7-Zip\7z.exe"

print("=" * 60)
print("             PSP Themes Unpacker & Curator                  ")
print("=" * 60)

# 1. Extract all zips in cache
for item in CACHE_DIR.glob("*.*"):
    out_dir = TEMP_DIR / item.stem
    out_dir.mkdir(parents=True, exist_ok=True)
    print(f"[*] Extracting {item.name}...")
    if item.suffix.lower() == ".zip":
        try:
            with zipfile.ZipFile(item, "r") as z:
                z.extractall(out_dir)
        except Exception as e:
            print(f"    [!] Zip error: {e}")
    elif item.suffix.lower() in [".7z", ".rar"]:
        if os.path.exists(SEVEN_ZIP):
            subprocess.run([SEVEN_ZIP, "x", "-y", f"-o{out_dir}", str(item)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

# 2. Collect and organize all .ptf and .ctf files
ptf_count = 0
ctf_count = 0

for root, _, files in os.walk(TEMP_DIR):
    for f in files:
        ext = os.path.splitext(f)[1].lower()
        src = Path(root) / f
        if ext == ".ptf":
            dest = OFFICIAL_DIR / f
            if not dest.exists():
                shutil.copy2(src, dest)
            # also copy to main themes/
            main_dest = BASE_DIR / f
            if not main_dest.exists():
                shutil.copy2(src, main_dest)
            ptf_count += 1
        elif ext == ".ctf":
            dest = CTF_DIR / f
            if not dest.exists():
                shutil.copy2(src, dest)
            main_dest = BASE_DIR / f
            if not main_dest.exists():
                shutil.copy2(src, main_dest)
            ctf_count += 1

print(f"[+] Found & Organized:")
print(f"    - Official PTF Themes: {len(list(OFFICIAL_DIR.glob('*.ptf')))}")
print(f"    - Custom CTF Themes:   {len(list(CTF_DIR.glob('*.ctf')))}")

# 3. Clean up temp extraction folder
shutil.rmtree(TEMP_DIR, ignore_errors=True)

print("=" * 60)
print("[*] Complete!")
