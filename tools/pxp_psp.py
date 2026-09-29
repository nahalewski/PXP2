#!/usr/bin/env python3
"""
PXP2 hub: PSP disc metadata and device file names.

read_sfo(path)          PARAM.SFO of a PSP .iso or .cso (PSP_GAME/PARAM.SFO, else UMD_VIDEO/PARAM.SFO) as a dict.
device_filename(...)    "<TITLE> [ULUS-10081].iso": the name a PSP disc is saved under on the device.
The user's files on the PC are not renamed; see pxp_server.py --rename-sources for the explicit option.
"""

import re
import struct
import zlib
from pathlib import Path

SECTOR = 2048


class _Reader:
    """Random access to the 2048-byte sectors of an .iso or a .cso (CISO v1)."""

    def __init__(self, path):
        self.f = open(path, "rb")
        head = self.f.read(24)
        self.cso = head[:4] == b"CISO"
        if self.cso:
            _, hsize, total, bsize, ver, align = struct.unpack("<4sIQIBB", head[:22])
            self.bsize, self.align = bsize, align
            n = total // bsize
            self.f.seek(24)
            self.index = struct.unpack(f"<{n + 1}I", self.f.read(4 * (n + 1)))

    def read(self, lba, count=1):
        if not self.cso:
            self.f.seek(lba * SECTOR)
            return self.f.read(count * SECTOR)
        out = bytearray()
        for b in range(lba * SECTOR // self.bsize, ((lba + count) * SECTOR + self.bsize - 1) // self.bsize):
            e, e2 = self.index[b], self.index[b + 1]
            pos, end = (e & 0x7FFFFFFF) << self.align, (e2 & 0x7FFFFFFF) << self.align
            self.f.seek(pos)
            data = self.f.read(end - pos)
            out += data[:self.bsize] if e & 0x80000000 else zlib.decompress(data, -15)[:self.bsize]
        skip = lba * SECTOR - (lba * SECTOR // self.bsize) * self.bsize
        return bytes(out[skip:skip + count * SECTOR])

    def close(self):
        self.f.close()


def _find(r, path):
    pvd = r.read(16)
    if pvd[1:6] != b"CD001":
        return None
    rec = pvd[156:190]
    lba, size = struct.unpack_from("<I", rec, 2)[0], struct.unpack_from("<I", rec, 10)[0]
    for part in path.strip("/").split("/"):
        data = r.read(lba, (size + SECTOR - 1) // SECTOR)
        p, hit = 0, None
        while p < len(data):
            ln = data[p]
            if ln == 0:
                p = (p // SECTOR + 1) * SECTOR
                continue
            fid = data[p + 33:p + 33 + data[p + 32]].decode("ascii", "replace").split(";")[0].upper()
            if fid == part.upper():
                hit = struct.unpack_from("<I", data, p + 2)[0], struct.unpack_from("<I", data, p + 10)[0]
                break
            p += ln
        if not hit:
            return None
        lba, size = hit
    return lba, size


def parse_sfo(b):
    if b[:4] != b"\0PSF":
        return {}
    kt, dt, n = struct.unpack_from("<III", b, 8)
    out = {}
    for i in range(n):
        ko, fmt, used, mx, do = struct.unpack_from("<HHIII", b, 20 + 16 * i)
        end = b.find(b"\0", kt + ko)
        k = b[kt + ko:end].decode("ascii", "replace")
        v = b[dt + do:dt + do + used]
        out[k] = struct.unpack("<I", v[:4])[0] if fmt == 0x0404 else v.split(b"\0")[0].decode("utf-8", "replace")
    return out


def read_sfo(path):
    r = _Reader(path)
    try:
        for p in ("PSP_GAME/PARAM.SFO", "UMD_VIDEO/PARAM.SFO"):
            hit = _find(r, p)
            if hit and 0 < hit[1] < 65536:
                return parse_sfo(r.read(hit[0], (hit[1] + SECTOR - 1) // SECTOR)[:hit[1]])
    finally:
        r.close()
    return {}


def format_id(disc_id):
    """'ULUS10081' -> 'ULUS-10081'."""
    s = re.sub(r"[^A-Za-z0-9]", "", disc_id or "").upper()
    return s[:4] + "-" + s[4:] if re.match(r"^[A-Z]{4}\d{5}$", s) else s


def clean_title(title):
    t = re.sub(r"[\x00-\x1f]+", " ", title or "")
    t = t.replace("™", "").replace("®", "").replace("©", "")
    t = re.sub(r"\s*:\s+", " - ", t)           # "God of War: Chains" -> "God of War - Chains"
    t = re.sub(r'[<>:"/\\|?*]', " ", t)        # characters FAT/Android storage cannot hold
    t = re.sub(r"\s+", " ", t).strip(" .")
    return t


def device_filename(title, disc_id, ext=".iso"):
    """'<Game Name> [ULUS-10081].iso' (None without both a title and an ID)."""
    t, i = clean_title(title), format_id(disc_id)
    if not t or not i:
        return None
    return f"{t[:120]} [{i}]{ext.lower()}"


if __name__ == "__main__":
    import sys
    for a in sys.argv[1:]:
        s = read_sfo(a)
        print(a, "->", device_filename(s.get("TITLE"), s.get("DISC_ID"), Path(a).suffix), s.get("CATEGORY"))
