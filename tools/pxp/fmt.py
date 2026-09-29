"""
.pxp container: byte-level structures shared by the encoder, reader and library store.

Every integer is little-endian. The full specification (with rationale) is docs/pxp-format.md; this module is its
executable form. Layout of a file:

    0            header (128 bytes)
    128          index (sections, usually zstd-compressed)
    hash_offset  chunk hash table (chunk_count x 16 bytes: SHA-256[:16] of each chunk's uncompressed data)
    data_offset  data area (4096-aligned): chunk payloads in chunk-id order, then extra metadata blobs
"""

import hashlib
import struct
import zlib
from dataclasses import dataclass, field

MAGIC = b"PXP2\r\n\x1a\n"
VERSION_MAJOR, VERSION_MINOR = 1, 0
HEADER_SIZE = 128
DATA_ALIGN = 4096
HASH_BYTES = 16
NONE32 = 0xFFFFFFFF

# header flags
HF_EXTERNAL = 1      # library-store manifest: chunk payloads live in the store, not in this file
HF_STRIPPED = 2      # some image regions (the UPDATE payload) have no data in this file
HF_OVERLAYS = 4      # at least one overlay layer is present

# index codecs
IDX_RAW, IDX_ZSTD = 0, 1

# chunk codecs
C_RAW, C_ZSTD, C_ZSTD_DICT, C_LZMA, C_ZERO = 0, 1, 2, 3, 4
CODEC_NAMES = {C_RAW: "raw", C_ZSTD: "zstd", C_ZSTD_DICT: "zstd+dict", C_LZMA: "lzma", C_ZERO: "zero"}
# chunk flags
CF_ABSENT = 1        # payload not in this file (stripped, or external in a library manifest)

# stream roles
R_SYSTEM, R_DIRECTORY, R_FILE, R_STRIPPED, R_OVERLAY = 0, 1, 2, 3, 4
ROLE_NAMES = {R_SYSTEM: "system", R_DIRECTORY: "directory", R_FILE: "file", R_STRIPPED: "stripped", R_OVERLAY: "overlay"}

# segment flags
SF_STRIPPED = 1

# disc kinds
DK_ISO9660, DK_RAW = 0, 1
# disc flags
DF_STRIPPED = 1

# file flags
FF_DIR, FF_STRIPPED, FF_META, FF_MULTI_EXTENT = 1, 2, 4, 8

# metadata kinds (4CC) and the image paths they come from
META_FROM_IMAGE = [  # (kind, path under the disc root) in placement order: small first, so an XMB list fills quickly
    (b"SFO ", "PSP_GAME/PARAM.SFO"),
    (b"ICN0", "PSP_GAME/ICON0.PNG"),
    (b"PIC1", "PSP_GAME/PIC1.PNG"),
    (b"PIC0", "PSP_GAME/PIC0.PNG"),
    (b"ICN1", "PSP_GAME/ICON1.PMF"),
    (b"SND0", "PSP_GAME/SND0.AT3"),
    (b"UMDD", "UMD_DATA.BIN"),
    (b"SFOV", "UMD_VIDEO/PARAM.SFO"),
    (b"ICNV", "UMD_VIDEO/ICON0.PNG"),
    (b"PC1V", "UMD_VIDEO/PIC1.PNG"),
]
META_BOX, META_DISC_ART, META_SETTINGS = b"BOXA", b"DSCA", b"SETJ"
# metadata flags
MF_IN_IMAGE = 1      # the bytes are also the raw chunk payloads of the image file (stored once)

# overlay layer kinds
OK_DISC_FILES = 0    # file replacements on the disc file system
OK_HOST_FILES = 1    # files for the host (texture packs): documented, not applied by the core yet
LF_ENABLED = 1       # layer applied by default


def crc32(b):
    return zlib.crc32(b) & 0xFFFFFFFF


def h16(b):
    return hashlib.sha256(b).digest()[:HASH_BYTES]


def align(n, a):
    return (n + a - 1) // a * a


