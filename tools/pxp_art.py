#!/usr/bin/env python3
"""
PXP2 hub artwork: finds, downloads, caches and prepares cover/title/disc art for PS1 and PSP games.

Sources (all fetched on demand and cached under <hub cache>/art and <hub cache>/db; nothing is bundled):
  - libretro-database (CC-BY-SA-4.0), metadat/redump/"Sony - PlayStation.dat" and
    "Sony - PlayStation Portable.dat": serial ("SLUS-01411", "ULUS-10567") -> Redump name.
  - libretro-thumbnails (thumbnails.libretro.com, mirror of github.com/libretro-thumbnails/*):
    Named_Boxarts / Named_Titles / Named_Snaps, keyed by No-Intro/Redump name.
  - xlenore/psx-covers (GitHub raw), PS1 front covers keyed by serial ("SLUS-01411.jpg"), fallback.
  - LaunchBox Games Database (gamesdb.launchbox-app.com Metadata.zip + images.launchbox-app.com):
    "Disc" images (UMD label scans for about half of all PSP games) and "Box - Front".
The artwork itself belongs to its publishers; the hub only caches it for the user's own library.

Matching: serial -> Redump name through the DAT; then the name is matched to the image sets by a
normalised title (lower case, "Title, The" -> "The Title", "&" -> "and", punctuation removed, tags in
brackets dropped), preferring the same disc number and region (from the serial prefix); if no exact
normalised title exists, difflib picks the closest title with a ratio >= 0.86.
"""

import difflib
import io
import json
import os
import re
import threading
import time
import urllib.parse
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

try:
    from PIL import Image, ImageFilter, ImageDraw, ImageEnhance
except Exception:  # pragma: no cover
    Image = None

try:
    import numpy as np
except Exception:  # pragma: no cover
    np = None

UA = {"User-Agent": "PXP2-Hub/1.0 (+https://github.com/; personal library artwork cache)"}
CACHE = Path(__file__).resolve().parent.parent / "hub_cache"

SYSTEMS = {
    "ps1": {"thumbs": "Sony - PlayStation",
            "dat": "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/redump/Sony%20-%20PlayStation.dat",
            "launchbox": "Sony Playstation"},
    "psp": {"thumbs": "Sony - PlayStation Portable",
            "dat": "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/redump/Sony%20-%20PlayStation%20Portable.dat",
            "launchbox": "Sony PSP"},
}
THUMB_HOSTS = ["https://thumbnails.libretro.com/{sys}/{kind}/{name}.png",
               "https://raw.githubusercontent.com/libretro-thumbnails/{repo}/master/{kind}/{name}.png"]
PSX_COVERS = "https://raw.githubusercontent.com/xlenore/psx-covers/main/covers/default/{serial}.jpg"
LAUNCHBOX_ZIP = "https://gamesdb.launchbox-app.com/Metadata.zip"
LAUNCHBOX_IMG = "https://images.launchbox-app.com/{file}"

DB_MAX_AGE = 30 * 86400
INDEX_MAX_AGE = 7 * 86400
MISS_RETRY = 86400

_lock = threading.Lock()
_locks = {}
_mem = {}
_pool = ThreadPoolExecutor(max_workers=3, thread_name_prefix="pxp2-art")
_pending = {}


def set_cache(path):
    global CACHE
    CACHE = Path(path)


def _key_lock(key):
    with _lock:
        return _locks.setdefault(key, threading.Lock())


def http_get(url, timeout=20):
    try:
        req = urllib.request.Request(url, headers=UA)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            if r.status == 200:
                return r.read()
    except Exception:
        pass
    return None


def _cached_file(path, url, max_age, timeout=60):
    path = Path(path)
    if path.is_file() and time.time() - path.stat().st_mtime < max_age:
        return path
    data = http_get(url, timeout)
    if data:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(path.suffix + ".tmp")
        tmp.write_bytes(data)
        os.replace(tmp, path)
        return path
    return path if path.is_file() else None   # stale copy is better than none


# ---------------------------------------------------------------------------------------------
# Names and matching
# ---------------------------------------------------------------------------------------------

