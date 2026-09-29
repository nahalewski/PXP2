#!/usr/bin/env python3
"""
PXP2 PS1 -> PSP converter: builds a POPS ("PS1 Classic") EBOOT.PBP from a PS1 disc image.

Written for PXP2 (GPL-3.0) from the public format descriptions; no code was copied from other converters.
Format references (read for the layout only):
  - PSDevWiki: "Eboot.PBP", "PSISOIMG0000", "POPS" pages (psdevwiki.com).
  - jamescook/psp-toolkit, docs/eboot-format.md (MIT): byte-level notes on the PSISOIMG0000 layout that
    ARK-4 boots (disc ID at +0x400, TOC at +0x800, index at +0x4000, blocks at +0x100000, STARTDAT).
  - ECMA-130 (CD-ROM): sector layout, EDC and the P/Q Reed-Solomon ECC, used to rebuild raw sectors
    from a 2048-byte ".iso".

Output layout (single disc):
  PBP header (0x28)            "\\0PBP", version 0x00010000, eight section offsets
  PARAM.SFO                    CATEGORY "ME", DISC_ID, TITLE, BOOTABLE, REGION 0x8000, ...
  ICON0.PNG (144x80), PIC0.PNG (optional), PIC1.PNG (480x272)
  DATA.PSP                     the POPS boot module (see "DATA.PSP" below)
  DATA.PSAR (0x10000-aligned)  PSISOIMG0000:
     +0x0000 "PSISOIMG0000", u32 offset of STARTDAT
     +0x0400 disc ID "_SLUS_01411"
     +0x0800 TOC (10-byte entries: A0, A1, A2, then one per track), +0x0BFC u32 0x100000
     +0x1220 u32 STARTDAT offset + 0x2D31, bytes FF 07 at +8, title at +0x0C (128 bytes)
     +0x4000 index: 32 bytes per 0x9300-byte block (16 raw sectors):
             u32 offset (from +0x100000), u16 stored size, u16 flags, 16 bytes SHA-1 of the raw block
     +0x100000 blocks, each raw-deflated (RFC 1951); a block stored as-is has size 0x9300
     then STARTDAT: 0x50-byte header, a PNG, and a 4976-byte "\\0PGD" trailer (not checked on CFW).

DATA.PSP: POPS starts through a small signed PRX that ships inside every PS1 EBOOT. It is Sony code, so PXP2
does not include one. Give the converter a DATA.PSP you own (or any PS1 EBOOT.PBP you own, e.g. a PSN
purchase, and it is taken from there): --data-psp PATH, env PXP2_POPS_DATA_PSP, or put it in
<hub>/pops_base/ (DATA.PSP or *.PBP). Without one the EBOOT is still built (so the art, SFO and disc image
can be checked) but carries a placeholder and will not boot on a PSP; build_eboot() reports "pops_ready".

Inputs: .cue (+ .bin tracks, one or many FILEs), raw .bin/.img (2352-byte sectors), cooked .iso/.img
(2048-byte sectors; each sector is rebuilt as Mode 2 Form 1 with EDC/ECC). Multi-disc games are converted
one disc per EBOOT (no PSTITLEIMG0000 yet).
"""

import hashlib
import io
import os
import re
import struct
import sys
import zlib
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

SECTOR = 2352
COOKED = 2048
BLOCK_SECTORS = 16
BLOCK = SECTOR * BLOCK_SECTORS          # 0x9300
ISO_BASE = 0x100000
INDEX_OFF = 0x4000
MAX_BLOCKS = (ISO_BASE - INDEX_OFF) // 32
SYNC = b"\x00" + b"\xff" * 10 + b"\x00"
CONVERTER_VERSION = 3                    # bump to invalidate cached EBOOTs

IMAGE_EXTS = (".cue", ".bin", ".img", ".iso")


# ---------------------------------------------------------------------------------------------
# Disc image access
# ---------------------------------------------------------------------------------------------

