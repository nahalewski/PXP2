"""
.pxp reader: random access to the disc image, metadata, verification and extraction.

    r = PxpReader("game.pxp")
    r.read(offset, n)            bytes of the image as the emulator sees it (stripped regions read as zeros)
    r.meta(b"ICN0")              ICON0.PNG without touching game data
    r.verify()                   every chunk hash + both whole-disc hashes
"""

import hashlib
import struct
from collections import OrderedDict

from compression import zstd

from . import codecs, fmt


class ChunkError(IOError):
    pass


class PxpReader:
    def __init__(self, path, store=None, cache_chunks=64):
        """store: a library Store to fetch chunks that are not in this file (manifests, stripped payloads)."""
        self.path, self.store = path, store
        self.f = open(path, "rb")
        self.header = fmt.Header.unpack(self.f.read(fmt.HEADER_SIZE))
        h = self.header
        self.f.seek(h.index_offset)
        packed = self.f.read(h.index_stored)
        index = zstd.decompress(packed) if h.index_codec == fmt.IDX_ZSTD else packed
        if len(index) != h.index_size or hashlib.sha256(index).digest() != h.index_sha256:
            raise ValueError("index hash mismatch")
        self.index_bytes = index
        self.ix = fmt.parse_index(index)
        self.f.seek(h.hash_offset)
        hashes = self.f.read(fmt.HASH_BYTES * h.chunk_count)
        for i, c in enumerate(self.ix.chunks):
            c.hash = hashes[i * fmt.HASH_BYTES:(i + 1) * fmt.HASH_BYTES]
        self.dec = codecs.Decompressor(self.ix.dicts)
        self.cache = OrderedDict()
        self.cache_chunks = cache_chunks
        self.chunk_size = h.chunk_size
        self.paths = fmt.file_paths(self.ix)
        self.stats = {"decoded": 0, "bytes_read": 0}

    # ------------------------------------------------------------------ chunks
    def payload(self, cid):
        c = self.ix.chunks[cid]
        if c.flags & fmt.CF_ABSENT:
            if self.store is None:
                return None
            got = self.store.payload(c.hash)
            if got is None:
                return None
            codec, dict_id, payload = got
            return codec, self._dict_index(dict_id, codec), payload
        self.f.seek(self.header.data_offset + c.offset)
        b = self.f.read(c.stored)
        self.stats["bytes_read"] += len(b)
        return c.codec, c.dict, b

    def _dict_index(self, dict_id, codec):
        if codec != fmt.C_ZSTD_DICT:
            return 0
        for i, (did, _) in enumerate(self.ix.dicts):
            if did == dict_id:
                return i
        content = self.store.dictionary(dict_id)
        self.ix.dicts.append((dict_id, content))
        self.dec = codecs.Decompressor(self.ix.dicts)
        return len(self.ix.dicts) - 1

    def chunk(self, cid, verify=True):
        """Uncompressed chunk data, or None when its payload is not available (stripped / external)."""
        hit = self.cache.get(cid)
        if hit is not None:
            self.cache.move_to_end(cid)
            return hit
        c = self.ix.chunks[cid]
        p = self.payload(cid)
        if p is None:
            return None
        codec, di, payload = p
        try:
            data = self.dec.decompress(codec, di, payload, c.raw)
        except Exception as e:
            raise ChunkError(f"chunk {cid} could not be decoded: {e}")
        if verify and fmt.h16(data) != c.hash:
            raise ChunkError(f"chunk {cid} hash mismatch (corrupt data)")
        self.stats["decoded"] += 1
        self.cache[cid] = data
        if len(self.cache) > self.cache_chunks:
            self.cache.popitem(last=False)
        return data

    # ------------------------------------------------------------------ image
    def disc(self, n=0):
        return self.ix.discs[n]

    def segments(self, disc=0):
        d = self.ix.discs[disc]
        return self.ix.segments[d.first_segment:d.first_segment + d.segment_count]

    def read(self, offset, n, disc=0, original=False):
        """Image bytes. original=True returns the stripped UPDATE payload too (needs it in the file or the store)."""
        d = self.ix.discs[disc]
        segs = self.segments(disc)
        end = min(offset + n, d.length)
        out = bytearray()
        # binary search for the first segment
        lo, hi = 0, len(segs) - 1
        ss = d.sector_size
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if segs[mid].sector * ss <= offset:
                lo = mid
            else:
                hi = mid - 1
        pos, i = offset, lo
        while pos < end:
            g = segs[i]
            gs, ge = g.sector * ss, min((g.sector + g.count) * ss, d.length)
            take = min(end, ge) - pos
            if g.stream == fmt.NONE32 or (g.flags & fmt.SF_STRIPPED and not original):
                out += bytes(take)
            else:
                st = self.ix.streams[g.stream]
                spos = g.stream_sector * ss + (pos - gs)
                left = take
                while left:
                    k, within = divmod(spos, self.chunk_size)
                    data = self.chunk(st.chunks[k])
                    if data is None:
                        raise ChunkError(f"image bytes at {pos} are not in this file"
                                         + (" (stripped UPDATE payload)" if g.flags & fmt.SF_STRIPPED else ""))
                    part = data[within:within + left]
                    out += part
                    left -= len(part); spos += len(part)
            pos += take
            i += 1
        return bytes(out)

    def iter_image(self, disc=0, original=False, block=1 << 20):
        d = self.ix.discs[disc]
        pos = 0
        while pos < d.length:
            n = min(block, d.length - pos)
            yield self.read(pos, n, disc, original)
            pos += n

    def has_original(self, disc=0):
        """True when the stripped payload can be rebuilt (kept in the file or available in the store)."""
        for g in self.segments(disc):
            if g.flags & fmt.SF_STRIPPED:
                for cid in self.ix.streams[g.stream].chunks:
                    c = self.ix.chunks[cid]
                    if c.flags & fmt.CF_ABSENT and (self.store is None or not self.store.has(c.hash)):
                        return False
        return True

    # ------------------------------------------------------------------ metadata / files
    def meta(self, kind, disc=0):
        if isinstance(kind, str):
            kind = kind.encode("ascii").ljust(4)
        for m in self.ix.meta:
            if m.kind == kind and m.disc == disc:
                self.f.seek(self.header.data_offset + m.offset)
                return self.f.read(m.length)
        return None

    def find(self, path, disc=0):
        want = "/" + path.strip("/").upper()
        for i, p in enumerate(self.paths):
            if p.upper() == want and self.ix.files[i].disc == disc:
                return i
        return None

    def read_file(self, index, original=False):
        f = self.ix.files[index]
        ss = self.ix.discs[f.disc].sector_size
        return self.read(f.lba * ss, f.size, f.disc, original)

    # ------------------------------------------------------------------ integrity
    def verify(self, log=None, original=None):
        """Checks every present chunk and both whole-disc hashes. Returns a dict of results; raises nothing."""
        res = {"chunks_ok": 0, "chunks_bad": [], "chunks_absent": 0, "discs": []}
        for cid, c in enumerate(self.ix.chunks):
            try:
                data = self.chunk(cid, verify=False)
                if data is None:
                    res["chunks_absent"] += 1
                elif fmt.h16(data) == c.hash:
                    res["chunks_ok"] += 1
                else:
                    res["chunks_bad"].append(cid)
            except Exception:
                res["chunks_bad"].append(cid)
        self.cache.clear()
        for dn, d in enumerate(self.ix.discs):
            r = {"disc": dn}
            try:
                h = hashlib.sha256()
                for b in self.iter_image(dn, False):
                    h.update(b)
                r["stored_ok"] = h.digest() == d.sha_stored
            except Exception as e:
                r["stored_ok"], r["stored_error"] = False, str(e)
            want_orig = self.has_original(dn) if original is None else original
            if want_orig and d.flags & fmt.DF_STRIPPED:
                try:
                    h = hashlib.sha256()
                    for b in self.iter_image(dn, True):
                        h.update(b)
                    r["original_ok"] = h.digest() == d.sha_original
                except Exception as e:
                    r["original_ok"], r["original_error"] = False, str(e)
            elif not d.flags & fmt.DF_STRIPPED:
                r["original_ok"] = r["stored_ok"]
            else:
                r["original_ok"] = None     # stripped and the payload is not available: flagged, not checkable
            res["discs"].append(r)
        res["ok"] = not res["chunks_bad"] and all(r["stored_ok"] and r["original_ok"] is not False for r in res["discs"])
        return res

    def close(self):
        self.f.close()

    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.close()