def dashed(serial):
    s = re.sub(r"[^A-Za-z0-9]", "", serial or "").upper()
    return s[:4] + "-" + s[4:] if len(s) == 9 else s


def plain(serial):
    return re.sub(r"[^A-Za-z0-9]", "", serial or "").upper()


def base_title(name):
    """'Legend of Dragoon, The (USA, Canada) (Disc 1)' -> 'Legend of Dragoon, The'."""
    return re.sub(r"\s*[\(\[][^\)\]]*[\)\]]", "", name or "").strip()


def norm(title):
    t = base_title(title).lower()
    m = re.match(r"^(.*?),\s*(the|a|an)\b(.*)$", t)     # "Title, The - Sub" -> "the Title - Sub"
    if m:
        t = f"{m.group(2)} {m.group(1)}{m.group(3)}"
    t = t.replace("&", " and ")
    return re.sub(r"[^a-z0-9]", "", t)


def tags(name):
    return [x.strip().lower() for g in re.findall(r"\(([^)]*)\)", name or "") for x in g.split(",")]


def disc_no(name):
    m = re.search(r"\(disc\s*(\d+)\)", name or "", re.I)
    return int(m.group(1)) if m else None


REGION_OF_PREFIX = {"U": "usa", "E": "europe", "J": "japan", "P": "japan", "K": "korea", "A": "asia"}


def region_of_serial(serial):
    s = plain(serial)
    if not s:
        return None
    if s[:2] in ("SL", "SC") and len(s) > 3:   # PS1: SLUS SCUS SLES SCES SLPS SCPS SLPM
        return {"U": "usa", "E": "europe", "P": "japan", "K": "korea"}.get(s[2])
    if s[0] in "UN" and len(s) > 3:           # PSP: ULUS UCUS ULES UCES ULJM ULJS UCJS NPUH ...
        return {"U": "usa", "E": "europe", "J": "japan", "K": "korea", "A": "asia", "H": "asia"}.get(s[2] if s[0] == "U" else s[3])
    return None


def best_match(want_name, candidates, serial=None, cutoff=0.86):
    """Best candidate name for want_name: same normalised title, then same disc and region; else fuzzy."""
    if not candidates:
        return None
    by_norm = {}
    for c in candidates:
        by_norm.setdefault(norm(c), []).append(c)
    key = norm(want_name)
    pool = by_norm.get(key)
    if not pool:
        close = difflib.get_close_matches(key, list(by_norm), n=1, cutoff=cutoff)
        if not close:
            return None
        pool = by_norm[close[0]]
    want_disc = disc_no(want_name)
    want_tags = set(tags(want_name))
    region = region_of_serial(serial) or next((r for r in ("usa", "europe", "japan") if r in want_tags), None)

    def score(c):
        ct = set(tags(c))
        s = 0
        d = disc_no(c)
        if want_disc is not None:
            s += 40 if d == want_disc else (5 if d is None else -40)
        elif d is not None:
            s -= 10 if d != 1 else 2
        if region and region in ct:
            s += 20
        elif "world" in ct:
            s += 12
        elif region == "usa" and ("canada" in ct or "north america" in ct):
            s += 18
        s += 3 * len(want_tags & ct)
        s -= len(ct - want_tags)            # fewer extra tags (Rev, Beta, Demo...) wins
        if any(x in ct for x in ("beta", "demo", "proto", "sample", "kiosk")):
            s -= 30
        return s
    return max(pool, key=score)


# ---------------------------------------------------------------------------------------------
# Redump DAT (serial -> name)
# ---------------------------------------------------------------------------------------------

