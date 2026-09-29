#!/usr/bin/env python3
"""
PXP2 Content Hub - High-Speed PSP Library & Streaming Server
Hosts PSP ISO games, UMD Video movies, and Homebrew from G:\\GAMES\\PSP
Provides local Wi-Fi browser UI and direct wireless ADB sync to connected devices.
"""

import os
import sys
import json
import shutil
import socket
import urllib.parse
import subprocess
import mimetypes
import re
import time
from pathlib import Path
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn

# PXP2's own helpers in tools/: PS1 -> POPS EBOOT converter, artwork lookup, PSP disc metadata.
sys.path.insert(0, str(Path(__file__).resolve().parent / "tools"))
import threading
import hashlib
try:
    import pxp_pops
    import pxp_art
    import pxp_psp
except Exception as e:  # the hub still serves PSP files without them (e.g. Pillow/numpy missing)
    print(f"[!] PS1 conversion / artwork disabled: {e}")
    pxp_pops = pxp_art = pxp_psp = None

# Packaged Windows build (PyInstaller): keep config and cache next to PXP2-Hub.exe, not inside the bundle.
HUB_DIR = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
CACHE_DIR = HUB_DIR / "hub_cache"      # converted EBOOTs, artwork, databases (--cache to move it)
POPS_BASE_DIR = HUB_DIR / "pops_base"  # put a DATA.PSP (or a PS1 EBOOT.PBP) you own here
POPS_DATA_PSP = None                   # --pops-data-psp
PS1_NOTE = "PS1 (runs on PSP; PXP2 support coming)"

DEFAULT_ROOT = r"G:\GAMES\PSP"
FALLBACK_ROOT = r"G:\games\psp"
DEFAULT_PS1_ROOT = r"G:\GAMES\PS1"
FALLBACK_PS1_ROOT = r"G:\games\ps1"
PORT = 8080
CONFIG_FILE = HUB_DIR / "server_config.json"

class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True

def get_machine_ips():
    ips = []
    try:
        hostname = socket.gethostname()
        for ip in socket.gethostbyname_ex(hostname)[2]:
            if not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    if not ips:
        ips.append("127.0.0.1")
    # Sort so 192.168.0.x is prioritized first
    ips.sort(key=lambda x: (not x.startswith("192.168.0."), not x.startswith("192.168."), x))
    return ips

def load_config():
    cfg = {}
    if CONFIG_FILE.exists():
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                cfg = json.load(f)
        except Exception:
            pass
    if "root_path" not in cfg:
        base = DEFAULT_ROOT if os.path.exists(DEFAULT_ROOT) else (FALLBACK_ROOT if os.path.exists(FALLBACK_ROOT) else os.getcwd())
        cfg["root_path"] = base
    return cfg


def ps1_root():
    """The PS1 library: the saved "ps1_root" setting, else a PS1 folder next to (or inside) the PSP library root."""
    if CONFIG.get("ps1_root"):
        return Path(CONFIG["ps1_root"])
    root = Path(CONFIG["root_path"])
    for cand in (root.parent / "PS1", root / "PS1", Path(DEFAULT_PS1_ROOT), Path(FALLBACK_PS1_ROOT)):
        if cand.is_dir():
            return cand
    return root.parent / "PS1"

def save_config(cfg):
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
    except Exception as e:
        print(f"Error saving config: {e}")

CONFIG = load_config()

def format_size(bytes_val):
    if bytes_val < 1024 * 1024:
        return f"{bytes_val / 1024:.1f} KB"
    elif bytes_val < 1024 * 1024 * 1024:
        return f"{bytes_val / (1024 * 1024):.1f} MB"
    else:
        return f"{bytes_val / (1024 * 1024 * 1024):.2f} GB"

# ---------------------------------------------------------------------------------------------
# PSP discs: PARAM.SFO metadata and the device file name "<TITLE> [ULUS-10081].iso"
# ---------------------------------------------------------------------------------------------
_meta_lock = threading.Lock()
_psp_meta = None


def psp_disc_meta(p):
    """{"title", "disc_id", "category"} from the disc's PARAM.SFO, cached by path + size + mtime."""
    global _psp_meta
    if pxp_psp is None or p.suffix.lower() not in (".iso", ".cso"):
        return {}
    st = p.stat()
    key = f"{p}|{st.st_size}|{int(st.st_mtime)}"
    cache_file = CACHE_DIR / "psp_meta.json"
    with _meta_lock:
        if _psp_meta is None:
            try:
                _psp_meta = json.loads(cache_file.read_text(encoding="utf-8"))
            except Exception:
                _psp_meta = {}
        if key in _psp_meta:
            return _psp_meta[key]
    try:
        sfo = pxp_psp.read_sfo(p)
        meta = {"title": sfo.get("TITLE", ""), "disc_id": sfo.get("DISC_ID", ""), "category": sfo.get("CATEGORY", "")}
    except Exception as e:
        meta = {"error": str(e)}
    with _meta_lock:
        _psp_meta[key] = meta
        try:
            CACHE_DIR.mkdir(parents=True, exist_ok=True)
            cache_file.write_text(json.dumps(_psp_meta, indent=0), encoding="utf-8")
        except OSError:
            pass
    return meta


def psp_device_name(p, meta):
    """The name the app saves a PSP disc under: "<TITLE> [ULUS-10081].iso" (the source keeps its own name)."""
    if pxp_psp and meta.get("title") and meta.get("disc_id"):
        return pxp_psp.device_filename(meta["title"], meta["disc_id"], p.suffix) or p.name
    return p.name


# ---------------------------------------------------------------------------------------------
# PS1 library: listing, artwork and conversion to POPS EBOOT.PBP
# ---------------------------------------------------------------------------------------------
_ps1_meta = {}
_ps1_locks = {}


