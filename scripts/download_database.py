import os
import sys
import time
import json
import re
import urllib.request
import urllib.error
from urllib.parse import urlparse

CATALOG_URL = "https://raw.githubusercontent.com/DrDecki/VitaHomebrewDB/main/psp_apps.json"
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATABASE_DIR = os.path.join(BASE_DIR, "database")

EXTRA_TOP_HOMEBREW = [
    {
        "name": "Cave Story",
        "version": "v1.0",
        "author": "Studio Pixel / Roor",
        "type": "11",
        "size": "1832010",
        "url": "https://archive.org/download/cavestory-4end-psp.7z/cavestory-4end-psp.zip",
        "description": "The legendary 2D action-adventure platformer ported to PSP with original music and cutscenes."
    },
    {
        "name": "PSPident",
        "version": "v1.5",
        "author": "Yoti",
        "type": "14",
        "size": "142851",
        "url": "https://github.com/Yoti/psp_pspident/releases/download/v1.5/pspIdent_v1.5_20230922-030352.zip",
        "description": "Hardware and motherboard identification tool displaying motherboard model, tachyon, baryon revisions."
    }
]

def map_category(type_str):
    if type_str in ("1", "11"):
        return "Games"
    elif type_str in ("4", "12"):
        return "Ports"
    elif type_str == "15":
        return "Emulators"
    elif type_str == "14":
        return "Utilities"
    return "Games"

def sanitize_filename(name):
    clean = re.sub(r'[^a-zA-Z0-9._-]', '_', name.strip())
    clean = re.sub(r'_+', '_', clean).strip('_')
    return clean or "homebrew"

def get_extension(url):
    path = urlparse(url).path
    ext = os.path.splitext(path)[1].lower()
    if ext in ('.zip', '.pbp', '.7z', '.rar', '.vpk'):
        return ext
    return '.zip'

def download_file(url, target_path, item_name, max_retries=4):
    if os.path.exists(target_path) and os.path.getsize(target_path) > 0:
        print(f"  [EXISTS] {os.path.basename(target_path)} ({os.path.getsize(target_path)} bytes)")
        return True, "exists"

    temp_path = target_path + ".tmp"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept": "*/*"
    }

    for attempt in range(1, max_retries + 1):
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=45) as resp:
                status = resp.status if hasattr(resp, 'status') else resp.getcode()
                if status in (429, 503):
                    backoff = (2 ** attempt) + 1
                    print(f"  [THROTTLED {status}] Waiting {backoff}s before retry {attempt}/{max_retries}...")
                    time.sleep(backoff)
                    continue

                total_size = int(resp.headers.get('content-length', 0))
                downloaded = 0
                with open(temp_path, "wb") as f:
                    while True:
                        chunk = resp.read(65536)
                        if not chunk:
                            break
                        f.write(chunk)
                        downloaded += len(chunk)

                if os.path.exists(temp_path) and os.path.getsize(temp_path) > 0:
                    os.replace(temp_path, target_path)
                    print(f"  [DOWNLOADED] {os.path.basename(target_path)} ({downloaded / (1024*1024):.2f} MB)")
                    return True, "downloaded"

        except urllib.error.HTTPError as e:
            if e.code in (429, 503):
                backoff = (2 ** attempt) + 2
                print(f"  [HTTP {e.code}] Throttled. Backing off for {backoff}s (attempt {attempt}/{max_retries})...")
                time.sleep(backoff)
            elif e.code == 404:
                print(f"  [HTTP 404] Not Found for {url}")
                if os.path.exists(temp_path):
                    os.remove(temp_path)
                return False, f"HTTP 404: {url}"
            else:
                print(f"  [HTTP {e.code}] Error: {e.reason}")
                time.sleep(2)
        except Exception as e:
            print(f"  [ERROR] {e} (attempt {attempt}/{max_retries})")
            time.sleep(2)

        if os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except Exception:
                pass

    return False, "Failed after max retries"

def main():
    print("=== PSP Homebrew Database Downloader ===")
    os.makedirs(DATABASE_DIR, exist_ok=True)
    for cat in ["Games", "Ports", "Emulators", "Utilities"]:
        os.makedirs(os.path.join(DATABASE_DIR, cat), exist_ok=True)

    # 1. Fetch live catalog
    print(f"Fetching catalog from {CATALOG_URL}...")
    headers = {"User-Agent": "Mozilla/5.0"}
    req = urllib.request.Request(CATALOG_URL, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as resp:
        catalog_data = json.loads(resp.read().decode('utf-8'))

    # Merge extras
    names_in_catalog = {d.get('name', '').lower() for d in catalog_data}
    for extra in EXTRA_TOP_HOMEBREW:
        if extra['name'].lower() not in names_in_catalog:
            catalog_data.append(extra)

    print(f"Total catalog entries: {len(catalog_data)}")

    # Save index
    index_file = os.path.join(DATABASE_DIR, "catalog_index.json")
    with open(index_file, "w", encoding="utf-8") as f:
        json.dump(catalog_data, f, indent=2)
    print(f"Saved catalog index to {index_file}")

    # 2. Download items
    success_count = 0
    skipped_count = 0
    failed_count = 0
    failed_items = []

    total = len(catalog_data)
    for idx, item in enumerate(catalog_data, start=1):
        name = item.get("name", f"item_{idx}").strip()
        url = item.get("url", "").strip()
        version = item.get("version", "").strip()
        type_str = str(item.get("type", "11"))
        category = map_category(type_str)

        if not url:
            print(f"[{idx}/{total}] Skipping {name} (no URL)")
            continue

        ext = get_extension(url)
        clean_name = sanitize_filename(name)
        clean_ver = sanitize_filename(version) if version else ""
        filename = f"{clean_name}_{clean_ver}{ext}" if clean_ver else f"{clean_name}{ext}"

        cat_dir = os.path.join(DATABASE_DIR, category)
        target_path = os.path.join(cat_dir, filename)

        print(f"[{idx}/{total}] [{category}] {name} ({item.get('author', 'Unknown')})")
        ok, reason = download_file(url, target_path, name)

        if ok:
            if reason == "downloaded":
                success_count += 1
                # Gentle pacing between live downloads to avoid rate limits
                time.sleep(0.8)
            else:
                skipped_count += 1
        else:
            failed_count += 1
            failed_items.append((name, url, reason))

    print("\n=== Download Complete ===")
    print(f"Successfully downloaded: {success_count}")
    print(f"Already existed: {skipped_count}")
    print(f"Failed: {failed_count}")
    if failed_items:
        print("Failed items:")
        for name, url, reason in failed_items:
            print(f"  - {name}: {reason}")

if __name__ == "__main__":
    main()