def dat_names(system):
    """{PLAINSERIAL: [redump names]} for 'ps1' or 'psp'."""
    k = ("dat", system)
    if k in _mem:
        return _mem[k]
    with _key_lock(k):
        if k in _mem:
            return _mem[k]
        p = _cached_file(CACHE / "db" / f"redump_{system}.dat", SYSTEMS[system]["dat"], DB_MAX_AGE, 120)
        out = {}
        if p:
            text = p.read_text(encoding="utf-8", errors="replace")
            for m in re.finditer(r'game \(\s*name "((?:[^"\\]|\\.)*)"(.*?)\n\)', text, re.S):
                name, body = m.group(1), m.group(2)
                sm = re.search(r'^\s*serial "([^"]*)"', body, re.M)
                if not sm:
                    continue
                for s in re.split(r"[,/]\s*", sm.group(1)):
                    ps = plain(s)
                    if re.match(r"^[A-Z]{4}\d{5}", ps):
                        out.setdefault(ps[:9], []).append(name)
        _mem[k] = out
        return out


def dat_ready(system):
    """True when the serial -> name table can be used without waiting for a download (else one starts)."""
    if ("dat", system) in _mem:
        return True
    p = CACHE / "db" / f"redump_{system}.dat"
    if p.is_file():
        return True
    with _lock:
        f = _pending.get(("dat", system))
        if f is None or f.done():
            _pending[("dat", system)] = _pool.submit(_safe, dat_names, system)
    return False


def redump_name(system, serial):
    names = dat_names(system).get(plain(serial)[:9])
    if not names:
        return None
    region = region_of_serial(serial)
    return max(names, key=lambda n: (region in tags(n), -len(n))) if region else names[0]


# ---------------------------------------------------------------------------------------------
# libretro-thumbnails
# ---------------------------------------------------------------------------------------------

def thumb_index(system, kind):
    k = ("thumbs", system, kind)
    if k in _mem:
        return _mem[k]
    with _key_lock(k):
        if k in _mem:
            return _mem[k]
        sysname = SYSTEMS[system]["thumbs"]
        url = "https://thumbnails.libretro.com/" + urllib.parse.quote(sysname) + "/" + kind + "/"
        p = _cached_file(CACHE / "db" / f"thumbs_{system}_{kind}.html", url, INDEX_MAX_AGE, 60)
        names = []
        if p:
            html = p.read_text(encoding="utf-8", errors="replace")
            names = [urllib.parse.unquote(h)[:-4] for h in re.findall(r'href="([^"/?]+\.png)"', html)]
        _mem[k] = names
        return names


def fetch_thumb(system, kind, name):
    sysname = SYSTEMS[system]["thumbs"]
    for pat in THUMB_HOSTS:
        url = pat.format(sys=urllib.parse.quote(sysname), repo=sysname.replace(" ", "_"), kind=kind,
                         name=urllib.parse.quote(name))
        data = http_get(url, 20)
        if data and data[:4] == b"\x89PNG":
            return data, url
    return None, None


def libretro_art(system, serial, name):
    """{kind: (bytes, url, matched name)} for Named_Boxarts / Named_Titles / Named_Snaps."""
    out = {}
    for kind in ("Named_Boxarts", "Named_Titles", "Named_Snaps"):
        idx = thumb_index(system, kind)
        cand = best_match(name, idx, serial) if idx else re.sub(r'[&*/:`<>?\\|"]', "_", name)
        if cand:
            data, url = fetch_thumb(system, kind, cand)
            if data:
                out[kind] = (data, url, cand)
    return out


# ---------------------------------------------------------------------------------------------
# LaunchBox index (disc scans)
# ---------------------------------------------------------------------------------------------

_lb_state = {"status": "idle"}


def launchbox_index(wait=False):
    """{system: {norm title: [{name, disc: [[file, region]], box: [[file, region]]}]}} or None while building."""
    k = ("launchbox",)
    if k in _mem:
        return _mem[k]
    path = CACHE / "db" / "launchbox_sony.json"
    if path.is_file() and time.time() - path.stat().st_mtime < DB_MAX_AGE:
        try:
            _mem[k] = json.loads(path.read_text(encoding="utf-8"))
            return _mem[k]
        except Exception:
            pass
    with _lock:
        start = _lb_state["status"] in ("idle", "failed")
        if start:
            _lb_state["status"] = "building"
            _lb_state["event"] = threading.Event()
    if start:
        threading.Thread(target=_build_launchbox, args=(path,), name="pxp2-launchbox", daemon=True).start()
    if wait:
        _lb_state["event"].wait(900)
        return _mem.get(k)
    return None