def _ps1_entries(root):
    """Top-level folders and image files of the PS1 library (a .bin with a .cue beside it is covered by the cue)."""
    if not root.is_dir():
        return []
    out = []
    for e in sorted(root.iterdir(), key=lambda x: x.name.lower()):
        if e.name.startswith("."):
            continue
        if e.is_dir() or e.suffix.lower() in (".cue", ".iso", ".img", ".pbp"):
            out.append(e)
        elif e.suffix.lower() == ".bin" and not e.with_suffix(".cue").is_file():
            out.append(e)
    return out


def _entry_files(e):
    return [f for f in (e.rglob("*") if e.is_dir() else [e]) if f.is_file()]


def ps1_title(name):
    """'Legend of Dragoon, The (USA, Canada) (Disc 1)' -> 'The Legend of Dragoon (Disc 1)'."""
    base = re.sub(r"\s*[\(\[][^\)\]]*[\)\]]", "", name).strip()
    m = re.match(r"^(.*?),\s*(The|A|An)\b(.*)$", base)
    if m:
        base = f"{m.group(2)} {m.group(1)}{m.group(3)}"
    d = re.search(r"\((Disc\s*\d+)\)", name, re.I)
    return base + (f" ({d.group(1)})" if d else "")


def ps1_entry_meta(e):
    """Disc ID, title and image of one PS1 library entry (reads a few sectors; cached by size + mtime)."""
    files = _entry_files(e)
    size = sum(f.stat().st_size for f in files)
    key = (str(e), size, max((int(f.stat().st_mtime) for f in files), default=0))
    hit = _ps1_meta.get(str(e))
    if hit and hit["key"] == key:
        if hit.get("disc_id") and not hit.get("redump") and not hit.get("prebuilt") and pxp_art and pxp_art.dat_ready("ps1"):
            hit["redump"] = pxp_art.redump_name("ps1", hit["disc_id"])      # the name table arrived after the first listing
            if hit["redump"]:
                hit["title"] = ps1_title(hit["redump"])
        return hit
    meta = {"key": key, "size": size, "image": None, "disc_id": None, "title": None, "redump": None, "error": None}
    try:
        stem = e.name if e.is_dir() else e.stem
        if e.suffix.lower() == ".pbp":
            pbp = pxp_pops.read_pbp(e)
            sfo = pxp_pops.parse_sfo(pbp["PARAM.SFO"])
            if sfo.get("CATEGORY") != "ME":
                raise ValueError("not a PS1 EBOOT (CATEGORY is not ME)")
            meta.update(image=str(e), disc_id=sfo.get("DISC_ID"), title=sfo.get("TITLE") or stem, prebuilt=True)
        else:
            img = pxp_pops.pick_image(e)
            if img is None:
                meta["error"] = "no .cue/.bin/.img/.iso inside"
            else:
                meta["image"] = str(img)
                disc = pxp_pops.DiscImage(img)
                try:
                    meta["disc_id"] = pxp_pops.read_disc_id(disc)
                finally:
                    disc.close()
                if not meta["disc_id"]:
                    # no SYSTEM.CNF (a few early discs): a stable made-up ID so the device folder is unique
                    meta["disc_id"] = "PXPS" + str(int(hashlib.sha1(stem.encode()).hexdigest(), 16) % 100000).zfill(5)
                    meta["id_guessed"] = True
                try:  # the Redump name (never waits: the table downloads in the background on first use)
                    meta["redump"] = pxp_art.redump_name("ps1", meta["disc_id"]) if pxp_art.dat_ready("ps1") else None
                except Exception:
                    meta["redump"] = None
                meta["title"] = ps1_title(meta["redump"] or stem)
    except Exception as ex:
        meta["error"] = str(ex)
        meta["image"] = None
    _ps1_meta[str(e)] = meta
    return meta


def pops_data_psp():
    return pxp_pops.find_data_psp(POPS_DATA_PSP, [POPS_BASE_DIR]) if pxp_pops else (None, None)


def scan_ps1(root):
    items = []
    if pxp_pops is None:
        return items
    data_psp, _ = pops_data_psp()
    for e in _ps1_entries(root):
        m = ps1_entry_meta(e)
        if not m.get("image"):
            continue
        sid = m["disc_id"]
        pxp_art.ps1_art_async(sid, m["redump"] or m["title"])    # listing never waits for the network
        out = CACHE_DIR / "ps1" / sid / "EBOOT.PBP"
        d = re.search(r"\(Disc\s*(\d+)\)", e.name, re.I)
        items.append({
            "name": m["title"],
            "filename": e.name,
            "category": "PS1",
            "disc_id": sid,
            "size": m["size"],
            "size_formatted": format_size(m["size"]),
            "ext": "PS1",
            "source_format": Path(m["image"]).suffix.upper().lstrip("."),
            "disc_number": int(d.group(1)) if d else 1,
            "artwork_icon": f"/art/ps1/{sid}/icon0.png",
            "artwork_bg": f"/art/ps1/{sid}/pic1.png",
            "artwork_box": f"/art/ps1/{sid}/box.png",
            "device_folder": sid,
            "filename_for_device": "EBOOT.PBP",
            "device_path": f"PSP/GAME/{sid}/EBOOT.PBP",
            "converted": bool(m.get("prebuilt")) or out.is_file(),
            "pops_ready": bool(m.get("prebuilt")) or data_psp is not None,
            "note": PS1_NOTE,
        })
    return items