def _bcd(n):
    return ((n // 10) << 4) | (n % 10)


def _msf(frames):
    return frames // 4500, (frames // 75) % 60, frames % 75


def _parse_msf(s):
    m, sec, f = (int(x) for x in s.split(":"))
    return (m * 60 + sec) * 75 + f


class DiscImage:
    """A PS1 disc as raw 2352-byte sectors, built from a cue sheet or a single image file."""

    def __init__(self, path):
        self.path = Path(path)
        self.segments = []   # (kind, file, byte_offset, nsectors, cooked) ; kind "file" or "zero"
        self.tracks = []     # dicts: number, ctrl (0x41 data / 0x01 audio), lba (INDEX 01)
        self.total = 0
        self.cooked = False
        if self.path.suffix.lower() == ".cue":
            self._from_cue(self.path)
        else:
            self._from_file(self.path)
        self._handles = {}

    # -- construction --
    def _from_file(self, f):
        size = f.stat().st_size
        with open(f, "rb") as fh:
            head = fh.read(16)
        if size % SECTOR == 0 and head[:12] == SYNC:
            n = size // SECTOR
            self.segments.append(("file", f, 0, n, False))
        elif size % COOKED == 0:
            n = size // COOKED
            self.segments.append(("file", f, 0, n, True))
            self.cooked = True
        else:
            raise ValueError(f"{f.name}: not a raw 2352-byte or 2048-byte disc image")
        self.total = n
        self.tracks.append({"number": 1, "ctrl": 0x41, "lba": 0})

    def _from_cue(self, cue):
        text = cue.read_text(encoding="utf-8", errors="replace")
        cur_file = None
        cur_file_start = 0   # image LBA where the current FILE starts
        cur_file_sectors = 0
        cur_cooked = False
        track = None
        files = []
        for raw in text.splitlines():
            line = raw.strip()
            if not line:
                continue
            up = line.upper()
            if up.startswith("FILE"):
                m = re.match(r'FILE\s+"(.*)"\s+\S+\s*$', line, re.I) or re.match(r"FILE\s+(\S+)\s+\S+\s*$", line, re.I)
                if not m:
                    raise ValueError(f"bad FILE line in {cue.name}: {line}")
                fp = cue.parent / m.group(1)
                if not fp.is_file():
                    # tolerate a renamed image: fall back to the only .bin next to the cue
                    bins = sorted(cue.parent.glob("*.bin"))
                    if len(bins) == 1 and not files:
                        fp = bins[0]
                    else:
                        raise FileNotFoundError(f"{cue.name}: missing track file {m.group(1)}")
                if cur_file is not None:
                    cur_file_start += cur_file_sectors
                cur_file = fp
                cur_cooked = False
                files.append(fp)
                cur_file_sectors = None  # known when the first TRACK gives the mode
            elif up.startswith("TRACK"):
                parts = line.split()
                num, mode = int(parts[1]), parts[2].upper()
                cooked = mode.endswith("/2048")
                if mode.endswith("/2336"):
                    raise ValueError("MODE2/2336 cue tracks are not supported")
                if cur_file_sectors is None:
                    cur_cooked = cooked
                    sz = cur_file.stat().st_size
                    unit = COOKED if cooked else SECTOR
                    cur_file_sectors = sz // unit
                    self.segments.append(("file", cur_file, 0, cur_file_sectors, cooked))
                    if cooked:
                        self.cooked = True
                track = {"number": num, "ctrl": 0x01 if mode == "AUDIO" else 0x41, "lba": None, "file": cur_file}
                self.tracks.append(track)
            elif up.startswith("PREGAP") and track is not None:
                # PREGAP sectors are not stored in the file: zero sectors go in front of the track's file
                if len([t for t in self.tracks if t.get("file") is cur_file]) > 1 or track["lba"] is not None:
                    raise ValueError("PREGAP inside a multi-track FILE is not supported")
                gap = _parse_msf(line.split()[1])
                self.segments.insert(len(self.segments) - 1, ("zero", None, 0, gap, False))
                cur_file_start += gap
            elif up.startswith("INDEX") and track is not None:
                parts = line.split()
                idx, pos = int(parts[1]), _parse_msf(parts[2])
                if idx == 1:
                    track["lba"] = cur_file_start + pos
        if cur_file is None or not self.tracks:
            raise ValueError(f"{cue.name}: no FILE/TRACK entries")
        self.total = sum(s[3] for s in self.segments)
        for t in self.tracks:
            if t["lba"] is None:
                raise ValueError(f"{cue.name}: track {t['number']} has no INDEX 01")

    # -- reading --
    def _fh(self, f):
        h = self._handles.get(f)
        if h is None:
            h = self._handles[f] = open(f, "rb")
        return h

    def close(self):
        for h in self._handles.values():
            h.close()
        self._handles.clear()

    def read_raw(self, lba, count):
        """count raw 2352-byte sectors from lba (zero-filled past the end)."""
        out = bytearray()
        pos = 0
        want_end = lba + count
        for kind, f, off, n, cooked in self.segments:
            seg_start, seg_end = pos, pos + n
            pos = seg_end
            a, b = max(lba, seg_start), min(want_end, seg_end)
            if a >= b:
                continue
            if kind == "zero":
                out += bytes(SECTOR * (b - a))
            elif cooked:
                h = self._fh(f)
                h.seek(off + (a - seg_start) * COOKED)
                data = h.read((b - a) * COOKED)
                data += bytes((b - a) * COOKED - len(data))
                out += cooked_to_raw(data, a)
            else:
                h = self._fh(f)
                h.seek(off + (a - seg_start) * SECTOR)
                data = h.read((b - a) * SECTOR)
                out += data + bytes((b - a) * SECTOR - len(data))
        if len(out) < count * SECTOR:
            out += bytes(count * SECTOR - len(out))
        return bytes(out)

    def read_user(self, lba):
        """2048 bytes of user data of a data sector (Mode 1 or Mode 2 Form 1)."""
        raw = self.read_raw(lba, 1)
        return raw[16:16 + 2048] if raw[15] == 1 else raw[24:24 + 2048]

    def data_size(self):
        return self.total * SECTOR


# ---------------------------------------------------------------------------------------------
# 2048 -> 2352 (Mode 2 Form 1) with EDC/ECC, vectorised with numpy when available
# ---------------------------------------------------------------------------------------------

def _edc_table():
    t = []
    for i in range(256):
        e = i
        for _ in range(8):
            e = (e >> 1) ^ (0xD8018001 if e & 1 else 0)
        t.append(e)
    return t


_EDC = _edc_table()
_F_LUT = [((i << 1) ^ (0x11D if i & 0x80 else 0)) & 0xFF for i in range(256)]
_B_LUT = [0] * 256
for _i in range(256):
    _B_LUT[_i ^ _F_LUT[_i]] = _i

try:
    import numpy as _np
except Exception:  # pragma: no cover
    _np = None


def _ecc_plan(major_count, minor_count, major_mult, minor_inc):
    size = major_count * minor_count
    plan = []
    for major in range(major_count):
        index = (major >> 1) * major_mult + (major & 1)
        seq = []
        for _ in range(minor_count):
            seq.append(index)
            index += minor_inc
            if index >= size:
                index -= size
        plan.append(seq)
    return plan


_P_PLAN = _ecc_plan(86, 24, 2, 86)
_Q_PLAN = _ecc_plan(52, 43, 86, 88)


def cooked_to_raw(data, first_lba):
    """Wraps 2048-byte sectors as raw Mode 2 Form 1 sectors (sync, header, subheader, EDC, ECC)."""
    n = len(data) // COOKED
    if _np is None:
        out = bytearray()
        for i in range(n):
            out += _one_sector(data[i * COOKED:(i + 1) * COOKED], first_lba + i)
        return bytes(out)
    np = _np
    s = np.zeros((n, SECTOR), dtype=np.uint8)
    s[:, 0:12] = np.frombuffer(SYNC, dtype=np.uint8)
    for i in range(n):
        m, sec, f = _msf(first_lba + i + 150)
        s[i, 12:16] = (_bcd(m), _bcd(sec), _bcd(f), 2)
    s[:, 16:24] = (0, 0, 8, 0, 0, 0, 8, 0)
    s[:, 24:24 + COOKED] = np.frombuffer(data[:n * COOKED], dtype=np.uint8).reshape(n, COOKED)
    edc_ecc_mode2_form1(s)
    return s.tobytes()


def edc_ecc_mode2_form1(s):
    """Fills EDC (0x818) and P/Q ECC (0x81C, 0x8C8) of raw Mode 2 Form 1 sectors in place (numpy (n, 2352))."""
    np = _np
    n = s.shape[0]
    table = np.array(_EDC, dtype=np.uint32)
    crc = np.zeros(n, dtype=np.uint32)
    for col in range(16, 0x818):                      # EDC over subheader + user data
        crc = (crc >> 8) ^ table[(crc ^ s[:, col]) & 0xFF]
    s[:, 0x818:0x81C] = crc.astype("<u4").view(np.uint8).reshape(n, 4)
    hdr = s[:, 12:16].copy()                          # ECC treats the Mode 2 header as zero
    s[:, 12:16] = 0
    f_lut = np.array(_F_LUT, dtype=np.uint8)
    b_lut = np.array(_B_LUT, dtype=np.uint8)
    for plan, major_count, dest in ((_P_PLAN, 86, 0x81C), (_Q_PLAN, 52, 0x8C8)):
        idx = np.array(plan, dtype=np.int64) + 12     # (major, minor) byte positions
        a = np.zeros((n, major_count), dtype=np.uint8)
        b = np.zeros((n, major_count), dtype=np.uint8)
        for minor in range(idx.shape[1]):
            t = s[:, idx[:, minor]]
            a ^= t
            b ^= t
            a = f_lut[a]
        a = b_lut[f_lut[a] ^ b]
        s[:, dest:dest + major_count] = a
        s[:, dest + major_count:dest + 2 * major_count] = a ^ b
    s[:, 12:16] = hdr


def _one_sector(user, lba):
    s = bytearray(SECTOR)
    s[0:12] = SYNC
    m, sec, f = _msf(lba + 150)
    s[12:16] = bytes((_bcd(m), _bcd(sec), _bcd(f), 2))
    s[16:24] = b"\x00\x00\x08\x00\x00\x00\x08\x00"
    s[24:24 + COOKED] = user
    crc = 0
    for c in s[16:0x818]:
        crc = (crc >> 8) ^ _EDC[(crc ^ c) & 0xFF]
    s[0x818:0x81C] = struct.pack("<I", crc)
    hdr = bytes(s[12:16])
    s[12:16] = b"\0\0\0\0"
    for plan, major_count, dest in ((_P_PLAN, 86, 0x81C), (_Q_PLAN, 52, 0x8C8)):
        for major, seq in enumerate(plan):
            a = b = 0
            for i in seq:
                t = s[12 + i]
                a ^= t
                b ^= t
                a = _F_LUT[a]
            a = _B_LUT[_F_LUT[a] ^ b]
            s[dest + major] = a
            s[dest + major + major_count] = a ^ b
    s[12:16] = hdr
    return bytes(s)


# ---------------------------------------------------------------------------------------------
# Disc identification (SYSTEM.CNF through ISO9660)
# ---------------------------------------------------------------------------------------------

SERIAL_RE = re.compile(r"^[A-Z]{4}\d{5}$")


def _iso_find(disc, name):
    pvd = disc.read_user(16)
    if pvd[1:6] != b"CD001":
        return None
    root = pvd[156:190]
    lba, size = struct.unpack_from("<I", root, 2)[0], struct.unpack_from("<I", root, 10)[0]
    for k in range((size + 2047) // 2048):
        sec = disc.read_user(lba + k)
        p = 0
        while p < 2048:
            ln = sec[p]
            if ln == 0:
                break
            fid_len = sec[p + 32]
            fid = sec[p + 33:p + 33 + fid_len].decode("ascii", "replace").split(";")[0].upper()
            if fid == name.upper():
                return struct.unpack_from("<I", sec, p + 2)[0], struct.unpack_from("<I", sec, p + 10)[0]
            p += ln
    return None


def serial_from_boot(boot):
    """'cdrom:\\SLUS_014.11;1' -> 'SLUS01411' (None if it does not look like a serial)."""
    name = re.split(r"[\\/:]", boot.strip())[-1].split(";")[0]
    s = re.sub(r"[^A-Za-z0-9]", "", name).upper()
    return s if SERIAL_RE.match(s) else None


def read_disc_id(disc):
    """DISC_ID from SYSTEM.CNF (BOOT=cdrom:\\XXXX_nnn.nn;1), else None."""
    try:
        hit = _iso_find(disc, "SYSTEM.CNF")
        if hit:
            lba, size = hit
            data = b"".join(disc.read_user(lba + i) for i in range((min(size, 4096) + 2047) // 2048))[:size]
            m = re.search(rb"BOOT\s*=\s*(\S+)", data, re.I)
            if m:
                return serial_from_boot(m.group(1).decode("ascii", "replace"))
    except Exception:
        pass
    return None


def pick_image(entry):
    """The image to convert for a library entry (a folder or a file): a .cue, else .bin/.img/.iso."""
    p = Path(entry)
    if p.is_file():
        if p.suffix.lower() in (".bin", ".img"):
            cue = p.with_suffix(".cue")
            if cue.is_file():
                return cue
        return p if p.suffix.lower() in IMAGE_EXTS else None
    if p.is_dir():
        for ext in (".cue", ".bin", ".img", ".iso"):
            files = sorted(f for f in p.iterdir() if f.is_file() and f.suffix.lower() == ext)
            if files:
                return files[0]
    return None


# ---------------------------------------------------------------------------------------------
# PARAM.SFO, PBP, PSISOIMG
# ---------------------------------------------------------------------------------------------

def build_sfo(fields):
    """fields: {key: int | (str, max_len)} -> PARAM.SFO bytes (keys sorted, strings as utf8 0x0204)."""
    keys = sorted(fields)
    key_table = b""
    key_offs = []
    for k in keys:
        key_offs.append(len(key_table))
        key_table += k.encode("ascii") + b"\0"
    while len(key_table) % 4:
        key_table += b"\0"
    header_len = 20 + 16 * len(keys)
    key_start = header_len
    data_start = key_start + len(key_table)
    index = b""
    data = b""
    for k, ko in zip(keys, key_offs):
        v = fields[k]
        if isinstance(v, int):
            raw, fmt, used, mx = struct.pack("<I", v), 0x0404, 4, 4
        else:
            s, mx = v
            raw = s.encode("utf-8")[:mx - 1] + b"\0"
            fmt, used = 0x0204, len(raw)
            raw = raw.ljust(mx, b"\0")
        index += struct.pack("<HHIII", ko, fmt, used, mx, len(data))
        data += raw
    head = b"\0PSF" + struct.pack("<IIII", 0x0101, key_start, data_start, len(keys))
    return head + index + key_table + data


def _png_blank(w, h, gray=0):
    raw = b"".join(b"\0" + bytes([gray]) * w for _ in range(h))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 0, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def _startdat():
    logo = _png_blank(480, 272, 0)
    head = bytearray(0x50)
    head[0:8] = b"STARTDAT"
    struct.pack_into("<IIII", head, 8, 1, 1, 0x50, len(logo))
    trailer = bytearray(4976)
    trailer[0:4] = b"\0PGD"
    struct.pack_into("<II", trailer, 4, 1, 1)
    return bytes(head) + logo + bytes(trailer)


def build_toc(disc):
    """TOC entries as POPS reads them: A0 (first track, PSEC 0x20 = CD-XA), A1 (last), A2 (lead-out), tracks."""
    tr = disc.tracks
    out = bytearray()

    def entry(ctrl, point, frames=None, pmin=0, psec=0, pframe=0):
        if frames is not None:
            pmin, psec, pframe = (_bcd(x) for x in _msf(frames))
        out.extend(bytes((ctrl, 0, point, 0, 0, 0, 0, pmin, psec, pframe)))
    entry(tr[0]["ctrl"], 0xA0, pmin=_bcd(tr[0]["number"]), psec=0x20)
    entry(tr[-1]["ctrl"], 0xA1, pmin=_bcd(tr[-1]["number"]))
    entry(tr[-1]["ctrl"], 0xA2, frames=disc.total + 150)
    for t in tr:
        entry(t["ctrl"], _bcd(t["number"]), frames=t["lba"] + 150)
    return bytes(out)


def find_data_psp(explicit=None, base_dirs=()):
    """(bytes, source) for DATA.PSP, from a file or a PBP the user owns; (None, None) if there is none."""
    cands = []
    if explicit:
        cands.append(Path(explicit))
    if os.environ.get("PXP2_POPS_DATA_PSP"):
        cands.append(Path(os.environ["PXP2_POPS_DATA_PSP"]))
    for d in base_dirs:
        d = Path(d)
        if d.is_dir():
            cands.append(d / "DATA.PSP")
            cands += sorted(d.glob("*.PBP")) + sorted(d.glob("*.pbp"))
    for c in cands:
        try:
            if not c.is_file():
                continue
            with open(c, "rb") as f:
                head = f.read(40)
                if head[:4] == b"\0PBP":
                    offs = struct.unpack_from("<8I", head, 8)
                    f.seek(offs[6])
                    data = f.read(offs[7] - offs[6])
                    # only a PS1 EBOOT's module is useful (CATEGORY "ME")
                    f.seek(offs[0])
                    sfo = f.read(offs[1] - offs[0])
                    if b"ME\0" not in sfo:
                        continue
                else:
                    f.seek(0)
                    data = f.read()
            if data[:4] in (b"~PSP", b"\x7fELF") and len(data) > 1024:
                return data, str(c)
        except OSError:
            continue
    return None, None


PLACEHOLDER_DATA_PSP = b"PXP2-NO-DATA.PSP" + b"\0" * (0x200 - 16)


def build_eboot(image, out_path, disc_id, title, icon0=None, pic0=None, pic1=None, data_psp=None,
                level=6, threads=None, progress=None):
    """Converts `image` (.cue/.bin/.img/.iso) into a POPS EBOOT.PBP at out_path. Returns a summary dict."""
    disc = DiscImage(image)
    try:
        n_blocks = (disc.total + BLOCK_SECTORS - 1) // BLOCK_SECTORS
        if n_blocks > MAX_BLOCKS:
            raise ValueError(f"disc too large for one PSISOIMG ({disc.total} sectors)")
        disc_id = (disc_id or "").upper()
        if not SERIAL_RE.match(disc_id):
            raise ValueError(f"bad DISC_ID {disc_id!r}")
        pops_ready = data_psp is not None
        dpsp = data_psp if pops_ready else PLACEHOLDER_DATA_PSP
        sfo = build_sfo({
            "BOOTABLE": 1,
            "CATEGORY": ("ME", 4),
            "DISC_ID": (disc_id, 16),
            "DISC_VERSION": ("1.00", 8),
            "LICENSE": ("Copyright(C) Sony Computer Entertainment America Inc.", 512),
            "PARENTAL_LEVEL": 3,
            "PSP_SYSTEM_VER": ("3.01", 8),
            "REGION": 0x8000,
            "TITLE": (title, 128),
        })
        sections = [sfo, icon0 or b"", b"", pic0 or b"", pic1 or b"", b"", dpsp]
        offs = []
        pos = 0x28
        for s in sections:
            offs.append(pos)
            pos += len(s)
        psar = (pos + 0xFFFF) & ~0xFFFF
        header = b"\0PBP" + struct.pack("<I", 0x00010000) + struct.pack("<8I", *offs, psar)

        out_path = Path(out_path)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = out_path.with_suffix(".tmp")
        index = bytearray(32 * n_blocks)
        with open(tmp, "wb") as f:
            f.write(header)
            for s in sections:
                f.write(s)
            f.write(bytes(psar - f.tell()))
            f.write(bytes(ISO_BASE))            # PSISOIMG header + index, filled in below
            rel = 0

            workers = threads or min(8, (os.cpu_count() or 2))
            # reading is serial (one file handle); compression runs in threads (zlib releases the GIL)
            with ThreadPoolExecutor(max_workers=workers) as ex:
                batch = workers * 4
                for start in range(0, n_blocks, batch):
                    raws = [disc.read_raw(i * BLOCK_SECTORS, BLOCK_SECTORS) for i in range(start, min(n_blocks, start + batch))]
                    comps = list(ex.map(_deflate, raws, [level] * len(raws)))
                    for j, (raw, comp) in enumerate(zip(raws, comps)):
                        i = start + j
                        stored = comp if len(comp) < BLOCK else raw
                        struct.pack_into("<IHH", index, 32 * i, rel, len(stored), 0)
                        index[32 * i + 8:32 * i + 24] = hashlib.sha1(raw).digest()[:16]
                        f.write(stored)
                        rel += len(stored)
                    if progress:
                        progress(min(n_blocks, start + batch) / n_blocks)
            end = ISO_BASE + rel
            aligned = (end + 15) & ~15
            f.write(bytes(aligned - end))
            f.write(_startdat())

            ps = bytearray(INDEX_OFF)
            ps[0:12] = b"PSISOIMG0000"
            struct.pack_into("<I", ps, 0x0C, aligned)
            did = ("_" + disc_id[:4] + "_" + disc_id[4:]).encode("ascii")
            ps[0x400:0x400 + len(did)] = did
            toc = build_toc(disc)
            ps[0x800:0x800 + len(toc)] = toc
            struct.pack_into("<I", ps, 0xBFC, ISO_BASE)
            struct.pack_into("<I", ps, 0x1220, aligned + 0x2D31)
            ps[0x1228:0x122A] = b"\xff\x07"
            t = title.encode("utf-8")[:127]
            ps[0x122C:0x122C + len(t)] = t
            f.seek(psar)
            f.write(ps)
            f.write(index)
        os.replace(tmp, out_path)
        return {
            "path": str(out_path), "size": out_path.stat().st_size, "disc_id": disc_id, "title": title,
            "sectors": disc.total, "tracks": len(disc.tracks), "cooked_source": disc.cooked,
            "compressed": rel, "pops_ready": pops_ready,
        }
    finally:
        disc.close()


def _deflate(raw, level):
    c = zlib.compressobj(level, zlib.DEFLATED, -15)
    return c.compress(raw) + c.flush()


# ---------------------------------------------------------------------------------------------
# Reading an EBOOT back (used by the self-test and the hub)
# ---------------------------------------------------------------------------------------------

def read_pbp(path):
    with open(path, "rb") as f:
        head = f.read(40)
        if head[:4] != b"\0PBP":
            raise ValueError("not a PBP")
        offs = list(struct.unpack_from("<8I", head, 8))
        size = os.path.getsize(path)
        names = ["PARAM.SFO", "ICON0.PNG", "ICON1.PMF", "PIC0.PNG", "PIC1.PNG", "SND0.AT3", "DATA.PSP", "DATA.PSAR"]
        out = {}
        for i, n in enumerate(names[:7]):
            f.seek(offs[i])
            out[n] = f.read(offs[i + 1] - offs[i])
        out["_psar"] = offs[7]
        out["_size"] = size
        return out


def parse_sfo(b):
    if b[:4] != b"\0PSF":
        return {}
    kt, dt, n = struct.unpack_from("<III", b, 8)
    out = {}
    for i in range(n):
        ko, fmt, used, mx, do = struct.unpack_from("<HHIII", b, 20 + 16 * i)
        k = b[kt + ko:b.index(b"\0", kt + ko)].decode()
        v = b[dt + do:dt + do + used]
        out[k] = struct.unpack("<I", v)[0] if fmt == 0x0404 else v.rstrip(b"\0").decode("utf-8", "replace")
    return out


def read_psiso_sector(path, lba):
    """Reads back one raw sector from a converted EBOOT (inflating its block)."""
    with open(path, "rb") as f:
        head = f.read(40)
        psar = struct.unpack_from("<I", head, 36)[0]
        blk = lba // BLOCK_SECTORS
        f.seek(psar + INDEX_OFF + 32 * blk)
        off, ln, _ = struct.unpack("<IHH", f.read(8))
        f.seek(psar + ISO_BASE + off)
        data = f.read(ln)
        raw = data if ln == BLOCK else zlib.decompress(data, -15)
        k = lba % BLOCK_SECTORS
        return raw[k * SECTOR:(k + 1) * SECTOR]


def main(argv=None):
    import argparse
    ap = argparse.ArgumentParser(description="PXP2 PS1 -> PSP POPS EBOOT.PBP converter")
    ap.add_argument("image", help=".cue, .bin, .img or .iso (or a folder holding one)")
    ap.add_argument("out", help="output EBOOT.PBP")
    ap.add_argument("--id", help="DISC_ID (default: from SYSTEM.CNF)")
    ap.add_argument("--title", help="TITLE (default: file name)")
    ap.add_argument("--data-psp", help="DATA.PSP or a PS1 EBOOT.PBP you own to take it from")
    ap.add_argument("--level", type=int, default=6)
    a = ap.parse_args(argv)
    img = pick_image(a.image)
    if not img:
        sys.exit("no disc image found")
    disc = DiscImage(img)
    did = a.id or read_disc_id(disc) or "SLUS00000"
    disc.close()
    dpsp, src = find_data_psp(a.data_psp, [Path(__file__).resolve().parent.parent / "pops_base"])
    title = a.title or re.sub(r"\s*\([^)]*\)", "", Path(img).stem).strip()
    r = build_eboot(img, a.out, did, title, data_psp=dpsp, level=a.level,
                    progress=lambda x: print(f"\r{x * 100:5.1f}%", end="", flush=True))
    print()
    r["data_psp_source"] = src
    print(r)


if __name__ == "__main__":
    main()