@dataclass
class Header:
    flags: int = 0
    chunk_size: int = 65536
    index_offset: int = HEADER_SIZE
    index_stored: int = 0
    index_size: int = 0
    index_codec: int = IDX_ZSTD
    chunk_count: int = 0
    hash_offset: int = 0
    data_offset: int = 0
    data_size: int = 0
    index_sha256: bytes = b"\0" * 32
    major: int = VERSION_MAJOR
    minor: int = VERSION_MINOR

    FMT = "<8sHHIIIQIIB3xIQQQ32s20x"

    def pack(self):
        b = struct.pack(self.FMT, MAGIC, self.major, self.minor, HEADER_SIZE, self.flags, self.chunk_size,
                        self.index_offset, self.index_stored, self.index_size, self.index_codec, self.chunk_count,
                        self.hash_offset, self.data_offset, self.data_size, self.index_sha256)
        assert len(b) == HEADER_SIZE - 4
        return b + struct.pack("<I", crc32(b))

    @classmethod
    def unpack(cls, b):
        if len(b) < HEADER_SIZE or b[:8] != MAGIC:
            raise ValueError("not a .pxp file")
        if struct.unpack_from("<I", b, 124)[0] != crc32(b[:124]):
            raise ValueError("header CRC mismatch")
        (_, major, minor, hsize, flags, cs, io, ist, isz, icodec, cc, ho, do, ds, isha) = struct.unpack_from(cls.FMT, b)
        if major != VERSION_MAJOR:
            raise ValueError(f"unsupported .pxp major version {major}")
        return cls(flags, cs, io, ist, isz, icodec, cc, ho, do, ds, isha, major, minor)


@dataclass
class Chunk:
    stored: int
    raw: int
    codec: int
    dict: int = 0
    flags: int = 0
    hash: bytes = b""
    offset: int = -1          # relative to the data area; computed when loading (prefix sum of present chunks)


@dataclass
class Stream:
    length: int
    chunks: list              # chunk ids
    role: int = R_SYSTEM
    disc: int = 0
    flags: int = 0


@dataclass
class Segment:
    disc: int
    sector: int
    count: int
    stream: int               # NONE32 = zero-filled
    stream_sector: int = 0
    flags: int = 0


@dataclass
class FileEntry:
    disc: int
    parent: int
    name: str
    flags: int
    lba: int
    size: int
    boot_rank: int = NONE32


@dataclass
class Disc:
    sector_size: int
    length: int
    kind: int = DK_ISO9660
    flags: int = 0
    first_segment: int = 0
    segment_count: int = 0
    first_file: int = 0
    file_count: int = 0
    sha_original: bytes = b"\0" * 32
    sha_stored: bytes = b"\0" * 32


@dataclass
class MetaItem:
    kind: bytes
    disc: int
    offset: int               # relative to the data area
    length: int
    flags: int = 0


@dataclass
class Stripped:
    file: int
    stream: int
    size: int
    sha256: bytes


@dataclass
class OverlayEntry:
    disc: int
    target: int               # file index replaced, or NONE32 for an added file
    path: str
    stream: int
    size: int
    flags: int = 0


@dataclass
class Layer:
    name: str
    kind: int = OK_DISC_FILES
    flags: int = LF_ENABLED
    entries: list = field(default_factory=list)


@dataclass
class Index:
    info: dict = field(default_factory=dict)
    discs: list = field(default_factory=list)
    dicts: list = field(default_factory=list)       # [(dict_id, bytes)]
    chunks: list = field(default_factory=list)
    streams: list = field(default_factory=list)
    segments: list = field(default_factory=list)
    files: list = field(default_factory=list)
    meta: list = field(default_factory=list)
    stripped: list = field(default_factory=list)
    layers: list = field(default_factory=list)
    order: list = field(default_factory=list)
    settings: dict = field(default_factory=dict)
    unknown: list = field(default_factory=list)     # [(tag, payload)] kept on rewrite


# ---------------------------------------------------------------------------------------------- index serialisation

def _kv(d):
    out = [struct.pack("<I", len(d))]
    for k, v in d.items():
        kb, vb = str(k).encode("utf-8"), str(v).encode("utf-8")
        out.append(struct.pack("<H", len(kb)) + kb + struct.pack("<I", len(vb)) + vb)
    return b"".join(out)


def _unkv(b):
    n, = struct.unpack_from("<I", b, 0)
    p, out = 4, {}
    for _ in range(n):
        kl, = struct.unpack_from("<H", b, p); p += 2
        k = b[p:p + kl].decode("utf-8"); p += kl
        vl, = struct.unpack_from("<I", b, p); p += 4
        out[k] = b[p:p + vl].decode("utf-8"); p += vl
    return out


def _section(tag, payload):
    return tag + struct.pack("<I", len(payload)) + payload + b"\0" * (-len(payload) % 4)


class _Names:
    def __init__(self):
        self.buf, self.at = bytearray(), {}

    def add(self, s):
        b = s.encode("utf-8")
        if b not in self.at:
            self.at[b] = len(self.buf)
            self.buf += b
        return self.at[b], len(b)