def ps1_eboot(entry_name, progress=None):
    """(path, info) of the EBOOT.PBP for a PS1 library entry, converted once and cached in <cache>/ps1/<DISC_ID>/."""
    root = ps1_root()
    e = (root / entry_name).resolve()
    if root.resolve() not in e.parents or not e.exists():
        raise FileNotFoundError(entry_name)
    m = ps1_entry_meta(e)
    if not m.get("image"):
        raise ValueError(m.get("error") or "no disc image")
    if m.get("prebuilt"):
        return Path(m["image"]), m
    sid = m["disc_id"]
    out_dir = CACHE_DIR / "ps1" / sid
    out = out_dir / "EBOOT.PBP"
    lock = _ps1_locks.setdefault(sid, threading.Lock())
    with lock:
        data_psp, dsrc = pops_data_psp()
        art = pxp_art.ps1_art(sid, m["redump"] or m["title"]) or {}   # normally cached by the listing already
        art_dir = CACHE_DIR / "art" / "ps1" / sid
        icon0 = (art_dir / "icon0.png").read_bytes() if art.get("icon0") else None
        pic1 = (art_dir / "pic1.png").read_bytes() if art.get("pic1") else None
        want = {"v": pxp_pops.CONVERTER_VERSION, "src": list(m["key"]),
                "data_psp": hashlib.sha1(data_psp).hexdigest() if data_psp else None,
                "art": hashlib.sha1((icon0 or b"") + (pic1 or b"")).hexdigest()}
        info_file = out_dir / "build.json"
        try:
            have = json.loads(info_file.read_text(encoding="utf-8"))
        except Exception:
            have = None
        if out.is_file() and have and have.get("want") == want:
            return out, have
        print(f"[PS1] converting {e.name} -> {out} ({sid}, DATA.PSP: {dsrc or 'none, placeholder'})")
        t0 = time.time()
        res = pxp_pops.build_eboot(m["image"], out, sid, m["title"], icon0=icon0, pic1=pic1, data_psp=data_psp,
                                   progress=progress)
        res.update(want=want, seconds=round(time.time() - t0, 1), data_psp_source=dsrc,
                   art_sources=art.get("sources"), source=str(e))
        info_file.write_text(json.dumps(res, indent=1), encoding="utf-8")
        print(f"[PS1] done in {res['seconds']} s: {format_size(res['size'])}, pops_ready={res['pops_ready']}")
        return out, res


def content_disposition(name):
    """attachment header with an ASCII fallback and the exact UTF-8 name (RFC 6266 / 5987)."""
    ascii_name = name.encode("ascii", "replace").decode("ascii").replace('"', "'").replace("?", "_")
    return f"attachment; filename=\"{ascii_name}\"; filename*=UTF-8''{urllib.parse.quote(name, safe='')}"


def scan_library(root_path):
    root = Path(root_path)
    games_dir = root / "GAMES"
    videos_dir = root / "VIDEOS"
    homebrew_dir = root / "HOMEBREW"

    games_dir.mkdir(parents=True, exist_ok=True)
    videos_dir.mkdir(parents=True, exist_ok=True)
    homebrew_dir.mkdir(parents=True, exist_ok=True)

    catalog = {"games": [], "videos": [], "homebrew": [], "ps1": []}

    # Scan GAMES
    if games_dir.exists():
        for p in sorted(games_dir.glob("*")):
            if p.is_file() and p.suffix.lower() in [".iso", ".cso", ".pbp"]:
                item = {
                    "name": p.stem,
                    "filename": p.name,
                    "category": "GAMES",
                    "size": p.stat().st_size,
                    "size_formatted": format_size(p.stat().st_size),
                    "ext": p.suffix.upper().replace(".", "")
                }
                meta = psp_disc_meta(p)
                if meta.get("disc_id"):
                    # the app saves the disc as "<TITLE> [ULUS-10081].iso"; the file on the PC keeps its name
                    item["title"] = meta.get("title", "")
                    item["disc_id"] = meta["disc_id"]
                    item["filename_for_device"] = psp_device_name(p, meta)
                    item["umd_label"] = f"/api/art/umd?id={meta['disc_id']}"
                    item["artwork_box"] = f"/api/art/box?sys=psp&id={meta['disc_id']}"
                    if pxp_art and meta.get("category") != "MG":
                        pxp_art.psp_art_async(meta["disc_id"])
                catalog["games"].append(item)

    # Scan VIDEOS
    if videos_dir.exists():
        for p in sorted(videos_dir.glob("*")):
            if p.is_file() and p.suffix.lower() in [".iso", ".cso", ".mp4"]:
                catalog["videos"].append({
                    "name": p.stem,
                    "filename": p.name,
                    "category": "VIDEOS",
                    "size": p.stat().st_size,
                    "size_formatted": format_size(p.stat().st_size),
                    "ext": p.suffix.upper().replace(".", "")
                })

    # Scan HOMEBREW
    if homebrew_dir.exists():
        for p in sorted(homebrew_dir.glob("*")):
            if p.is_file() and p.suffix.lower() in [".zip", ".pbp"]:
                catalog["homebrew"].append({
                    "name": p.stem,
                    "filename": p.name,
                    "category": "HOMEBREW",
                    "size": p.stat().st_size,
                    "size_formatted": format_size(p.stat().st_size),
                    "ext": p.suffix.upper().replace(".", "")
                })
            elif p.is_dir():
                eboot = p / "EBOOT.PBP"
                size = sum(f.stat().st_size for f in p.rglob("*") if f.is_file())
                catalog["homebrew"].append({
                    "name": p.name,
                    "filename": p.name,
                    "category": "HOMEBREW",
                    "size": size,
                    "size_formatted": format_size(size),
                    "ext": "FOLDER",
                    "is_dir": True
                })

    # Scan PS1 (converted to POPS EBOOT.PBP on download)
    ps1_dir = ps1_root()
    try:
        catalog["ps1"] = scan_ps1(ps1_dir)
    except Exception as e:
        print(f"[!] Error scanning PS1 library from {ps1_dir}: {e}")

    return catalog