def _build_launchbox(path):
    import xml.etree.ElementTree as ET
    k = ("launchbox",)
    try:
        zp = CACHE / "db" / "launchbox_Metadata.zip"
        if not (zp.is_file() and time.time() - zp.stat().st_mtime < DB_MAX_AGE):
            print("[art] downloading the LaunchBox metadata (about 100 MB, once a month) for disc art...")
            zp.parent.mkdir(parents=True, exist_ok=True)
            req = urllib.request.Request(LAUNCHBOX_ZIP, headers=UA)
            with urllib.request.urlopen(req, timeout=120) as r, open(str(zp) + ".tmp", "wb") as f:
                while True:
                    b = r.read(1 << 20)
                    if not b:
                        break
                    f.write(b)
            os.replace(str(zp) + ".tmp", zp)
        want = {v["launchbox"]: s for s, v in SYSTEMS.items()}
        games, imgs = {}, {}
        with zipfile.ZipFile(zp) as z:
            for _, el in ET.iterparse(z.open("Metadata.xml")):
                if el.tag == "Game":
                    sysk = want.get(el.findtext("Platform"))
                    if sysk:
                        games[el.findtext("DatabaseID")] = (sysk, el.findtext("Name"))
                    el.clear()
                elif el.tag == "GameImage":
                    t = el.findtext("Type")
                    if t in ("Disc", "Box - Front"):
                        imgs.setdefault(el.findtext("DatabaseID"), []).append((t, el.findtext("FileName"), el.findtext("Region") or ""))
                    el.clear()
        idx = {s: {} for s in SYSTEMS}
        for gid, (sysk, name) in games.items():
            im = imgs.get(gid, [])
            rec = {"name": name, "disc": [[f, r] for t, f, r in im if t == "Disc"],
                   "box": [[f, r] for t, f, r in im if t == "Box - Front"]}
            if rec["disc"] or rec["box"]:
                idx[sysk].setdefault(norm(name), []).append(rec)
        path.write_text(json.dumps(idx), encoding="utf-8")
        _mem[k] = idx
        _lb_state["status"] = "ready"
        print(f"[art] LaunchBox index ready: {sum(len(v) for v in idx['psp'].values())} PSP, "
              f"{sum(len(v) for v in idx['ps1'].values())} PS1 titles with images")
    except Exception as e:
        _lb_state["status"] = "failed"
        print(f"[art] LaunchBox index failed: {e}")
    finally:
        _lb_state["event"].set()


def launchbox_building():
    return _lb_state["status"] == "building"


LB_REGION = {"usa": ("north america", "united states", "usa", "canada"), "europe": ("europe", "united kingdom", "pal"),
             "japan": ("japan",)}


def launchbox_image(system, name, serial, kind, wait=False):
    idx = launchbox_index(wait)
    if not idx:
        return None, None
    table = idx.get(system, {})
    recs = table.get(norm(name))
    if not recs:
        close = difflib.get_close_matches(norm(name), list(table), n=1, cutoff=0.9)
        recs = table.get(close[0]) if close else None
    if not recs:
        return None, None
    region = region_of_serial(serial) or "usa"
    files = [(f, r) for rec in recs for f, r in rec.get(kind, [])]
    if not files:
        return None, None
    pref = LB_REGION.get(region, ())
    files.sort(key=lambda fr: (0 if fr[1].lower() in pref else (1 if not fr[1] else 2)))
    for f, _ in files[:3]:
        url = LAUNCHBOX_IMG.format(file=urllib.parse.quote(f))
        data = http_get(url, 30)
        if data:
            return data, url
    return None, None


# ---------------------------------------------------------------------------------------------
# Image preparation
# ---------------------------------------------------------------------------------------------

def _img(data):
    im = Image.open(io.BytesIO(data))
    im.load()
    return im.convert("RGBA")


def _png(im, **kw):
    b = io.BytesIO()
    im.save(b, "PNG", optimize=True, **kw)
    return b.getvalue()


