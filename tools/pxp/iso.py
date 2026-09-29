"""
ISO 9660 layout analysis for the .pxp encoder (original code, written from ECMA-119).

parse(path) walks the primary volume descriptor's directory tree and returns every directory and file with its extent,
then partition() cuts the image into regions that cover every sector exactly once:

    ("file",  first_sector, sectors, file_index)   data of one file (including the unused tail of its last sector)
    ("dir",   first_sector, sectors, file_index)   a directory's records
    ("system",first_sector, sectors, None)         system area, volume descriptors, path tables, gaps
    ("zero",  first_sector, sectors, None)         all-zero sectors outside any file (stored as nothing)

Files whose extents overlap an earlier one (hard links, zero-length files that point at another file's sector) do not
get their own region: the file table still records their extent, and reads resolve through the region that owns it.
"""

import struct

SECTOR = 2048


class IsoFile:
    __slots__ = ("path", "name", "parent", "lba", "size", "is_dir", "flags", "extents")

    def __init__(self, path, name, parent, lba, size, is_dir, flags):
        self.path, self.name, self.parent, self.lba, self.size, self.is_dir, self.flags = path, name, parent, lba, size, is_dir, flags
        self.extents = [(lba, size)]

    @property
    def sectors(self):
        return (self.size + SECTOR - 1) // SECTOR

    def __repr__(self):
        return f"IsoFile({self.path!r}, lba={self.lba}, size={self.size})"


class IsoImage:
    def __init__(self, path):
        self.path = path
        self.f = open(path, "rb")
        self.f.seek(0, 2)
        self.length = self.f.tell()
        self.sectors = (self.length + SECTOR - 1) // SECTOR
        pvd = self.read(16, 1)
        if pvd[0] != 1 or pvd[1:6] != b"CD001":
            raise ValueError(f"{path}: not an ISO 9660 image")
        self.pvd = pvd
        self.volume_id = pvd[40:72].decode("ascii", "replace").strip()
        self.volume_space = struct.unpack_from("<I", pvd, 80)[0]
        self.files = []
        root = IsoFile("/", "", -1, *_extent(pvd, 156), True, 2)
        self.files.append(root)
        self._walk(0, set())

    def read(self, lba, count):
        self.f.seek(lba * SECTOR)
        return self.f.read(count * SECTOR)

    def read_bytes(self, offset, n):
        self.f.seek(offset)
        return self.f.read(n)

    def _walk(self, index, seen):
        d = self.files[index]
        if (d.lba, d.size) in seen or d.lba >= self.sectors:
            return
        seen.add((d.lba, d.size))
        data = self.read(d.lba, d.sectors)
        p, children = 0, []
        last = None
        while p < len(data):
            n = data[p]
            if n == 0:
                p = (p // SECTOR + 1) * SECTOR
                continue
            if p + 33 > len(data) or p + n > len(data):
                break
            nl = data[p + 32]
            raw = data[p + 33:p + 33 + nl]
            flags = data[p + 25]
            if not (nl == 1 and raw in (b"\0", b"\1")):
                name = raw.decode("latin-1").split(";")[0]
                if name.endswith(".") and not flags & 2:
                    name = name[:-1]
                lba, size = _extent(data, p)
                if last is not None and last.name == name and last.flags & 0x80:
                    last.extents.append((lba, size))           # multi-extent file: later records continue it
                    last.size += size
                    last.flags = flags                            # a clear 0x80 bit ends the chain
                else:
                    path = ("" if d.path == "/" else d.path) + "/" + name
                    f = IsoFile(path, name, index, lba, size, bool(flags & 2), flags)
                    self.files.append(f)
                    children.append(len(self.files) - 1)
                    last = f
            p += n
        for c in children:
            if self.files[c].is_dir:
                self._walk(c, seen)

    def find(self, path):
        want = "/" + path.strip("/").upper()
        for i, f in enumerate(self.files):
            if f.path.upper() == want and not f.is_dir:
                return i
        return None

    def system_extents(self):
        """Sectors the volume descriptors point at besides directories: path tables."""
        out = []
        pt_size = struct.unpack_from("<I", self.pvd, 132)[0]
        n = (pt_size + SECTOR - 1) // SECTOR
        for at, fmt in ((140, "<I"), (144, "<I"), (148, ">I"), (152, ">I")):
            lba = struct.unpack_from(fmt, self.pvd, at)[0]
            if lba and n:
                out.append((lba, n))
        return out

    def partition(self):
        """Regions covering sectors [0, self.sectors) exactly once, in sector order (see module docstring)."""
        owners = []  # (first, count, kind, index)
        for i, f in enumerate(self.files):
            for lba, size in f.extents:
                n = (size + SECTOR - 1) // SECTOR
                if n and lba < self.sectors:
                    owners.append((lba, min(n, self.sectors - lba), "dir" if f.is_dir else "file", i))
        # earlier sector first; at equal start the longer extent owns the sectors
        owners.sort(key=lambda o: (o[0], -o[1], o[3]))
        regions, pos = [], 0
        for first, count, kind, i in owners:
            end = first + count
            if end <= pos:
                continue               # fully inside an earlier region (hard link / duplicate extent)
            if first < pos:
                first, count = pos, end - pos   # partial overlap: the remainder becomes its own region
            if first > pos:
                regions.extend(self._gap(pos, first))
            regions.append((kind, first, count, i))
            pos = end
        if pos < self.sectors:
            regions.extend(self._gap(pos, self.sectors))
        return regions

    def _gap(self, first, end):
        """Splits a run of unowned sectors into zero and system runs (zero runs shorter than 16 sectors stay system)."""
        out, run_start, run_zero = [], first, None
        CH = 512
        s = first
        flags = []
        while s < end:
            n = min(CH, end - s)
            data = self.read(s, n)
            data = data + b"\0" * (n * SECTOR - len(data))
            for k in range(n):
                flags.append(not any(data[k * SECTOR:(k + 1) * SECTOR]))
            s += n
        runs = []
        for k, z in enumerate(flags):
            if runs and runs[-1][2] == z:
                runs[-1][1] += 1
            else:
                runs.append([first + k, 1, z])
        # merge short zero runs into neighbouring system runs
        merged = []
        for r in runs:
            if r[2] and r[1] < 16:
                r[2] = False
            if merged and merged[-1][2] == r[2]:
                merged[-1][1] += r[1]
            else:
                merged.append(r)
        return [("zero" if z else "system", a, n, None) for a, n, z in merged]

    def close(self):
        self.f.close()


def _extent(b, p):
    return struct.unpack_from("<I", b, p + 2)[0], struct.unpack_from("<I", b, p + 10)[0]