def get_connected_devices():
    devices = []
    try:
        out = subprocess.check_output(["adb", "devices", "-l"], text=True, timeout=5)
        for line in out.splitlines():
            line = line.strip()
            if not line or line.startswith("List of devices"):
                continue
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                serial = parts[0]
                model = "Android Device"
                for part in parts[2:]:
                    if part.startswith("model:"):
                        model = part.split(":", 1)[1].replace("_", " ")
                devices.append({"serial": serial, "model": model})
    except Exception:
        pass
    return devices

HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>PXP2 Content Hub</title>
<style>
  :root {
    --bg-primary: #150824;
    --bg-secondary: #210d38;
    --card-bg: rgba(255, 255, 255, 0.07);
    --card-border: rgba(255, 255, 255, 0.12);
    --accent: #b46bff;
    --accent-glow: rgba(180, 107, 255, 0.4);
    --text-primary: #ffffff;
    --text-muted: #b8a6cc;
    --success: #00e676;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    background: radial-gradient(circle at 50% 0%, #3a1359 0%, #150824 75%);
    color: var(--text-primary);
    min-height: 100vh;
    padding: 24px 16px;
  }
  .container { max-width: 1080px; margin: 0 auto; }
  header {
    display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center;
    padding-bottom: 24px; border-bottom: 1px solid var(--card-border); gap: 16px;
  }
  .brand { display: flex; align-items: center; gap: 14px; }
  .logo {
    width: 48px; height: 48px; border-radius: 14px;
    background: linear-gradient(135deg, #b46bff, #7a2a9c);
    display: flex; align-items: center; justify-content: center;
    font-size: 24px; font-weight: bold; box-shadow: 0 4px 16px var(--accent-glow);
  }
  .title-group h1 { font-size: 26px; font-weight: 700; letter-spacing: 0.5px; }
  .title-group p { font-size: 14px; color: var(--text-muted); }
  .ip-badges { display: flex; flex-wrap: wrap; gap: 8px; }
  .ip-badge {
    background: rgba(180, 107, 255, 0.15); border: 1px solid var(--accent);
    color: #e4c4ff; padding: 6px 14px; border-radius: 20px; font-size: 13px; font-family: monospace;
  }
  .path-bar {
    background: var(--card-bg); border: 1px solid var(--card-border);
    border-radius: 14px; padding: 14px 18px; margin: 24px 0;
    display: flex; gap: 12px; align-items: center;
  }
  .path-input {
    flex: 1; background: rgba(0,0,0,0.3); border: 1px solid var(--card-border);
    border-radius: 8px; color: white; padding: 8px 12px; font-size: 14px; font-family: monospace;
  }
  .btn {
    background: var(--accent); color: white; border: none; padding: 9px 18px;
    border-radius: 8px; font-weight: 600; cursor: pointer; transition: 0.2s; font-size: 14px;
  }
  .btn:hover { background: #c384ff; box-shadow: 0 0 12px var(--accent-glow); }
  .btn-outline {
    background: transparent; border: 1px solid var(--card-border); color: var(--text-muted);
  }
  .btn-outline:hover { background: rgba(255,255,255,0.1); color: white; }
  .tabs { display: flex; gap: 10px; margin-bottom: 20px; flex-wrap: wrap; }
  .tab-btn {
    background: var(--card-bg); border: 1px solid var(--card-border);
    color: var(--text-muted); padding: 10px 22px; border-radius: 24px;
    font-size: 14px; font-weight: 600; cursor: pointer; transition: 0.2s;
  }
  .tab-btn.active {
    background: var(--accent); color: white; border-color: var(--accent);
    box-shadow: 0 4px 14px var(--accent-glow);
  }
  .grid {
    display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
    gap: 16px; margin-top: 10px;
  }
  .card {
    background: var(--card-bg); border: 1px solid var(--card-border);
    border-radius: 16px; padding: 18px; display: flex; flex-direction: column;
    justify-content: space-between; transition: transform 0.15s, border-color 0.15s;
  }
  .card:hover { transform: translateY(-2px); border-color: rgba(180, 107, 255, 0.4); }
  .card-top { display: flex; gap: 14px; align-items: center; margin-bottom: 14px; }
  .icon-box {
    width: 48px; height: 48px; border-radius: 12px;
    background: rgba(255,255,255,0.1); display: flex; align-items: center;
    justify-content: center; font-size: 22px; flex-shrink: 0;
  }
  .card-info { overflow: hidden; }
  .card-title {
    font-size: 16px; font-weight: 600; white-space: nowrap;
    overflow: hidden; text-overflow: ellipsis; margin-bottom: 4px;
  }
  .card-meta { font-size: 12px; color: var(--text-muted); display: flex; gap: 8px; align-items: center; }
  .tag {
    background: rgba(180, 107, 255, 0.2); color: #d4aaff;
    padding: 2px 7px; border-radius: 6px; font-size: 11px; font-weight: 600;
  }
  .card-actions { display: flex; gap: 8px; margin-top: 8px; }
  .device-section {
    background: rgba(0, 230, 118, 0.08); border: 1px solid rgba(0, 230, 118, 0.3);
    border-radius: 14px; padding: 14px 18px; margin: 20px 0;
    display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px;
  }
  .device-list { display: flex; flex-wrap: wrap; gap: 8px; }
  .device-pill {
    background: rgba(0,0,0,0.4); border: 1px solid var(--success);
    color: var(--success); padding: 4px 12px; border-radius: 16px; font-size: 12px; font-weight: 600;
  }
  .empty { text-align: center; padding: 48px; color: var(--text-muted); grid-column: 1 / -1; }
  .toast {
    position: fixed; bottom: 24px; right: 24px; background: #222; color: #fff;
    padding: 12px 20px; border-radius: 10px; border-left: 4px solid var(--accent);
    box-shadow: 0 6px 20px rgba(0,0,0,0.5); display: none; z-index: 999;
  }
</style>
</head>
<body>
<div class="container">
  <header>
    <div class="brand">
      <div class="logo">P</div>
      <div class="title-group">
        <h1>PXP2 Content Hub</h1>
        <p>High-Speed Wireless PSP Library & Streaming Server</p>
      </div>
    </div>
    <div class="ip-badges">
      <!-- IP Badges inserted here -->
      __IP_BADGES__
    </div>
  </header>

  <div class="device-section">
    <div>
      <strong>📡 Wireless ADB Connected Devices:</strong>
      <span style="font-size: 13px; color: var(--text-muted); margin-left: 8px;">Direct push available</span>
    </div>
    <div class="device-list" id="deviceList">
      Loading devices...
    </div>
  </div>

  <div class="path-bar">
    <span style="font-size: 18px;">📁</span>
    <span style="font-size: 14px; font-weight: 600;">Library Folder:</span>
    <input type="text" class="path-input" id="rootInput" value="__CURRENT_ROOT__">
    <button class="btn" onclick="saveRoot()">Change Folder</button>
  </div>

  <div class="tabs">
    <button class="tab-btn active" onclick="switchTab('games')">🎮 Games (<span id="count-games">0</span>)</button>
    <button class="tab-btn" onclick="switchTab('videos')">🎬 Videos / Movies (<span id="count-videos">0</span>)</button>
    <button class="tab-btn" onclick="switchTab('homebrew')">🕹️ Homebrew (<span id="count-homebrew">0</span>)</button>
    <button class="tab-btn" onclick="switchTab('ps1')">💿 PS1 Games (<span id="count-ps1">0</span>)</button>
  </div>

  <div class="grid" id="contentGrid"></div>
</div>

<div class="toast" id="toast"></div>

<script>
let catalog = { games: [], videos: [], homebrew: [], ps1: [] };
let devices = [];
let currentTab = 'games';

function showToast(msg) {
  const t = document.getElementById('toast');
  t.innerText = msg;
  t.style.display = 'block';
  setTimeout(() => t.style.display = 'none', 3500);
}

async function loadData() {
  try {
    const res = await fetch('/api/catalog');
    catalog = await res.json();
    document.getElementById('count-games').innerText = (catalog.games || []).length;
    document.getElementById('count-videos').innerText = (catalog.videos || []).length;
    document.getElementById('count-homebrew').innerText = (catalog.homebrew || []).length;
    document.getElementById('count-ps1').innerText = (catalog.ps1 || []).length;
    renderTab();
  } catch(e) {
    showToast('Failed to load catalog');
  }

  try {
    const dRes = await fetch('/api/devices');
    devices = await dRes.json();
    const dList = document.getElementById('deviceList');
    if (devices.length === 0) {
      dList.innerHTML = '<span style="color:#aaa; font-size:13px;">No ADB devices found</span>';
    } else {
      dList.innerHTML = devices.map(d => `<span class="device-pill">● ${d.model} (${d.serial})</span>`).join('');
    }
  } catch(e) {}
}

function switchTab(tab) {
  currentTab = tab;
  document.querySelectorAll('.tab-btn').forEach(b => {
    b.classList.toggle('active', b.getAttribute('onclick').includes("'" + tab + "'"));
  });
  renderTab();
}

function renderTab() {
  const grid = document.getElementById('contentGrid');
  const items = catalog[currentTab] || [];
  if (items.length === 0) {
    // no Windows paths here: in this Python string a trailing backslash ended the JS string and broke the page
    const folderHint = currentTab === 'ps1' ? 'the PS1 library folder (next to the PSP library, or --ps1-root)' : `the ${currentTab.toUpperCase()} folder of the library`;
    grid.innerHTML = `<div class="empty">No items found in ${currentTab.toUpperCase()} folder. Place files into ${folderHint} to populate.</div>`;
    return;
  }

  grid.innerHTML = items.map(item => {
    let iconBox = '';
    if (currentTab === 'ps1' && item.artwork_icon) {
      iconBox = `<div class="icon-box" style="background:#151520; overflow:hidden; border:1px solid rgba(255,255,255,0.1);"><img src="${item.artwork_icon}" style="width:100%; height:100%; object-fit:contain;" onerror="this.outerHTML='💿'"></div>`;
    } else {
      const icon = currentTab === 'games' ? '🎮' : (currentTab === 'videos' ? '🎬' : (currentTab === 'ps1' ? '💿' : '🕹️'));
      iconBox = `<div class="icon-box">${icon}</div>`;
    }
    const dlUrl = `/download?cat=${currentTab}&file=${encodeURIComponent(item.filename)}`;
    
    let deviceButtons = '';
    if (devices.length > 0) {
      deviceButtons = devices.map(d => 
        `<button class="btn btn-outline" style="font-size:11px; padding:4px 8px;" onclick="pushToDevice('${currentTab}', '${encodeURIComponent(item.filename)}', '${d.serial}')">Push to ${d.model.split(' ')[0]}</button>`
      ).join('');
    }

    const badgeLabel = currentTab === 'ps1' ? 'PS1' : item.ext;
    const discTag = item.disc_id ? `<span class="tag" style="background:rgba(255,255,255,0.1); color:#aaa;">${item.disc_id}</span>` : '';
    const saveAs = item.filename_for_device ? (currentTab === 'ps1' ? (item.device_path || item.filename_for_device) : item.filename_for_device) : item.filename;
    const note = currentTab === 'ps1'
      ? `<div style="font-size:11px; color:#ffd27a; margin-top:4px;">${item.note || 'PS1 (runs on PSP; PXP2 support coming)'}${item.pops_ready ? '' : ' · needs a DATA.PSP in pops_base to boot on a PSP'}</div>`
      : (item.filename_for_device && item.filename_for_device !== item.filename ? `<div style="font-size:11px; color:var(--text-muted); margin-top:4px;">Saved on the device as ${item.filename_for_device}</div>` : '');

    return `
      <div class="card">
        <div>
          <div class="card-top">
            ${iconBox}
            <div class="card-info">
              <div class="card-title" title="${item.filename}">${item.name}</div>
              <div class="card-meta">
                <span class="tag">${badgeLabel}</span>
                ${discTag}
                <span>${item.size_formatted}</span>
              </div>
              ${note}
            </div>
          </div>
        </div>
        <div>
          <div class="card-actions">
            <a href="${dlUrl}" class="btn" style="text-decoration:none; display:inline-block;" download="${currentTab === 'ps1' ? 'EBOOT.PBP' : saveAs}" title="${saveAs}">⬇ Download ${currentTab === 'ps1' ? 'EBOOT' : ''}</a>
            <button class="btn btn-outline" onclick="copyLink('${dlUrl}')">Copy URL</button>
          </div>
          ${deviceButtons ? `<div style="margin-top:10px; display:flex; flex-wrap:wrap; gap:6px;">${deviceButtons}</div>` : ''}
        </div>
      </div>
    `;
  }).join('');
}

function copyLink(relUrl) {
  const full = window.location.origin + relUrl;
  navigator.clipboard.writeText(full);
  showToast('Copied download URL: ' + full);
}

async function saveRoot() {
  const path = document.getElementById('rootInput').value.trim();
  const res = await fetch('/api/set_root', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ root: path })
  });
  const data = await res.json();
  if (data.ok) {
    showToast('Library folder updated!');
    loadData();
  } else {
    showToast('Error: ' + data.error);
  }
}

