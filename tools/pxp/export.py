"""
Export a .pxp back to ISO, CSO (CISO v1, raw deflate) or ZSO (ZISO v1, LZ4 blocks).

The ISO is bit-exact: the original disc when the stripped UPDATE payload is available (kept in the file or in the
library store), otherwise the stripped disc; both are checked against the SHA-256 recorded at encode time.
CSO/ZSO decompress to that same ISO (the block encoding itself is deterministic for a given zlib/lz4 version).

ZSO needs the "lz4" Python package (BSD-3-Clause) for good ratios; without it blocks are stored uncompressed.
"""

import hashlib
import os
import struct
import zlib
from concurrent.futures import ProcessPoolExecutor

from . import fmt
from .reader import PxpReader

try:
    import lz4.block as _lz4
except Exception:  # optional
    _lz4 = None

BLOCK = 2048


def _deflate_batch(blocks):
    out = []
    for b in blocks:
        c = zlib.compressobj(9, zlib.DEFLATED, -15, 9)
        z = c.compress(b) + c.flush()
        out.append(z if len(z) < len(b) else None)
    return out


def _lz4_batch(blocks):
    out = []
    for b in blocks:
        z = _lz4.compress(b, mode="high_compression", compression=12, store_size=False) if _lz4 else None
        out.append(z if z is not None and len(z) < len(b) else None)
    return out


def export(pxp_path, out_path, kind=None, original=None, store=None, disc=0, workers=None, log=None):
    """Writes an ISO/CSO/ZSO; returns {"sha256", "exact", "original", "size"}."""
    kind = (kind or os.path.splitext(out_path)[1].lstrip(".")).lower()
    with PxpReader(pxp_path, store=store) as r:
        d = r.disc(disc)
        if original is None:
            original = bool(d.flags & fmt.DF_STRIPPED) and r.has_original(disc)
        want = d.sha_original if (original or not d.flags & fmt.DF_STRIPPED) else d.sha_stored
        h = hashlib.sha256()
        tmp = out_path + ".part"
        if kind == "iso":
            with open(tmp, "wb") as o:
                for b in r.iter_image(disc, original):
                    h.update(b); o.write(b)
        elif kind in ("cso", "zso"):
            _compressed(r, disc, original, tmp, kind, h, workers)
        else:
            raise ValueError(f"unknown export format {kind!r} (iso, cso, zso)")
        exact = h.digest() == want
        if not exact:
            os.remove(tmp)
            raise IOError("exported image does not match the recorded disc hash")
        os.replace(tmp, out_path)
        return {"sha256": h.hexdigest(), "exact": exact, "original": bool(original or not d.flags & fmt.DF_STRIPPED),
                "size": os.path.getsize(out_path)}


def _compressed(r, disc, original, tmp, kind, h, workers):
    d = r.disc(disc)
    total = d.length
    nblocks = (total + BLOCK - 1) // BLOCK
    align = 0
    while (total >> align) >= 0x7FFFFFFF:        # offsets are 31-bit, shifted by align
        align += 1
    magic = b"CISO" if kind == "cso" else b"ZISO"
    header = struct.pack("<4sIQIBB2x", magic, 0x18, total, BLOCK, 1, align)
    fn = _deflate_batch if kind == "cso" else _lz4_batch
    index = [0] * (nblocks + 1)
    with open(tmp, "wb") as o:
        o.write(header)
        o.write(b"\0" * 4 * (nblocks + 1))
        pos = o.tell()
        BATCH = 512

        def batches():
            blk = 0
            for big in r.iter_image(disc, original, block=BATCH * BLOCK * 4):
                h.update(big)
                for i in range(0, len(big), BATCH * BLOCK):
                    part = big[i:i + BATCH * BLOCK]
                    yield [part[j:j + BLOCK].ljust(BLOCK, b"\0") for j in range(0, len(part), BLOCK)]

        with ProcessPoolExecutor(workers or os.cpu_count() or 4) as ex:
            blk = 0
            for res, raw in _zip_map(ex, fn, batches()):
                for z, b in zip(res, raw):
                    if pos & ((1 << align) - 1):
                        pad = (1 << align) - (pos & ((1 << align) - 1))
                        o.write(b"\0" * pad); pos += pad
                    if z is None:
                        index[blk] = (pos >> align) | 0x80000000
                        o.write(b); pos += len(b)
                    else:
                        index[blk] = pos >> align
                        o.write(z); pos += len(z)
                    blk += 1
        if pos & ((1 << align) - 1):
            pad = (1 << align) - (pos & ((1 << align) - 1))
            o.write(b"\0" * pad); pos += pad
        index[nblocks] = pos >> align
        o.seek(0x18)
        o.write(struct.pack(f"<{nblocks + 1}I", *index))


def _zip_map(ex, fn, gen, depth=64):
    """ex.map over a generator with bounded look-ahead, yielding (result, input)."""
    from collections import deque
    q = deque()
    for item in gen:
        q.append((ex.submit(fn, item), item))
        if len(q) >= depth:
            f, it = q.popleft()
            yield f.result(), it
    while q:
        f, it = q.popleft()
        yield f.result(), it


def read_compressed_image(path):
    """Decodes a CSO/ZSO back to bytes (used by tests to check round trips)."""
    with open(path, "rb") as f:
        magic, hsize, total, bsize, ver, align = struct.unpack("<4sIQIBB2x", f.read(24))
        n = (total + bsize - 1) // bsize
        index = struct.unpack(f"<{n + 1}I", f.read(4 * (n + 1)))
        h = hashlib.sha256()
        for i in range(n):
            a, b = index[i], index[i + 1]
            pa, pb = (a & 0x7FFFFFFF) << align, (b & 0x7FFFFFFF) << align
            f.seek(pa)
            data = f.read(pb - pa)
            if a & 0x80000000:
                blk = data[:bsize]
            elif magic == b"CISO":
                blk = zlib.decompressobj(-15).decompress(data)[:bsize]
            else:
                blk = None
                for trim in range(0, 1 << align):     # alignment padding may follow a block
                    try:
                        blk = _lz4.decompress(data[:len(data) - trim] if trim else data, uncompressed_size=bsize)
                        break
                    except Exception:
                        continue
                if blk is None:
                    raise IOError(f"ZSO block {i} is corrupt")
            h.update(blk[:min(bsize, total - i * bsize)])
        return h.hexdigest()
