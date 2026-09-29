#!/usr/bin/env python3
"""
PXP2 hub: PSP UMD compression - firmware UPDATE stripping and conversion to CHD (MAME CHD v5, DVD profile).

    python tools/psp_compress.py [options] <.iso files or folders>      convert (skips up-to-date outputs)
    python tools/psp_compress.py --report <.iso files or folders>       sizes only, nothing written
    python tools/psp_compress.py --fetch-chdman                         download chdman (MAME release, SHA-256 checked)

UPDATE stripping
    Every retail PSP disc carries a firmware updater in PSP_GAME/SYSDIR/UPDATE/ (EBOOT.BIN/EBOOT.PBP + DATA.BIN,
    15-35 MB of encrypted, incompressible data). The files are *zero-filled in place*: every directory record, every
    other file and every sector address stays where it was, and UPDATE/PARAM.SFO is kept byte for byte (vshmain stats
    it during the disc check). Rebuilding the ISO without the folder would move every later file, which breaks games
    that open files by raw sector address (disc0:/sce_lbn0x..._size0x...). Zeroed sectors cost almost nothing in the
    CHD. The user's source image is never modified: a temporary stripped copy is made next to the output and deleted.

CHD
    Written by chdman (MAME, BSD-3-Clause/GPL) with `createdvd`: 2048-byte units (sectors) and hunks of 16 KiB
    (8 sectors; --hunk), codecs zstd + lzma (chdman keeps the smaller per hunk; lzma usually wins, zstd decodes faster).
    chdman is the reference writer, multithreaded and byte-exact with every CHD reader; PXP2 reads the result with its
    own decoder (core ChdSource). The hub never bundles chdman: it is found on PATH, in tools/chdman/, via
    --chdman / PXP2_CHDMAN, or fetched once with --fetch-chdman.

Output
    "<Title> [ULUS-10081].chd" (the same naming as the transfer rename, tools/pxp_psp.py) in the output folder, with a
    manifest (pxp2_chd.json) recording the source size/mtime, the settings and the sizes. An output is up to date when
    the manifest entry matches the source and the settings, so it is not converted again.

GPL-3.0, part of PXP2. Our own code; no emulator code was copied.
"""

import hashlib
import json
import lzma
import os
import re
import shutil
import struct
import subprocess
import sys
import threading
import time
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pxp_psp  # noqa: E402

SECTOR = 2048
TOOL_VERSION = 1                 # bump when the stripping or CHD settings change meaning
DEFAULT_HUNK = 16384
DEFAULT_CODECS = "zstd,lzma"
MANIFEST = "pxp2_chd.json"
DEFAULT_RATIO = 0.64             # CHD/ISO estimate before any conversion has been measured
MAME_RELEASE = "mame0289"
MAME_ASSET = "mame0289b_x64.exe"
MAME_SHA256 = "a1aa7912168c9d1b05e611906bc21b8b9be3935822aead36d12a1da363150b7d"
TOOLS_DIR = Path(__file__).resolve().parent


