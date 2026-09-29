#!/usr/bin/env python3
"""
Downloads all PSP homebrew from the VitaHomebrewDB / PSPDev catalog into a local 'database/' folder,
and mirrors into G:\\GAMES\\PSP\\HOMEBREW\\.
Applies gentle pacing, custom headers, timeout handling, and backoff to avoid throttling.
"""

import os
import sys
import time
import json
import shutil
import urllib.request
import urllib.error
from pathlib import Path

CATALOG_URL = "https://raw.githubusercontent.com/DrDecki/VitaHomebrewDB/main/psp_apps.json"
LOCAL_DB = Path(r"c:\Users\Ben\Desktop\pxp2\database")
HDD_HOMEBREW = Path(r"G:\GAMES\PSP\HOMEBREW")
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

LOCAL_DB.mkdir(parents=True, exist_ok=True)
HDD_HOMEBREW.mkdir(parents=True, exist_ok=True)

import socket
socket.setdefaulttimeout(15)

def sanitize_filename(name):
    clean = "".join(c for c in name if c.isalnum() or c in " ._-()[]")
    return clean.strip().replace("  ", " ")

def download_with_retry(url, dest_path, max_retries=2, delay_between=0.4):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(max_retries):
        try:
            with urllib.request.urlopen(req, timeout=12) as resp:
                if resp.status == 200:
                    temp_path = str(dest_path) + ".tmp"
                    total_read = 0
                    with open(temp_path, "wb") as f:
                        while True:
                            chunk = resp.read(65536)
                            if not chunk:
                                break
                            f.write(chunk)
                            total_read += len(chunk)
                    if total_read > 500:
                        os.replace(temp_path, dest_path)
                        return True, total_read
                    if os.path.exists(temp_path):
                        os.remove(temp_path)
                    return False, "File too small (<500B)"
        except urllib.error.HTTPError as e:
            if e.code == 429 or e.code == 503:
                wait_time = (attempt + 1) * 2.0
                print(f"  [!] HTTP {e.code} throttling. Backing off {wait_time:.1f}s...", end=" ", flush=True)
                time.sleep(wait_time)
            elif e.code in [403, 404]:
                return False, f"HTTP {e.code}"
            else:
                time.sleep(0.5)
        except Exception as e:
            time.sleep(0.5)
    return False, "Timed out or failed"

def main():
    print("=" * 60)
    print("      PSP Homebrew Database Downloader & Synchronizer      ")
    print("=" * 60)
    print(f"[*] Target Local Database: {LOCAL_DB}")
    print(f"[*] Target HDD Homebrew:   {HDD_HOMEBREW}")
    print("[*] Fetching live catalog...")

    req = urllib.request.Request(CATALOG_URL, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            apps = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"[!] Failed to fetch catalog: {e}")
        return

    # Save catalog.json in database folder
    with open(LOCAL_DB / "catalog.json", "w", encoding="utf-8") as f:
        json.dump(apps, f, indent=2)

    print(f"[+] Found {len(apps)} homebrew applications in catalog.")
    print("[*] Beginning downloads with gentle rate limiting...")

    success_count = 0
    skip_count = 0
    fail_count = 0

    for idx, app in enumerate(apps, 1):
        name = app.get("name", f"App_{idx}")
        version = app.get("version", "")
        url = app.get("url", "").strip()
        if not url:
            continue

        # Determine extension from url
        ext = ".zip"
        if url.lower().endswith(".pbp"):
            ext = ".pbp"
        elif url.lower().endswith(".7z"):
            ext = ".7z"
        elif url.lower().endswith(".rar"):
            ext = ".rar"

        clean_name = sanitize_filename(f"{name} {version}".strip())
        filename = f"{clean_name}{ext}"
        local_file = LOCAL_DB / filename
        hdd_file = HDD_HOMEBREW / filename

        # Skip if already downloaded and non-empty
        if local_file.exists() and local_file.stat().st_size > 1024:
            if not hdd_file.exists():
                try:
                    shutil.copy2(str(local_file), str(hdd_file))
                except Exception:
                    pass
            skip_count += 1
            continue

        print(f"[{idx}/{len(apps)}] Downloading: {name} ({version})...", end=" ", flush=True)
        ok, res = download_with_retry(url, local_file)
        if ok:
            size_mb = res / (1024 * 1024)
            print(f"[OK] ({size_mb:.2f} MB)")
            success_count += 1
            try:
                shutil.copy2(str(local_file), str(hdd_file))
            except Exception:
                pass
        else:
            print(f"[FAILED: {res}]")
            fail_count += 1

        # Gentle delay to prevent server rate limiting
        time.sleep(0.4)

    print("=" * 60)
    print(f"[+] Completed! Newly downloaded: {success_count}, Already present: {skip_count}, Failed: {fail_count}")
    print(f"[+] Database folder now contains {len(list(LOCAL_DB.glob('*')))} items.")
    print(f"[+] HDD G:\\GAMES\\PSP\\HOMEBREW contains {len(list(HDD_HOMEBREW.glob('*')))} items.")
    print("=" * 60)

if __name__ == "__main__":
    main()