def build_index(ix):
    names = _Names()
    s = []
    s.append(_section(b"INFO", _kv(ix.info)))
    b = [struct.pack("<I", len(ix.discs))]
    for d in ix.discs:
        b.append(struct.pack("<IBBHQIIII32s32s", d.sector_size, d.kind, d.flags, 0, d.length, d.first_segment,
                             d.segment_count, d.first_file, d.file_count, d.sha_original, d.sha_stored))
    s.append(_section(b"DISC", b"".join(b)))
    b = [struct.pack("<I", len(ix.dicts))]
    for did, content in ix.dicts:
        b.append(struct.pack("<II", did, len(content)) + content + b"\0" * (-len(content) % 4))
    s.append(_section(b"DICT", b"".join(b)))
    b = [struct.pack("<I", len(ix.chunks))]
    b += [struct.pack("<IIBBH", c.stored, c.raw, c.codec, c.dict, c.flags) for c in ix.chunks]
    s.append(_section(b"CHNK", b"".join(b)))
    b, cl = [struct.pack("<I", len(ix.streams))], []
    for st in ix.streams:
        b.append(struct.pack("<QIIBBHI", st.length, len(cl), len(st.chunks), st.role, st.disc, st.flags, 0))
        prev = -1
        for cid in st.chunks:
            cl.append((cid - prev - 1) & 0xFFFFFFFF)
            prev = cid
    s.append(_section(b"STRM", b"".join(b)))
    s.append(_section(b"CLST", struct.pack("<I", len(cl)) + struct.pack(f"<{len(cl)}I", *cl)))
    b = [struct.pack("<I", len(ix.segments))]
    b += [struct.pack("<IIIIII", g.disc, g.sector, g.count, g.stream, g.stream_sector, g.flags) for g in ix.segments]
    s.append(_section(b"SEGS", b"".join(b)))
    b = [struct.pack("<I", len(ix.files))]
    for f in ix.files:
        no, nl = names.add(f.name)
        b.append(struct.pack("<IIIHHIQI", f.disc, f.parent, no, nl, f.flags, f.lba, f.size, f.boot_rank))
    fbytes = b"".join(b)
    b = [struct.pack("<I", len(ix.meta))]
    b += [struct.pack("<4sIQII", m.kind, m.disc, m.offset, m.length, m.flags) for m in ix.meta]
    mbytes = b"".join(b)
    b = [struct.pack("<I", len(ix.stripped))]
    b += [struct.pack("<IIQ32s", x.file, x.stream, x.size, x.sha256) for x in ix.stripped]
    sbytes = b"".join(b)
    lb, eb, entries = [struct.pack("<I", len(ix.layers))], [], 0
    for L in ix.layers:
        no, nl = names.add(L.name)
        lb.append(struct.pack("<IHHIIII", no, nl, L.kind, L.flags, entries, len(L.entries), 0))
        for e in L.entries:
            po, pl = names.add(e.path)
            eb.append(struct.pack("<IIIHHIQI", e.disc, e.target, po, pl, e.flags, e.stream, e.size, 0))
        entries += len(L.entries)
    obytes = b"".join(lb) + struct.pack("<I", entries) + b"".join(eb)
    s.append(_section(b"NAME", bytes(names.buf)))
    s.append(_section(b"FILE", fbytes))
    s.append(_section(b"META", mbytes))
    s.append(_section(b"STRP", sbytes))
    s.append(_section(b"OVLY", obytes))
    s.append(_section(b"ORDR", struct.pack("<I", len(ix.order)) + struct.pack(f"<{len(ix.order)}I", *ix.order)))
    s.append(_section(b"SETT", _kv(ix.settings)))
    for tag, payload in ix.unknown:
        s.append(_section(tag, payload))
    s.append(_section(b"END ", b""))
    return b"".join(s)