def cover_fit(im, w, h, focus_y=0.5):
    """Scales im to cover w x h and crops the overflow (no distortion)."""
    s = max(w / im.width, h / im.height)
    nw, nh = max(w, round(im.width * s)), max(h, round(im.height * s))
    im = im.resize((nw, nh), Image.LANCZOS)
    x = (nw - w) // 2
    y = int((nh - h) * focus_y)
    return im.crop((x, y, x + w, y + h))


def contain_fit(im, w, h):
    s = min(w / im.width, h / im.height)
    return im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.LANCZOS)


def make_icon0(box=None, title=None):
    """ICON0.PNG, 144x80. A title screen is cropped to 1.8:1 (the logo stays centred); a cover is fitted to
    the full height at its own aspect over a blurred, darkened copy of itself, like PS1 Classics icons."""
    W, H = 144, 80
    if title is not None:
        im = cover_fit(title, W, H, 0.45).convert("RGB")
        return _png(im)
    if box is not None:
        bg = cover_fit(box, W, H).convert("RGB").filter(ImageFilter.GaussianBlur(6))
        bg = ImageEnhance.Brightness(bg).enhance(0.45)
        fg = contain_fit(box, W, H)
        bg.paste(fg, ((W - fg.width) // 2, (H - fg.height) // 2), fg)
        return _png(bg)
    return None


def make_pic1(snap=None, title=None, box=None):
    """PIC1.PNG, 480x272: a gameplay snap (else title screen, else blurred cover), dimmed so XMB text stays legible."""
    W, H = 480, 272
    src = snap or title
    if src is not None:
        im = cover_fit(src, W, H).convert("RGB")
        im = ImageEnhance.Brightness(im.filter(ImageFilter.GaussianBlur(0.6))).enhance(0.72)
    elif box is not None:
        im = cover_fit(box, W, H).convert("RGB").filter(ImageFilter.GaussianBlur(10))
        im = ImageEnhance.Brightness(im).enhance(0.5)
        fg = contain_fit(box, 200, 240)
        im.paste(fg, (W - fg.width - 24, (H - fg.height) // 2), fg)
    else:
        return None
    # left-to-right shade under the XMB's text column
    shade = Image.linear_gradient("L").rotate(90, expand=True).resize((W, H)).point(lambda v: int(110 * (1 - v / 255)))
    im.paste((0, 0, 0), (0, 0), shade)
    return _png(im)


def detect_disc(im):
    """(cx, cy, r) of the disc in a UMD/CD scan: the circle whose rim is darkest against the brightest
    ring just outside it (the caddy plastic or a transparent background)."""
    a = np.asarray(im.convert("RGBA"), dtype=np.float32)
    alpha = a[..., 3:4] / 255.0
    g = (a[..., :3] * alpha + 255.0 * (1 - alpha)).mean(axis=2)     # transparent -> white
    H, W = g.shape
    scale = 256.0 / max(W, H)
    small = np.asarray(Image.fromarray(g.astype(np.uint8)).resize((max(8, round(W * scale)), max(8, round(H * scale))),
                                                                   Image.BILINEAR), dtype=np.float32)
    h, w = small.shape
    ang = np.linspace(0, 2 * np.pi, 180, endpoint=False)
    ca, sa = np.cos(ang), np.sin(ang)

    def score(img, cx, cy, r, d):
        hh, ww = img.shape
        out = []
        for rr in (r + d, r, r - d):
            xs, ys = cx + rr * ca, cy + rr * sa
            ok = (xs >= 0) & (xs <= ww - 1) & (ys >= 0) & (ys <= hh - 1)
            out.append((img[np.clip(ys, 0, hh - 1).astype(int), np.clip(xs, 0, ww - 1).astype(int)], ok))
        ok = out[0][1] & out[1][1] & out[2][1]
        if ok.mean() < 0.55:
            return -1e9
        o, rim, i = out[0][0][ok], out[1][0][ok], out[2][0][ok]
        return float((o - 0.6 * rim - 0.4 * i).mean())

    m = min(w, h)
    best = (-1e9, 0, 0, 0)
    for r in np.arange(0.30 * m, 0.51 * m, 1.0):
        for cy in np.arange(0.38 * h, 0.64 * h, 1.5):
            for cx in np.arange(0.40 * w, 0.60 * w, 1.5):
                s = score(small, cx, cy, r, 2.5)
                if s > best[0]:
                    best = (s, cx, cy, r)
    _, cx, cy, r = best
    cx, cy, r = cx / scale, cy / scale, r / scale
    # refine at full resolution
    fbest = (-1e9, cx, cy, r)
    step = 1.0 / scale
    for dr in np.arange(-2 * step, 2 * step + 0.01, max(1.0, step / 2)):
        for dy in np.arange(-2 * step, 2 * step + 0.01, max(1.0, step / 2)):
            for dx in np.arange(-2 * step, 2 * step + 0.01, max(1.0, step / 2)):
                s = score(g, cx + dx, cy + dy, r + dr, max(3.0, 0.01 * r))
                if s > fbest[0]:
                    fbest = (s, cx + dx, cy + dy, r + dr)
    return fbest[1], fbest[2], fbest[3], fbest[0]


def disc_label(data, size=512):
    """A disc scan -> size x size PNG with the disc exactly inscribed (transparent outside the rim)."""
    im = _img(data)
    cx, cy, r, sc = detect_disc(im)
    r *= 0.985                                     # stay just inside the dark rim
    box = (round(cx - r), round(cy - r), round(cx + r), round(cy + r))
    canvas = Image.new("RGBA", (box[2] - box[0], box[3] - box[1]), (0, 0, 0, 0))
    canvas.paste(im, (-box[0], -box[1]))
    lab = canvas.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    a = lab.getchannel("A")
    lab.putalpha(Image.fromarray(np.minimum(np.asarray(a), np.asarray(mask))))
    return _png(lab), {"cx": round(cx, 1), "cy": round(cy, 1), "r": round(r, 1), "score": round(sc, 1),
                       "src_size": [im.width, im.height]}


# ---------------------------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------------------------

def _meta_path(system, serial):
    return CACHE / "art" / system / plain(serial) / "meta.json"


def _read_meta(system, serial):
    p = _meta_path(system, serial)
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except Exception:
        return None


def ps1_art(serial, fallback_name=None, refresh=False):
    """Prepares <cache>/art/ps1/<SERIAL>/{icon0.png, pic1.png, box.png} and returns its meta dict."""
    serial = plain(serial)
    d = CACHE / "art" / "ps1" / serial
    with _key_lock(("ps1", serial)):
        meta = _read_meta("ps1", serial)
        if meta and not refresh and (meta.get("icon0") or time.time() - meta.get("time", 0) < MISS_RETRY):
            return meta
        name = redump_name("ps1", serial) or fallback_name or serial
        src = libretro_art("ps1", serial, name)
        box = src.get("Named_Boxarts")
        box_data, box_url = (box[0], box[1]) if box else (None, None)
        if not box_data:
            box_data = http_get(PSX_COVERS.format(serial=dashed(serial)), 20)
            box_url = PSX_COVERS.format(serial=dashed(serial)) if box_data else None
        if not box_data:
            box_data, box_url = launchbox_image("ps1", name, serial, "box")
        title = src.get("Named_Titles")
        snap = src.get("Named_Snaps")
        d.mkdir(parents=True, exist_ok=True)
        ims = {}
        for k, v in (("box", box_data), ("title", title and title[0]), ("snap", snap and snap[0])):
            if v:
                try:
                    ims[k] = _img(v)
                except Exception:
                    pass
        meta = {"serial": serial, "name": name, "time": time.time(), "sources": {}}
        if box_url:
            meta["sources"]["box"] = box_url
        if title:
            meta["sources"]["title"] = title[1]
        if snap:
            meta["sources"]["snap"] = snap[1]
        icon = make_icon0(ims.get("box"), ims.get("title"))
        pic1 = make_pic1(ims.get("snap"), ims.get("title"), ims.get("box"))
        for fname, data in (("icon0.png", icon), ("pic1.png", pic1),
                            ("box.png", _png(contain_fit(ims["box"], 512, 512)) if "box" in ims else None)):
            if data:
                (d / fname).write_bytes(data)
                meta[fname.split(".")[0]] = fname
        (d / "meta.json").write_text(json.dumps(meta, indent=1), encoding="utf-8")
        return meta


def ps1_art_async(serial, fallback_name=None):
    """Starts preparing PS1 art in the background (listing never waits for the network)."""
    serial = plain(serial)
    meta = _read_meta("ps1", serial)
    if meta and (meta.get("icon0") or time.time() - meta.get("time", 0) < MISS_RETRY):
        return None
    with _lock:
        f = _pending.get(("ps1", serial))
        if f is None or f.done():
            f = _pending[("ps1", serial)] = _pool.submit(_safe, ps1_art, serial, fallback_name)
    return f


def _safe(fn, *a):
    try:
        return fn(*a)
    except Exception as e:
        print(f"[art] {fn.__name__}{a}: {e}")
        return None


def art_file(system, serial, fname, wait=0.0):
    """Path of a prepared file, waiting up to `wait` seconds for a running fetch."""
    serial = plain(serial)
    p = CACHE / "art" / system / serial / fname
    if not p.is_file() and wait > 0:
        f = _pending.get((system, serial))
        if f is not None:
            try:
                f.result(timeout=wait)
            except Exception:
                pass
    return p if p.is_file() else None


def _psp_meta_fresh(meta):
    if meta.get("umd_label"):
        return True
    if meta.get("index_pending"):
        return launchbox_building()
    return time.time() - meta.get("time", 0) < MISS_RETRY


def psp_art(serial, refresh=False, wait_index=False):
    """Prepares <cache>/art/psp/<SERIAL>/{umd_label.png, box.png} (label from a LaunchBox disc scan)."""
    serial = plain(serial)
    d = CACHE / "art" / "psp" / serial
    with _key_lock(("psp", serial)):
        meta = _read_meta("psp", serial)
        if meta and not refresh and _psp_meta_fresh(meta):
            return meta
        name = redump_name("psp", serial)
        meta = {"serial": serial, "name": name, "time": time.time(), "sources": {}}
        d.mkdir(parents=True, exist_ok=True)
        if name:
            idx_ready = launchbox_index(wait_index) is not None
            disc, url = launchbox_image("psp", name, serial, "disc") if idx_ready else (None, None)
            if disc:
                try:
                    png, geo = disc_label(disc)
                    (d / "umd_label.png").write_bytes(png)
                    meta["umd_label"] = "umd_label.png"
                    meta["sources"]["disc"] = url
                    meta["disc_geometry"] = geo
                except Exception as e:
                    meta["disc_error"] = str(e)
            box = libretro_art("psp", serial, name).get("Named_Boxarts")
            box_data, box_url = (box[0], box[1]) if box else (None, None)
            if not box_data and idx_ready:
                box_data, box_url = launchbox_image("psp", name, serial, "box")
            if box_data:
                try:
                    (d / "box.png").write_bytes(_png(contain_fit(_img(box_data), 512, 512)))
                    meta["box"] = "box.png"
                    meta["sources"]["box"] = box_url
                except Exception:
                    pass
            if not idx_ready:
                meta["index_pending"] = True   # retried as soon as the LaunchBox index is ready
        (d / "meta.json").write_text(json.dumps(meta, indent=1), encoding="utf-8")
        return meta


def psp_art_async(serial):
    serial = plain(serial)
    meta = _read_meta("psp", serial)
    if meta and _psp_meta_fresh(meta):
        return None
    with _lock:
        f = _pending.get(("psp", serial))
        if f is None or f.done():
            f = _pending[("psp", serial)] = _pool.submit(_safe, psp_art, serial)
    return f


if __name__ == "__main__":
    import sys
    sysk, ser = sys.argv[1], sys.argv[2]
    if len(sys.argv) > 3:
        set_cache(sys.argv[3])
    print(json.dumps(ps1_art(ser, refresh=True) if sysk == "ps1" else psp_art(ser, refresh=True, wait_index=True), indent=1))