# ---------------------------------------------------------------------------------------------------------------------
# ISO 9660 walk
# ---------------------------------------------------------------------------------------------------------------------
def _walk(f, lba, size, path, files, dirs, depth=0):
    if depth > 16:
        return
    dirs.append((lba, (size + SECTOR - 1) // SECTOR, path or "/"))
    f.seek(lba * SECTOR)
    d = f.read(((size + SECTOR - 1) // SECTOR) * SECTOR)
    p = 0
    while p < len(d):
        ln = d[p]
        if ln == 0:
            p = (p // SECTOR + 1) * SECTOR
            continue
        if p + 33 > len(d):
            break
        nl = d[p + 32]
        name = d[p + 33:p + 33 + nl]
        l2, sz, fl = struct.unpack_from("<I", d, p + 2)[0], struct.unpack_from("<I", d, p + 10)[0], d[p + 25]
        if not (nl == 1 and name[0] in (0, 1)):
            n = name.decode("latin1").split(";")[0]
            if fl & 2:
                _walk(f, l2, sz, path + "/" + n, files, dirs, depth + 1)
            else:
                files.append((l2, sz, path + "/" + n))
        p += ln


def iso_layout(path):
    """(files, dirs, volume_sectors): files = [(lba, size, "/PSP_GAME/..."), ...], dirs = [(lba, sectors, path)]."""
    with open(path, "rb") as f:
        f.seek(16 * SECTOR)
        pvd = f.read(SECTOR)
        if pvd[0] != 1 or pvd[1:6] != b"CD001":
            raise ValueError("not an ISO 9660 image")
        root = pvd[156:190]
        files, dirs = [], []
        _walk(f, struct.unpack_from("<I", root, 2)[0], struct.unpack_from("<I", root, 10)[0], "", files, dirs)
        return files, dirs, struct.unpack_from("<I", pvd, 80)[0]


def update_plan(path):
    """What stripping would zero: {"kind", "ranges": [(lba, sectors, file)], "bytes", "files", "reason"}.

    kind is "game" (PSP_GAME disc), "video" (UMD Video) or "other". Only PSP game discs are stripped. PARAM.SFO stays;
    a range is dropped if it would touch any other file, a directory or the volume descriptors."""
    files, dirs, _ = iso_layout(path)
    names = {p.upper() for _, _, p in files}
    kind = "game" if "/PSP_GAME/PARAM.SFO" in names else ("video" if any(p.upper().startswith("/UMD_VIDEO/") for p in names) else "other")
    plan = {"kind": kind, "ranges": [], "bytes": 0, "files": [], "reason": None}
    if kind != "game":
        plan["reason"] = "UMD Video disc" if kind == "video" else "not a PSP game disc"
        return plan
    upd = [x for x in files if x[2].upper().startswith("/PSP_GAME/SYSDIR/UPDATE/") and x[2].upper() != "/PSP_GAME/SYSDIR/UPDATE/PARAM.SFO"]
    if not upd:
        plan["reason"] = "no UPDATE folder on this disc"
        return plan
    keep = [(l, (s + SECTOR - 1) // SECTOR) for l, s, p in files if (l, s, p) not in upd and s > 0]
    keep += [(l, n) for l, n, _ in dirs]
    keep.append((0, 18))  # system area, volume descriptors
    for lba, size, p in upd:
        n = (size + SECTOR - 1) // SECTOR
        if n == 0:
            continue
        if any(lba < kl + kn and kl < lba + n for kl, kn in keep):
            continue  # shares sectors with something else: leave it alone
        plan["ranges"].append((lba, n, p))
        plan["files"].append(p)
        plan["bytes"] += n * SECTOR
    if not plan["ranges"]:
        plan["reason"] = "UPDATE files overlap other data; left as is"
    return plan


def write_stripped(src, dst, ranges, progress=None, cancel=None):
    """Copies src to dst with the sector ranges zero-filled (src is only read)."""
    total = os.path.getsize(src)
    zero = sorted((lba * SECTOR, (lba + n) * SECTOR) for lba, n, _ in ranges)
    chunk = 8 * 1024 * 1024
    done = 0
    with open(src, "rb") as fi, open(dst, "wb") as fo:
        while True:
            buf = fi.read(chunk)
            if not buf:
                break
            b = bytearray(buf) if any(a < done + len(buf) and done < e for a, e in zero) else None
            if b is not None:
                for a, e in zero:
                    lo, hi = max(a, done), min(e, done + len(buf))
                    if lo < hi:
                        b[lo - done:hi - done] = bytes(hi - lo)
            fo.write(b if b is not None else buf)
            done += len(buf)
            if progress:
                progress(done / total if total else 1.0)
            if cancel and cancel():
                raise RuntimeError("cancelled")


# ---------------------------------------------------------------------------------------------------------------------
# chdman
# ---------------------------------------------------------------------------------------------------------------------
def find_chdman(explicit=None):
    cands = [explicit, os.environ.get("PXP2_CHDMAN"), shutil.which("chdman"),
             TOOLS_DIR / "chdman" / "chdman.exe", TOOLS_DIR / "chdman" / "chdman"]
    for c in cands:
        if c and Path(c).is_file():
            return str(c)
    return None


def fetch_chdman(dest=TOOLS_DIR / "chdman"):
    """Downloads the MAME Windows release, checks its SHA-256 and extracts chdman.exe with 7-Zip (the only file kept,
    plus MAME's COPYING). On Linux/macOS install chdman from the package manager (mame-tools) instead."""
    import urllib.request
    if os.name != "nt":
        raise RuntimeError("install chdman with your package manager (Debian/Ubuntu: apt install mame-tools)")
    seven = shutil.which("7z") or next((p for p in (r"C:\Program Files\7-Zip\7z.exe", r"C:\Program Files (x86)\7-Zip\7z.exe") if Path(p).is_file()), None)
    if not seven:
        raise RuntimeError("7-Zip is needed to unpack the MAME release (https://www.7-zip.org/)")
    dest = Path(dest)
    dest.mkdir(parents=True, exist_ok=True)
    url = f"https://github.com/mamedev/mame/releases/download/{MAME_RELEASE}/{MAME_ASSET}"
    tmp = dest / (MAME_ASSET + ".part")
    print(f"[CHD] downloading {url} (about 88 MB) ...")
    h = hashlib.sha256()
    with urllib.request.urlopen(url, timeout=60) as r, open(tmp, "wb") as f:
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            h.update(b)
            f.write(b)
    if h.hexdigest() != MAME_SHA256:
        tmp.unlink()
        raise RuntimeError("SHA-256 mismatch for the MAME download; not used")
    subprocess.run([seven, "e", "-y", f"-o{dest}", str(tmp), "chdman.exe", "COPYING"], check=True, capture_output=True)
    tmp.unlink()
    return str(dest / "chdman.exe")


_PROGRESS = re.compile(rb"(\d+(?:\.\d+)?)% complete")


def run_chdman(chdman, src, dst, hunk=DEFAULT_HUNK, codecs=DEFAULT_CODECS, progress=None, cancel=None):
    cmd = [chdman, "createdvd", "-f", "-hs", str(hunk), "-c", codecs, "-i", str(src), "-o", str(dst)]
    flags = 0x08000000 if os.name == "nt" else 0  # CREATE_NO_WINDOW
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, creationflags=flags)
    tail = b""
    log = bytearray()
    while True:
        b = proc.stdout.read(256)
        if not b:
            break
        log += b
        tail = (tail + b)[-400:]
        m = None
        for m in _PROGRESS.finditer(tail):
            pass
        if m and progress:
            progress(float(m.group(1)) / 100.0)
        if cancel and cancel():
            proc.kill()
            proc.wait()
            raise RuntimeError("cancelled")
    rc = proc.wait()
    if rc != 0:
        text = log.decode("utf-8", "replace").replace("\r", "\n")
        raise RuntimeError(f"chdman failed ({rc}): " + " / ".join(l.strip() for l in text.splitlines() if l.strip())[-400:])


# ---------------------------------------------------------------------------------------------------------------------
# CHD v5 reader (header, compressed map, zlib/lzma/zstd hunks) - used to read PARAM.SFO from .chd files and to verify
# ---------------------------------------------------------------------------------------------------------------------
class _Bits:
    """MSB-first bit reader; bits past the end read as zero."""

    def __init__(self, data):
        self.d = bytes(data) + bytes(16)
        self.pos = 0

    def peek(self, k):
        if k == 0:
            return 0
        i, o = self.pos >> 3, self.pos & 7
        v = int.from_bytes(self.d[i:i + 8], "big") if i < len(self.d) else 0
        if k + o > 64:
            v = (v << 64) | int.from_bytes(self.d[i + 8:i + 16], "big")
            return (v >> (128 - o - k)) & ((1 << k) - 1)
        return (v >> (64 - o - k)) & ((1 << k) - 1)

    def read(self, k):
        x = self.peek(k)
        self.pos += k
        return x


def _huffman(bits, numcodes, maxbits, lengths):
    histo = [0] * 33
    for l in lengths:
        histo[l] += 1
    start = 0
    for ln in range(32, 0, -1):
        nxt = (start + histo[ln]) >> 1
        if ln != 1 and nxt * 2 != start + histo[ln]:
            raise ValueError("bad CHD Huffman tree")
        histo[ln] = start
        start = nxt
    lut = [(0, 0)] * (1 << maxbits)
    for i, l in enumerate(lengths):
        if l:
            code = histo[l]
            histo[l] += 1
            sh = maxbits - l
            for k in range(code << sh, (code + 1) << sh):
                lut[k] = (i, l)
    return lut


_CRC_T = []
for _i in range(256):
    _c = _i << 8
    for _ in range(8):
        _c = ((_c << 1) ^ 0x1021) if _c & 0x8000 else _c << 1
    _CRC_T.append(_c & 0xFFFF)


def _crc16_ccitt(data):
    c = 0xFFFF
    t = _CRC_T
    for b in data:
        c = ((c << 8) & 0xFFFF) ^ t[(c >> 8) ^ b]
    return c


class ChdReader:
    """Minimal CHD v5 reader: parent-less images, codecs zlib, lzma and zstd (Python 3.14's compression.zstd)."""

    def __init__(self, path):
        self.f = open(path, "rb")
        h = self.f.read(124)
        if h[:8] != b"MComprHD" or struct.unpack(">I", h[12:16])[0] != 5:
            raise ValueError("not a CHD v5 file")
        self.codecs = [h[16 + 4 * i:20 + 4 * i] for i in range(4)]
        self.logical, self.mapoff, self.metaoff = struct.unpack(">QQQ", h[32:56])
        self.hunkbytes, self.unitbytes = struct.unpack(">II", h[56:64])
        if any(h[104:124]):
            raise ValueError("CHD needs a parent")
        self.hunks = (self.logical + self.hunkbytes - 1) // self.hunkbytes
        self._map()
        self._cache = {}

    def _map(self):
        f = self.f
        if self.codecs[0] == b"\0\0\0\0":
            f.seek(self.mapoff)
            raw = f.read(4 * self.hunks)
            self.map = [((4, self.hunkbytes, o * self.hunkbytes, None) if o else (-1, 0, 0, None)) for o in struct.unpack(f">{self.hunks}I", raw)]
            return
        f.seek(self.mapoff)
        mh = f.read(16)
        mapbytes = struct.unpack(">I", mh[0:4])[0]
        cur = int.from_bytes(mh[4:10], "big")
        mapcrc = struct.unpack(">H", mh[10:12])[0]
        lbits, sbits, pbits = mh[12], mh[13], mh[14]
        bits = _Bits(f.read(mapbytes))
        lengths, cur_n = [], 0
        while cur_n < 16:
            nb = bits.read(4)
            if nb != 1:
                lengths.append(nb); cur_n += 1; continue
            nb = bits.read(4)
            if nb == 1:
                lengths.append(1); cur_n += 1; continue
            rep = bits.read(4) + 3
            lengths += [nb] * rep
            cur_n += rep
        lut = _huffman(bits, 16, 8, lengths[:16])

        def dec():
            s, l = lut[bits.peek(8)]
            bits.pos += l
            return s
        types, last, rep = [], 0, 0
        for _ in range(self.hunks):
            if rep:
                types.append(last); rep -= 1; continue
            v = dec()
            if v == 7:
                types.append(last); rep = 2 + dec()
            elif v == 8:
                types.append(last); rep = 2 + 16 + (dec() << 4); rep += dec()
            else:
                types.append(v); last = v
        out, raw, last_self, last_parent = [], bytearray(), 0, 0
        for i, t in enumerate(types):
            off, ln, crc = cur, 0, 0
            if t <= 3:
                ln = bits.read(lbits); cur += ln; crc = bits.read(16)
            elif t == 4:
                ln = self.hunkbytes; cur += ln; crc = bits.read(16)
            elif t == 5:
                off = last_self = bits.read(sbits)
            elif t == 6:
                off = last_parent = bits.read(pbits)
            elif t in (9, 10):
                if t == 10:
                    last_self += 1
                t, off = 5, last_self
            elif t == 11:
                t, off = 6, i * self.hunkbytes // self.unitbytes
                last_parent = off
            elif t in (12, 13):
                if t == 13:
                    last_parent += self.hunkbytes // self.unitbytes
                t, off = 6, last_parent
            raw += bytes([t]) + ln.to_bytes(3, "big") + off.to_bytes(6, "big") + crc.to_bytes(2, "big")
            out.append((t, ln, off, crc if t <= 4 else None))
        if _crc16_ccitt(raw) != mapcrc:
            raise ValueError("CHD map CRC mismatch")
        self.map = out

    def hunk(self, i):
        if i in self._cache:
            return self._cache[i]
        t, ln, off, crc = self.map[i]
        if t == -1:
            data = bytes(self.hunkbytes)
        elif t == 5:
            data = self.hunk(off)
        elif t == 6:
            raise ValueError("CHD needs a parent")
        else:
            self.f.seek(off)
            comp = self.f.read(ln)
            if t == 4:
                data = comp
            else:
                c = self.codecs[t]
                if c == b"zlib":
                    data = zlib.decompress(comp, -15)
                elif c == b"lzma":
                    d = lzma.LZMADecompressor(lzma.FORMAT_RAW, filters=[{"id": lzma.FILTER_LZMA1, "lc": 3, "lp": 0, "pb": 2, "dict_size": max(self.hunkbytes, 4096)}])
                    data = d.decompress(comp, max_length=self.hunkbytes)
                elif c == b"zstd":
                    from compression import zstd
                    data = zstd.decompress(comp)
                else:
                    raise ValueError(f"CHD codec {c!r} not supported here")
            if len(data) != self.hunkbytes or _crc16_ccitt(data) != crc:
                raise ValueError(f"CHD hunk {i} failed its CRC")
        if len(self._cache) > 64:
            self._cache.clear()
        self._cache[i] = data
        return data

    def read(self, lba, count=1):
        out = bytearray()
        pos, end = lba * SECTOR, min((lba + count) * SECTOR, self.logical)
        while pos < end:
            h, w = divmod(pos, self.hunkbytes)
            take = min(end - pos, self.hunkbytes - w)
            out += self.hunk(h)[w:w + take]
            pos += take
        return bytes(out)

    def close(self):
        self.f.close()


def read_sfo(path):
    """PARAM.SFO of an .iso/.cso (pxp_psp) or a .chd image."""
    if str(path).lower().endswith(".chd"):
        r = ChdReader(path)
        try:
            for p in ("PSP_GAME/PARAM.SFO", "UMD_VIDEO/PARAM.SFO"):
                hit = pxp_psp._find(r, p)
                if hit and 0 < hit[1] < 65536:
                    return pxp_psp.parse_sfo(r.read(hit[0], (hit[1] + SECTOR - 1) // SECTOR)[:hit[1]])
        finally:
            r.close()
        return {}
    return pxp_psp.read_sfo(path)


# ---------------------------------------------------------------------------------------------------------------------
# Conversion and the manifest
# ---------------------------------------------------------------------------------------------------------------------
_manifest_lock = threading.Lock()


def load_manifest(out_dir):
    try:
        return json.loads((Path(out_dir) / MANIFEST).read_text(encoding="utf-8"))
    except Exception:
        return {}


def save_manifest_entry(out_dir, name, entry):
    with _manifest_lock:
        m = load_manifest(out_dir)
        if entry is None:
            m.pop(name, None)
        else:
            m[name] = entry
        p = Path(out_dir) / MANIFEST
        tmp = p.with_suffix(".tmp")
        tmp.write_text(json.dumps(m, indent=1, sort_keys=True), encoding="utf-8")
        os.replace(tmp, p)


def settings(strip=True, hunk=DEFAULT_HUNK, codecs=DEFAULT_CODECS):
    return {"tool": TOOL_VERSION, "strip": bool(strip), "hunk": int(hunk), "codecs": codecs}


def output_name(src, sfo=None):
    """"<Title> [ULUS-10081].chd", or the source stem when the disc has no TITLE/DISC_ID."""
    if sfo is None:
        try:
            sfo = read_sfo(src)
        except Exception:
            sfo = {}
    return pxp_psp.device_filename(sfo.get("TITLE"), sfo.get("DISC_ID"), ".chd") or (Path(src).stem + ".chd")


def find_output(src, out_dir, want):
    """(name, entry) of an up-to-date output for src, else (None, None)."""
    st = os.stat(src)
    for name, e in load_manifest(out_dir).items():
        if (e.get("source_name") == Path(src).name and e.get("source_size") == st.st_size
                and e.get("source_mtime") == int(st.st_mtime) and e.get("settings") == want):
            f = Path(out_dir) / name
            if f.is_file() and f.stat().st_size == e.get("chd_size"):
                return name, e
    return None, None


def estimate_ratio(out_dir):
    """Mean CHD/ISO ratio of the conversions done so far (for the catalog's "≈" size)."""
    rs = [e["chd_size"] / e["source_size"] for e in load_manifest(out_dir).values() if e.get("source_size")]
    return sum(rs) / len(rs) if rs else DEFAULT_RATIO


def convert(src, out_dir, strip=True, hunk=DEFAULT_HUNK, codecs=DEFAULT_CODECS, chdman=None, progress=None,
            cancel=None, force=False, log=print):
    """Converts one PSP ISO to CHD in out_dir; returns the manifest entry (with "name" and "skipped")."""
    src = Path(src)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    want = settings(strip, hunk, codecs)
    if not force:
        name, e = find_output(src, out_dir, want)
        if name:
            return dict(e, name=name, skipped=True)
    chdman = find_chdman(chdman)
    if not chdman:
        raise RuntimeError("chdman not found (run: python tools/psp_compress.py --fetch-chdman)")
    st = src.stat()
    sfo = read_sfo(src)
    name = output_name(src, sfo)
    plan = update_plan(src) if strip else {"kind": None, "ranges": [], "bytes": 0, "files": [], "reason": "--no-strip"}
    say = progress or (lambda phase, frac: None)
    t0 = time.time()
    tmp_dir = out_dir / ".tmp"
    tmp_dir.mkdir(exist_ok=True)
    key = hashlib.sha1(str(src).encode()).hexdigest()[:10]
    stripped = tmp_dir / f"{key}.stripped.iso"
    part = tmp_dir / f"{key}.chd.part"
    try:
        inp = src
        if plan["ranges"]:
            log(f"[CHD] {src.name}: zeroing {len(plan['ranges'])} UPDATE file(s), {plan['bytes'] / 1048576:.1f} MiB")
            write_stripped(src, stripped, plan["ranges"], lambda f: say("stripping", f), cancel)
            inp = stripped
        say("compressing", 0.0)
        run_chdman(chdman, inp, part, hunk, codecs, lambda f: say("compressing", f), cancel)
        final = out_dir / name
        os.replace(part, final)
    finally:
        for p in (stripped, part):
            try:
                p.unlink()
            except OSError:
                pass
    entry = {
        "source": str(src), "source_name": src.name, "source_size": st.st_size, "source_mtime": int(st.st_mtime),
        "title": sfo.get("TITLE", ""), "disc_id": sfo.get("DISC_ID", ""), "settings": want,
        "stripped_files": plan["files"], "stripped_bytes": plan["bytes"], "strip_note": plan["reason"],
        "chd_size": final.stat().st_size, "saved_bytes": st.st_size - final.stat().st_size,
        "seconds": round(time.time() - t0, 1), "made": time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    save_manifest_entry(out_dir, name, entry)
    say("done", 1.0)
    return dict(entry, name=name, skipped=False)


# ---------------------------------------------------------------------------------------------------------------------
# Background queue for the hub
# ---------------------------------------------------------------------------------------------------------------------
class Converter:
    """One conversion at a time on a background thread (chdman already uses every core). Jobs are keyed by the source
    path; status() reports "queued", "stripping", "compressing", "done" or "error" with a 0..1 progress."""

    def __init__(self, out_dir_fn, strip=True, hunk=DEFAULT_HUNK, codecs=DEFAULT_CODECS, chdman=None):
        self.out_dir_fn, self.strip, self.hunk, self.codecs, self.chdman = out_dir_fn, strip, hunk, codecs, chdman
        self.jobs = {}
        self.queue = []
        self.lock = threading.Condition()
        self.thread = None

    def settings(self):
        return settings(self.strip, self.hunk, self.codecs)

    def ready(self, src):
        """(path, entry) of the up-to-date CHD for src, else (None, None)."""
        out = Path(self.out_dir_fn())
        try:
            name, e = find_output(src, out, self.settings())
        except OSError:
            return None, None
        return (out / name, e) if name else (None, None)

    def request(self, src):
        src = str(Path(src).resolve())
        with self.lock:
            j = self.jobs.get(src)
            if j and j["state"] in ("queued", "stripping", "compressing"):
                return dict(j)
            path, e = self.ready(src)
            if path:
                j = self._done(src, path, e)
                self.jobs[src] = j
                return dict(j)
            j = {"source": src, "state": "queued", "phase": "queued", "progress": 0.0, "error": None, "queued_at": time.time()}
            self.jobs[src] = j
            self.queue.append(src)
            if not self.thread or not self.thread.is_alive():
                self.thread = threading.Thread(target=self._run, name="pxp2-chd", daemon=True)
                self.thread.start()
            self.lock.notify_all()
            return dict(j)

    def status(self, src):
        src = str(Path(src).resolve())
        with self.lock:
            j = self.jobs.get(src)
            if j:
                return dict(j)
        path, e = self.ready(src)
        if path:
            return self._done(src, path, e)
        return {"source": src, "state": "none", "progress": 0.0}

    def all_status(self):
        with self.lock:
            return [dict(j) for j in self.jobs.values()]

    def _done(self, src, path, e):
        return {"source": src, "state": "done", "phase": "done", "progress": 1.0, "chd_file": path.name,
                "chd_size": e["chd_size"], "source_size": e["source_size"], "saved_bytes": e["saved_bytes"],
                "stripped_bytes": e.get("stripped_bytes", 0), "error": None}

    def _run(self):
        while True:
            with self.lock:
                if not self.queue:
                    return
                src = self.queue.pop(0)
                j = self.jobs[src]

            def prog(phase, frac, j=j):
                # stripping is a plain copy (about a tenth of the time); compression is the rest
                j["phase"] = phase
                j["state"] = phase if phase != "done" else "compressing"
                j["progress"] = round(0.1 * frac if phase == "stripping" else 0.1 + 0.9 * frac, 4)
            try:
                e = convert(src, self.out_dir_fn(), self.strip, self.hunk, self.codecs, self.chdman, progress=prog)
                with self.lock:
                    self.jobs[src] = self._done(src, Path(self.out_dir_fn()) / e["name"], e)
                print(f"[CHD] {Path(src).name} -> {e['name']}: {e['source_size'] / 1048576:.1f} MiB -> {e['chd_size'] / 1048576:.1f} MiB"
                      + (" (up to date)" if e.get("skipped") else f" in {e['seconds']} s"))
            except Exception as ex:
                with self.lock:
                    j.update(state="error", phase="error", error=str(ex))
                print(f"[CHD] {Path(src).name}: {ex}")


# ---------------------------------------------------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------------------------------------------------
def _inputs(args):
    out = []
    for a in args:
        p = Path(a)
        if p.is_dir():
            out += sorted(x for x in p.iterdir() if x.is_file() and x.suffix.lower() == ".iso")
        elif p.is_file():
            out.append(p)
    return out


def main(argv=None):
    import argparse
    ap = argparse.ArgumentParser(description="Strip the firmware UPDATE from PSP ISOs and convert them to CHD.")
    ap.add_argument("inputs", nargs="*", help=".iso files or folders of them (never modified)")
    ap.add_argument("--out", help="output folder (default: CHD next to the first input's folder)")
    ap.add_argument("--no-strip", action="store_true", help="keep PSP_GAME/SYSDIR/UPDATE as it is")
    ap.add_argument("--hunk", type=int, default=DEFAULT_HUNK, help="CHD hunk size in bytes, a multiple of 2048 (default 16384)")
    ap.add_argument("--codecs", default=DEFAULT_CODECS, help="chdman codecs (default zstd,lzma)")
    ap.add_argument("--chdman", help="path to chdman")
    ap.add_argument("--force", action="store_true", help="convert even when an up-to-date output exists")
    ap.add_argument("--report", action="store_true", help="only print sizes and what stripping would remove")
    ap.add_argument("--strip-only", metavar="OUT_ISO", help="write the stripped ISO of the single input to OUT_ISO")
    ap.add_argument("--fetch-chdman", action="store_true", help="download chdman into tools/chdman/")
    a = ap.parse_args(argv)
    if a.fetch_chdman:
        print(fetch_chdman())
        return 0
    if a.hunk % SECTOR or a.hunk <= 0:
        ap.error("--hunk must be a multiple of 2048")
    files = _inputs(a.inputs)
    if not files:
        ap.error("no .iso inputs")
    if a.strip_only:
        plan = update_plan(files[0])
        write_stripped(files[0], a.strip_only, plan["ranges"])
        print(f"{files[0].name}: zeroed {plan['bytes']} bytes ({', '.join(plan['files']) or plan['reason']}) -> {a.strip_only}")
        return 0
    out = Path(a.out) if a.out else files[0].parent.parent / "CHD"
    tot = [0, 0, 0]
    for f in files:
        size = f.stat().st_size
        try:
            plan = update_plan(f)
        except Exception as ex:
            print(f"{f.name}: {ex}")
            continue
        if a.report:
            print(f"{f.name}: {size / 1048576:.1f} MiB, {plan['kind']}, UPDATE {plan['bytes'] / 1048576:.1f} MiB "
                  f"({', '.join(Path(x).name for x in plan['files']) or plan['reason']}) -> {output_name(f)}")
            continue
        t0 = time.time()
        last = [0]

        def prog(phase, frac):
            if time.time() - last[0] > 2:
                last[0] = time.time()
                print(f"   {phase} {frac * 100:.0f}%", flush=True)
        e = convert(f, out, not a.no_strip, a.hunk, a.codecs, a.chdman, progress=prog, force=a.force)
        tot[0] += size
        tot[1] += size - e.get("stripped_bytes", 0)
        tot[2] += e["chd_size"]
        print(f"{f.name} -> {e['name']}: ISO {size / 1048576:.1f} MiB, UPDATE {e.get('stripped_bytes', 0) / 1048576:.1f} MiB, "
              f"CHD {e['chd_size'] / 1048576:.1f} MiB ({e['chd_size'] / size * 100:.1f} %)"
              + (" [up to date]" if e.get("skipped") else f" in {time.time() - t0:.0f} s"), flush=True)
    if tot[0]:
        print(f"TOTAL: ISO {tot[0] / 1048576:.1f} MiB, without UPDATE {tot[1] / 1048576:.1f} MiB, CHD {tot[2] / 1048576:.1f} MiB "
              f"({tot[2] / tot[0] * 100:.1f} %), saved {(tot[0] - tot[2]) / 1048576:.1f} MiB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