async function pushToDevice(cat, encFilename, serial) {
  showToast(`Pushing ${decodeURIComponent(encFilename)} to device... please wait.`);
  try {
    const res = await fetch('/api/push_device', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ cat, filename: decodeURIComponent(encFilename), serial })
    });
    const d = await res.json();
    if (d.ok) {
      showToast('Successfully pushed to device!');
    } else {
      showToast('Push failed: ' + d.error);
    }
  } catch(e) {
    showToast('Error pushing to device: ' + e);
  }
}

loadData();
setInterval(() => {
  fetch('/api/devices').then(r => r.json()).then(d => {
    devices = d;
    const dList = document.getElementById('deviceList');
    if (devices.length === 0) {
      dList.innerHTML = '<span style="color:#aaa; font-size:13px;">No ADB devices found</span>';
    } else {
      dList.innerHTML = devices.map(x => `<span class="device-pill">● ${x.model} (${x.serial})</span>`).join('');
    }
  }).catch(()=>{});
}, 10000);
</script>
</body>
</html>
"""

class ContentHandler(BaseHTTPRequestHandler):
    # HTTP/1.1 keeps connections alive between the app's many small Range reads (every response sets Content-Length).
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        # Clean logging
        pass

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        params = urllib.parse.parse_qs(parsed.query)

        if path == "/":
            self.handle_index()
        elif path == "/api/ping":
            # Lightweight identity check used by the PXP2 app's auto-detection.
            body = json.dumps({"pxp2_hub": True, "port": PORT}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif path == "/api/catalog":
            self.handle_catalog()
        elif path == "/api/devices":
            self.handle_devices()
        elif path == "/download":
            self.handle_download(params)
        elif path.startswith("/art/ps1/"):
            # /art/ps1/<DISC_ID>/<icon0|pic1|box>.png, prepared in the background when the game is listed
            parts = path.split("/")
            if len(parts) == 5 and pxp_art and re.match(r"^[A-Z]{4}\d{5}$", parts[3]) and parts[4] in ("icon0.png", "pic1.png", "box.png"):
                f = pxp_art.art_file("ps1", parts[3], parts[4], wait=20)
                if f:
                    self.serve_single_file(f, mime="image/png")
                    return
            self.send_error(404, "Artwork not found")
        elif path in ("/api/art/umd", "/api/art/box"):
            self.handle_psp_art(path.rsplit("/", 1)[1], params)
        else:
            self.send_error(404, "Not Found")

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        content_len = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_len).decode("utf-8") if content_len > 0 else "{}"
        try:
            payload = json.loads(body)
        except Exception:
            payload = {}

        if parsed.path == "/api/set_root":
            new_root = payload.get("root", "").strip()
            if os.path.exists(new_root):
                CONFIG["root_path"] = new_root
                save_config(CONFIG)
                self.send_json({"ok": True, "root": new_root})
            else:
                self.send_json({"ok": False, "error": "Directory does not exist on host"}, 400)
        elif parsed.path == "/api/push_device":
            cat = payload.get("cat")
            filename = payload.get("filename")
            serial = payload.get("serial")
            if not cat or not filename or not serial:
                self.send_json({"ok": False, "error": "Missing parameters"}, 400)
                return

            if cat.upper() == "PS1":
                try:
                    eboot_path, info = ps1_eboot(filename)
                    sid = info.get("disc_id") or ps1_entry_meta((ps1_root() / filename).resolve())["disc_id"]
                    remote_dir = f"/sdcard/PXP/PSP/GAME/{sid}"
                    subprocess.run(["adb", "-s", serial, "shell", f"mkdir -p {remote_dir}"], check=True, timeout=10)
                    subprocess.run(["adb", "-s", serial, "push", str(eboot_path), f"{remote_dir}/EBOOT.PBP"], check=True, timeout=600)
                    self.send_json({"ok": True, "pushed": f"PSP/GAME/{sid}/EBOOT.PBP", "device": serial})
                except Exception as e:
                    self.send_json({"ok": False, "error": str(e)}, 500)
                return

            src_file = Path(CONFIG["root_path"]) / cat / filename
            if not src_file.exists():
                self.send_json({"ok": False, "error": "Source file not found"}, 404)
                return

            dest_folder = "/sdcard/PXP/ISO/" if cat in ["GAMES", "games"] else ("/sdcard/PXP/VIDEO/" if cat in ["VIDEOS", "videos"] else "/sdcard/PXP/PSP/GAME/")
            dest = dest_folder
            if cat.upper() == "GAMES":
                dest = dest_folder + psp_device_name(src_file, psp_disc_meta(src_file))  # "<TITLE> [ULUS-10081].iso"
            try:
                cmd = ["adb", "-s", serial, "push", str(src_file), dest]
                subprocess.run(cmd, check=True, timeout=180)
                self.send_json({"ok": True, "pushed": filename, "device": serial})
            except Exception as e:
                self.send_json({"ok": False, "error": str(e)}, 500)
        else:
            self.send_error(404, "Not Found")

    def handle_index(self):
        ips = get_machine_ips()
        ip_badges = "".join([f'<span class="ip-badge">http://{ip}:{PORT}</span>' for ip in ips])
        html = HTML_TEMPLATE.replace("__IP_BADGES__", ip_badges).replace("__CURRENT_ROOT__", CONFIG["root_path"]).replace("__PS1_ROOT__", str(ps1_root()))
        data = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def handle_catalog(self):
        catalog = scan_library(CONFIG["root_path"])
        self.send_json(catalog)

    def handle_devices(self):
        devs = get_connected_devices()
        self.send_json(devs)

    def handle_download(self, params):
        cat = params.get("cat", [""])[0].upper()
        filename = params.get("file", [""])[0]

        if not cat or not filename or ".." in filename:
            self.send_error(400, "Invalid file request")
            return

        if cat == "PS1":
            # Converted to a POPS EBOOT.PBP on first request (cached); the app saves it as PSP/GAME/<DISC_ID>/EBOOT.PBP
            if pxp_pops is None:
                self.send_error(503, "PS1 conversion unavailable on this hub")
                return
            try:
                eboot_path, _ = ps1_eboot(filename)
            except FileNotFoundError:
                self.send_error(404, f"PS1 game '{filename}' not found")
                return
            except Exception as e:
                self.send_error(500, f"Error converting PS1 game: {e}")
                return
            self.serve_single_file(eboot_path, download_name="EBOOT.PBP", mime="application/octet-stream")
            return

        base = (Path(CONFIG["root_path"]) / cat).resolve()
        file_path = (base / filename).resolve()
        if base not in file_path.parents:
            self.send_error(400, "Invalid file request")
            return
        if cat == "HOMEBREW" and file_path.is_dir():
            # A homebrew folder is sent as a zip of that folder (the app unpacks the EBOOT.PBP's folder).
            self.send_folder_zip(file_path)
            return
        if not file_path.exists() or not file_path.is_file():
            self.send_error(404, "File not found")
            return

        download_name = filename
        if cat == "GAMES":
            download_name = psp_device_name(file_path, psp_disc_meta(file_path))  # "<TITLE> [ULUS-10081].iso"
        self.serve_single_file(file_path, download_name=download_name)

    def handle_psp_art(self, kind, params):
        """/api/art/umd?id=ULUS10567 (disc label cut from a UMD scan, 512x512 PNG, disc inscribed) and
        /api/art/box?id=...&sys=psp|ps1 (front cover). 404 when there is none (yet)."""
        sid = re.sub(r"[^A-Za-z0-9]", "", params.get("id", [""])[0]).upper()
        system = params.get("sys", ["psp"])[0]
        if not pxp_art or not re.match(r"^[A-Z]{4}\d{5}$", sid) or system not in ("psp", "ps1"):
            self.send_error(404, "No artwork")
            return
        if system == "psp":
            f = pxp_art.psp_art_async(sid)
            fname = "umd_label.png" if kind == "umd" else "box.png"
            found = pxp_art.art_file("psp", sid, fname, wait=25 if f else 0)
        else:
            if kind == "umd":
                self.send_error(404, "No artwork")
                return
            found = pxp_art.art_file("ps1", sid, "box.png", wait=20)
        if found:
            self.serve_single_file(found, mime="image/png")
        elif system == "psp" and pxp_art.launchbox_building():
            self.send_error(503, "Artwork index is still being built; try again in a few minutes")  # not a real miss
        else:
            self.send_error(404, "No artwork")

    def serve_single_file(self, file_path, download_name=None, mime=None):
        file_size = file_path.stat().st_size
        range_header = self.headers.get("Range")

        if not mime:
            mime, _ = mimetypes.guess_type(str(file_path))
            if not mime:
                mime = "application/octet-stream"

        if range_header and range_header.startswith("bytes="):
            # Range requests (RFC 7233, one range): ISO streaming reads 256 KiB chunks, video seeks, resumable downloads.
            try:
                first, _, last = range_header[6:].split(",")[0].strip().partition("-")
                if first == "":  # suffix range: the last N bytes
                    start, end = max(0, file_size - int(last)), file_size - 1
                else:
                    start = int(first)
                    end = min(int(last), file_size - 1) if last else file_size - 1
            except ValueError:
                self.send_error(400, "Bad Range header")
                return
            if start >= file_size or start > end:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{file_size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return

            length = end - start + 1
            self.send_response(206)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Range", f"bytes {start}-{end}/{file_size}")
            self.send_header("Content-Length", str(length))
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()

            with open(file_path, "rb") as f:
                f.seek(start)
                remaining = length
                chunk_size = 64 * 1024
                while remaining > 0:
                    read_len = min(remaining, chunk_size)
                    chunk = f.read(read_len)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        else:
            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(file_size))
            self.send_header("Accept-Ranges", "bytes")
            if download_name:
                self.send_header("Content-Disposition", content_disposition(download_name))
            self.end_headers()

            with open(file_path, "rb") as f:
                shutil.copyfileobj(f, self.wfile, length=128 * 1024)

    def send_folder_zip(self, folder):
        import tempfile, zipfile
        with tempfile.TemporaryFile() as tmp:
            with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
                for f in sorted(folder.rglob("*")):
                    if f.is_file():
                        z.write(f, f"{folder.name}/{f.relative_to(folder).as_posix()}")
            size = tmp.tell()
            tmp.seek(0)
            self.send_response(200)
            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Length", str(size))
            self.send_header("Content-Disposition", f'attachment; filename="{folder.name}.zip"')
            self.end_headers()
            shutil.copyfileobj(tmp, self.wfile, length=128 * 1024)

    def send_json(self, data, code=200):
        body = json.dumps(data).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

DISCOVERY_PORT = 8081


def discovery_responder():
    """Answers the PXP2 app's UDP broadcast "PXP2_DISCOVER" with "PXP2_HUB <http port>" so no IP has to be typed."""
    import threading
    def run():
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("0.0.0.0", DISCOVERY_PORT))
        except OSError as e:
            print(f"[!] Auto-discovery disabled (UDP {DISCOVERY_PORT} unavailable: {e}); the app will fall back to a subnet scan.")
            return
        while True:
            try:
                data, addr = sock.recvfrom(256)
                if data.strip() == b"PXP2_DISCOVER":
                    sock.sendto(f"PXP2_HUB {PORT}".encode("ascii"), addr)
            except OSError:
                pass
    threading.Thread(target=run, name="pxp2-discovery", daemon=True).start()