def parse_index(b, chunk_count=None):
    ix, p, sec = Index(), 0, {}
    while p + 8 <= len(b):
        tag = bytes(b[p:p + 4]); n, = struct.unpack_from("<I", b, p + 4)
        payload = bytes(b[p + 8:p + 8 + n]); p += 8 + n + (-n % 4)
        if tag == b"END ":
            break
        sec[tag] = payload
    known = {b"INFO", b"DISC", b"DICT", b"CHNK", b"STRM", b"CLST", b"SEGS", b"NAME", b"FILE", b"META", b"STRP",
             b"OVLY", b"ORDR", b"SETT"}
    ix.unknown = [(t, v) for t, v in sec.items() if t not in known]
    ix.info = _unkv(sec[b"INFO"]) if b"INFO" in sec else {}
    ix.settings = _unkv(sec[b"SETT"]) if b"SETT" in sec else {}
    d = sec[b"DISC"]
    for i in range(struct.unpack_from("<I", d)[0]):
        v = struct.unpack_from("<IBBHQIIII32s32s", d, 4 + 96 * i)
        ix.discs.append(Disc(v[0], v[4], v[1], v[2], v[5], v[6], v[7], v[8], v[9], v[10]))
    d, q = sec.get(b"DICT", b"\0\0\0\0"), 4
    for _ in range(struct.unpack_from("<I", d)[0]):
        did, n = struct.unpack_from("<II", d, q)
        ix.dicts.append((did, d[q + 8:q + 8 + n])); q += 8 + n + (-n % 4)
    d = sec[b"CHNK"]
    for i in range(struct.unpack_from("<I", d)[0]):
        st, raw, codec, dic, fl = struct.unpack_from("<IIBBH", d, 4 + 12 * i)
        ix.chunks.append(Chunk(st, raw, codec, dic, fl))
    off = 0
    for c in ix.chunks:
        if not c.flags & CF_ABSENT:
            c.offset = off; off += c.stored
    d, cl = sec[b"STRM"], sec[b"CLST"]
    ncl, = struct.unpack_from("<I", cl)
    clist = struct.unpack_from(f"<{ncl}I", cl, 4)
    for i in range(struct.unpack_from("<I", d)[0]):
        length, first, count, role, disc, fl, _ = struct.unpack_from("<QIIBBHI", d, 4 + 24 * i)
        ids, prev = [], -1
        for v in clist[first:first + count]:
            prev = (prev + 1 + v) & 0xFFFFFFFF
            ids.append(prev)
        ix.streams.append(Stream(length, ids, role, disc, fl))
    d = sec[b"SEGS"]
    for i in range(struct.unpack_from("<I", d)[0]):
        ix.segments.append(Segment(*struct.unpack_from("<IIIIII", d, 4 + 24 * i)))
    names = sec.get(b"NAME", b"")
    d = sec.get(b"FILE", b"\0\0\0\0")
    for i in range(struct.unpack_from("<I", d)[0]):
        disc, parent, no, nl, fl, lba, size, rank = struct.unpack_from("<IIIHHIQI", d, 4 + 32 * i)
        ix.files.append(FileEntry(disc, parent, names[no:no + nl].decode("utf-8"), fl, lba, size, rank))
    d = sec.get(b"META", b"\0\0\0\0")
    for i in range(struct.unpack_from("<I", d)[0]):
        ix.meta.append(MetaItem(*struct.unpack_from("<4sIQII", d, 4 + 24 * i)))
    d = sec.get(b"STRP", b"\0\0\0\0")
    for i in range(struct.unpack_from("<I", d)[0]):
        ix.stripped.append(Stripped(*struct.unpack_from("<IIQ32s", d, 4 + 48 * i)))
    d = sec.get(b"OVLY")
    if d:
        nl_, = struct.unpack_from("<I", d)
        heads = [struct.unpack_from("<IHHIIII", d, 4 + 24 * i) for i in range(nl_)]
        q = 4 + 24 * nl_
        ne, = struct.unpack_from("<I", d, q)
        ents = []
        for i in range(ne):
            disc, target, po, pl, fl, stream, size, _ = struct.unpack_from("<IIIHHIQI", d, q + 4 + 32 * i)
            ents.append(OverlayEntry(disc, target, names[po:po + pl].decode("utf-8"), stream, size, fl))
        for no, nl, kind, fl, first, count, _ in heads:
            ix.layers.append(Layer(names[no:no + nl].decode("utf-8"), kind, fl, ents[first:first + count]))
    d = sec.get(b"ORDR")
    if d:
        n, = struct.unpack_from("<I", d)
        ix.order = list(struct.unpack_from(f"<{n}I", d, 4))
    return ix


def file_paths(ix):
    """Full path ("/PSP_GAME/SYSDIR/BOOT.BIN") of every file-table entry."""
    out = []
    for i, f in enumerate(ix.files):
        if f.parent == NONE32:
            out.append("" if f.flags & FF_DIR else "/" + f.name)
        else:
            out.append(out[f.parent] + "/" + f.name)
    return [p if p else "/" for p in out]