def rename_sources(apply):
    """Explicit, off-by-default option: renames the PSP discs in <root>/GAMES to "<TITLE> [ULUS-10081].iso"."""
    games = Path(CONFIG["root_path"]) / "GAMES"
    n = 0
    for p in sorted(games.glob("*")):
        if not (p.is_file() and p.suffix.lower() in (".iso", ".cso")):
            continue
        new = psp_device_name(p, psp_disc_meta(p))
        if new == p.name:
            continue
        target = p.with_name(new)
        if target.exists():
            print(f"  skip (exists): {p.name} -> {new}")
            continue
        print(f"  {'rename' if apply else 'would rename'}: {p.name} -> {new}")
        if apply:
            p.rename(target)
        n += 1
    print(f"[*] {n} file(s) {'renamed' if apply else 'to rename (dry run; add --rename-sources to apply)'}")


def main():
    global PORT, CACHE_DIR, POPS_DATA_PSP
    import argparse
    ap = argparse.ArgumentParser(description="PXP2 Wireless Content Hub")
    ap.add_argument("--port", type=int, default=PORT, help="HTTP port (default 8080)")
    ap.add_argument("--root", help="library folder with GAMES/VIDEOS/HOMEBREW (default: saved setting)")
    ap.add_argument("--no-discovery", action="store_true", help="do not answer UDP discovery broadcasts")
    ap.add_argument("--ps1-root", help="PS1 library folder (default: saved setting, else PS1 next to the PSP root)")
    ap.add_argument("--cache", help="cache folder for converted EBOOTs, artwork and databases (default: hub_cache)")
    ap.add_argument("--pops-data-psp", help="DATA.PSP, or a PS1 EBOOT.PBP you own, to take the POPS boot module from")
    ap.add_argument("--rename-sources", action="store_true",
                    help="rename the PSP discs in GAMES on this PC to '<TITLE> [ULUS-10081].iso', then exit (off by default)")
    ap.add_argument("--rename-sources-dry-run", action="store_true", help="only list what --rename-sources would rename")
    args = ap.parse_args()
    PORT = args.port
    if args.root:
        CONFIG["root_path"] = args.root  # this run only; the saved setting is unchanged
        if not args.ps1_root:
            CONFIG.pop("ps1_root", None)
    if args.ps1_root:
        CONFIG["ps1_root"] = args.ps1_root  # this run only
    if args.cache:
        CACHE_DIR = Path(args.cache)
    if pxp_art:
        pxp_art.set_cache(CACHE_DIR)
    POPS_DATA_PSP = args.pops_data_psp
    if args.rename_sources or args.rename_sources_dry_run:
        rename_sources(apply=args.rename_sources and not args.rename_sources_dry_run)
        return
    ips = get_machine_ips()
    primary_ip = ips[0] if ips else "127.0.0.1"

    print("=" * 60)
    print("           PXP2 Wireless Content Hub Server")
    print("=" * 60)
    print(f"[*] Serving PSP library from: {CONFIG['root_path']}")
    print(f"[*] PS1 library (converted to PSP EBOOTs): {ps1_root()}")
    if pxp_pops:
        _, dsrc = pops_data_psp()
        print(f"[*] POPS DATA.PSP: {dsrc or 'none - PS1 EBOOTs get a placeholder and will not boot on a PSP (see psp.md, PS1 games and artwork)'}")
    print(f"[*] Cache: {CACHE_DIR}")
    print(f"[*] Local access:   http://localhost:{PORT}")
    print(f"[*] Network access: http://{primary_ip}:{PORT}")
    if len(ips) > 1:
        for ip in ips[1:]:
            print(f"                    http://{ip}:{PORT}")
    print("=" * 60)
    print("[*] Press Ctrl+C to stop the server at any time.")
    print("")

    if not args.no_discovery:
        discovery_responder()
        print(f"[*] Auto-discovery: answering PXP2 app broadcasts on UDP {DISCOVERY_PORT}")
    server = ThreadedHTTPServer(("0.0.0.0", PORT), ContentHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[*] Server stopped.")

if __name__ == "__main__":
    main()
