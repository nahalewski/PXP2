"""
.pxp encoder: ISO (or raw disc image) -> .pxp container.

    encode(["game.iso"], "game.pxp", dictionary=dict_bytes, trace="boot.trace")

Passes:
  1. layout   parse ISO 9660, cut the image into file / directory / system / zero regions (iso.partition)
  2. hash     one sequential read: SHA-256 of the original and the stripped image, SHA-256 of every chunk
  3. place    order chunks for streaming (metadata, file system, boot trace, boot heuristics, rest) and deduplicate
  4. compress unique chunks in worker processes (per-file policy, per-chunk codec choice)
  5. write    header, index, hash table, data area
"""

import hashlib
import os
import struct
import sys
import tempfile
import time
from concurrent.futures import ProcessPoolExecutor

from . import codecs, fmt
from .iso import SECTOR, IsoImage

ENCODER = "pxp-tools 1.0"
UPDATE_DIR = "/PSP_GAME/SYSDIR/UPDATE/"


def parse_sfo(b):
    if len(b) < 20 or b[:4] != b"\0PSF":
        return {}
    kt, dt, n = struct.unpack_from("<III", b, 8)
    out = {}
    for i in range(min(n, 256)):
        ko, f, used, mx, do = struct.unpack_from("<HHIII", b, 20 + 16 * i)
        end = b.find(b"\0", kt + ko)
        k = b[kt + ko:end].decode("ascii", "replace")
        v = b[dt + do:dt + do + used]
        out[k] = struct.unpack("<I", v[:4])[0] if f == 0x0404 and len(v) >= 4 else v.split(b"\0")[0].decode("utf-8", "replace")
    return out


def region_of(disc_id):
    p = (disc_id or "")[:4].upper()
    if len(p) < 4:
        return ""
    return {"U": "US", "E": "EU", "J": "JP", "A": "ASIA", "K": "KR", "H": "ASIA", "C": "CN"}.get(p[2], "")


def is_stripped_path(path):
    """UPDATE payload: every file in PSP_GAME/SYSDIR/UPDATE except PARAM.SFO (vshmain stats it)."""
    up = path.upper()
    return up.startswith(UPDATE_DIR) and up != UPDATE_DIR + "PARAM.SFO"


def read_trace(path):
    """Boot trace written by PxpSource (-Dpxp.trace=file): lines "disc offset length", in first-access order."""
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            p = line.split()
            if len(p) >= 3 and p[0].isdigit():
                out.append((int(p[0]), int(p[1]), int(p[2])))
    return out


# ------------------------------------------------------------------------------------------------ worker processes

_W = {}


def _winit(level, dictionary, chunk_size, allow_lzma):
    _W["c"] = codecs.Compressor(level, dictionary, 0, chunk_size, allow_lzma)
    _W["files"] = {}


def _wjob(batch):
    out = []
    for cid, path, offset, length, policy in batch:
        f = _W["files"].get(path)
        if f is None:
            f = _W["files"][path] = open(path, "rb")
        f.seek(offset)
        data = f.read(length)
        data += b"\0" * (length - len(data))
        codec, di, payload = _W["c"].compress(data, policy)
        out.append((cid, codec, di, payload))
    return out


# ------------------------------------------------------------------------------------------------------- encoder

class _Disc:
    """Working state for one input image."""

    def __init__(self, number, path, sector_size):
        self.number, self.path = number, path
        self.iso = None
        try:
            if sector_size == SECTOR:
                self.iso = IsoImage(path)
        except ValueError:
            self.iso = None
        self.sector = SECTOR if self.iso else sector_size
        self.length = os.path.getsize(path)
        self.sfo = {}


def encode(inputs, out_path, *, chunk_size=65536, level=19, dictionary=None, allow_lzma=True, strip=True,
           keep_stripped=False, trace=None, art=None, settings=None, info=None, workers=None, sector_size=SECTOR,
           log=print):
    t0 = time.time()
    if chunk_size % 2048 or chunk_size & (chunk_size - 1) or not 2048 <= chunk_size <= 1 << 20:
        raise ValueError("chunk size must be a power of two between 2 KiB and 1 MiB")
    ix = fmt.Index()
    dict_id = None
    if dictionary:
        from compression import zstd
        dict_id = zstd.ZstdDict(dictionary).dict_id
        ix.dicts.append((dict_id, bytes(dictionary)))
    streams = []        # (fmt.Stream, source path, source offset, policy, meta_kind)
    stream_hash = []    # per stream: list of full chunk digests
    discs = []
    any_stripped = False
    for dn, path in enumerate(inputs):
        d = _Disc(dn, path, sector_size)
        discs.append(d)
        disc = fmt.Disc(d.sector, d.length, fmt.DK_ISO9660 if d.iso else fmt.DK_RAW)
        disc.first_segment, disc.first_file = len(ix.segments), len(ix.files)
        stripped_files = set()
        meta_file = {}
        policy_of = {}
        if d.iso:
            paths = [f.path for f in d.iso.files]
            base = len(ix.files)
            for i, f in enumerate(d.iso.files):
                flags = (fmt.FF_DIR if f.is_dir else 0) | (fmt.FF_MULTI_EXTENT if len(f.extents) > 1 else 0)
                if strip and not f.is_dir and f.size and is_stripped_path(f.path):
                    flags |= fmt.FF_STRIPPED
                    stripped_files.add(i)
                ix.files.append(fmt.FileEntry(dn, fmt.NONE32 if f.parent < 0 else base + f.parent, f.name, flags, f.lba, f.size))
            for kind, mpath in fmt.META_FROM_IMAGE:
                i = d.iso.find(mpath)
                if i is not None and d.iso.files[i].size and len(d.iso.files[i].extents) == 1:
                    meta_file[i] = kind
                    ix.files[base + i].flags |= fmt.FF_META
            i = d.iso.find("PSP_GAME/PARAM.SFO")
            if i is not None and d.iso.files[i].size < 65536:
                f = d.iso.files[i]
                d.sfo = parse_sfo(d.iso.read_bytes(f.lba * SECTOR, f.size))
            regions = d.iso.partition()
            for kind, first, count, i in regions:
                if kind == "file":
                    f = d.iso.files[i]
                    if i in meta_file:
                        policy_of[i] = codecs.P_PINNED
                    else:
                        head = d.iso.read_bytes(first * SECTOR, 64)
                        pol, _ = codecs.detect(head, f.size)
                        up = f.path.upper()
                        if pol == codecs.P_BULK and (up.startswith("/PSP_GAME/SYSDIR/") or up.endswith(".PRX")):
                            pol = codecs.P_ZSTD
                        policy_of[i] = pol
            disc.file_count = len(d.iso.files)
        else:
            regions = [("system", 0, (d.length + d.sector - 1) // d.sector, None)]
        for kind, first, count, i in regions:
            if kind == "zero":
                ix.segments.append(fmt.Segment(dn, first, count, fmt.NONE32, 0, 0))
                continue
            nbytes = min(count * d.sector, d.length - first * d.sector)
            role = {"file": fmt.R_FILE, "dir": fmt.R_DIRECTORY, "system": fmt.R_SYSTEM}[kind]
            sflags = 0
            if kind == "file" and i in stripped_files:
                role, sflags = fmt.R_STRIPPED, fmt.SF_STRIPPED
                any_stripped = True
            pol = policy_of.get(i, codecs.P_ZSTD) if kind == "file" else codecs.P_ZSTD
            st = fmt.Stream(nbytes, [], role, dn)
            ix.segments.append(fmt.Segment(dn, first, count, len(streams), 0, sflags))
            streams.append([st, path, first * d.sector, pol, meta_file.get(i) if kind == "file" else None, i])
        if any_stripped:
            disc.flags |= fmt.DF_STRIPPED
        disc.segment_count = len(ix.segments) - disc.first_segment
        ix.discs.append(disc)

    # ---- pass 2: sequential hashing
    t_hash = time.time()
    nstreams = len(streams)
    stream_hash = [None] * nstreams
    stripped_sha = {}
    for dn, d in enumerate(discs):
        disc = ix.discs[dn]
        h_orig, h_stored = hashlib.sha256(), hashlib.sha256()
        with open(d.path, "rb", buffering=0) as f:
            pos = 0
            for g in ix.segments[disc.first_segment:disc.first_segment + disc.segment_count]:
                start = g.sector * d.sector
                end = min((g.sector + g.count) * d.sector, d.length)
                assert start == pos, (start, pos)
                if g.stream == fmt.NONE32:
                    z = bytes(min(end - start, 1 << 20))
                    left = end - start
                    while left:
                        n = min(left, len(z))
                        h_orig.update(z[:n]); h_stored.update(z[:n]); left -= n
                    pos = end
                    continue
                st = streams[g.stream][0]
                f.seek(start)
                digests = []
                fh = hashlib.sha256() if g.flags & fmt.SF_STRIPPED else None
                fsize = None
                if fh is not None:
                    fsize = ix.files[disc.first_file + streams[g.stream][5]].size
                done = 0
                while done < st.length:
                    n = min(chunk_size, st.length - done)
                    data = f.read(n)
                    if len(data) < n:
                        data += bytes(n - len(data))
                    digests.append(hashlib.sha256(data).digest())
                    h_orig.update(data)
                    h_stored.update(bytes(n) if fh is not None else data)
                    if fh is not None:
                        take = max(0, min(n, fsize - done))
                        fh.update(data[:take])
                    done += n
                stream_hash[g.stream] = digests
                if fh is not None:
                    stripped_sha[g.stream] = (fh.digest(), fsize)
                pos = end
        disc.sha_original, disc.sha_stored = h_orig.digest(), h_stored.digest()
    t_hash = time.time() - t_hash

    # ---- pass 3: placement and deduplication
    placed = []          # (stream, k)
    seen = set()

    def place(s, k=None):
        ks = range(len(stream_hash[s])) if k is None else [k]
        for kk in ks:
            if (s, kk) not in seen:
                seen.add((s, kk)); placed.append((s, kk))

    meta_streams = sorted((s for s in range(nstreams) if streams[s][4] is not None),
                          key=lambda s: [k for k, _ in fmt.META_FROM_IMAGE].index(streams[s][4]))
    for s in meta_streams:
        place(s)
    for s in range(nstreams):
        if streams[s][0].role in (fmt.R_SYSTEM, fmt.R_DIRECTORY) and stream_hash[s] and streams[s][0].length <= 4 << 20:
            place(s)
    boot_rank = {}
    if trace:
        for dn, off, length in read_trace(trace) if isinstance(trace, str) else trace:
            if dn >= len(ix.discs):
                continue
            disc = ix.discs[dn]
            segs = ix.segments[disc.first_segment:disc.first_segment + disc.segment_count]
            a, b = off, off + max(length, 1)
            for g in segs:
                gs, ge = g.sector * disc.sector_size, (g.sector + g.count) * disc.sector_size
                if ge <= a or gs >= b or g.stream == fmt.NONE32:
                    continue
                s = g.stream
                if streams[s][0].role == fmt.R_STRIPPED:
                    continue
                lo, hi = max(a, gs) - gs, min(b, ge) - gs
                for k in range(lo // chunk_size, (hi - 1) // chunk_size + 1):
                    if k < len(stream_hash[s]):
                        place(s, k)
                fi = streams[s][5]
                if fi is not None:
                    boot_rank.setdefault(disc.first_file + fi, len(boot_rank))
    # heuristic boot set: SYSDIR executables, then modules
    heur = []
    paths_all = fmt.file_paths(ix)
    for s in range(nstreams):
        fi = streams[s][5]
        if streams[s][0].role != fmt.R_FILE or fi is None:
            continue
        p = paths_all[ix.discs[streams[s][0].disc].first_file + fi].upper()
        if p.startswith("/PSP_GAME/SYSDIR/") and ("BOOT.BIN" in p or "EBOOT.BIN" in p):
            heur.append((0, s))
        elif p.startswith("/PSP_GAME/USRDIR/MODULE") or p.endswith(".PRX"):
            heur.append((1, s))
    for _, s in sorted(heur):
        place(s)
    for s in range(nstreams):
        if streams[s][0].role != fmt.R_STRIPPED:
            place(s)
    for s in range(nstreams):
        place(s)

    by_hash = {}
    jobs = []            # (cid, path, offset, length, policy)
    meta_set = set(meta_streams)
    for s, k in placed:
        st, path, soff, pol, _, _ = streams[s]
        digest = stream_hash[s][k]
        n = min(chunk_size, st.length - k * chunk_size)
        cid = None if s in meta_set else by_hash.get(digest)
        if cid is None:
            cid = len(ix.chunks)
            absent = st.role == fmt.R_STRIPPED and not keep_stripped
            ix.chunks.append(fmt.Chunk(0, n, fmt.C_RAW, 0, fmt.CF_ABSENT if absent else 0, digest[:fmt.HASH_BYTES]))
            if s not in meta_set:
                by_hash[digest] = cid
            if not absent:
                jobs.append((cid, path, soff + k * chunk_size, n, pol))
        while len(st.chunks) <= k:
            st.chunks.append(None)
        st.chunks[k] = cid
    for rec in streams:
        assert None not in rec[0].chunks
        ix.streams.append(rec[0])
    for s, (sha, size) in stripped_sha.items():
        fi = streams[s][5]
        ix.stripped.append(fmt.Stripped(ix.discs[streams[s][0].disc].first_file + fi, s, size, sha))
    for f_index, rank in boot_rank.items():
        ix.files[f_index].boot_rank = rank
    ix.order = [f for f, _ in sorted(boot_rank.items(), key=lambda x: x[1])]

    # ---- pass 4: compression
    t_comp = time.time()
    batches, cur = [], []
    for j in jobs:
        cur.append(j)
        if len(cur) >= 16:
            batches.append(cur); cur = []
    if cur:
        batches.append(cur)
    tmp = tempfile.NamedTemporaryFile(prefix="pxp-data-", suffix=".tmp", dir=os.path.dirname(os.path.abspath(out_path)), delete=False)
    stats = {"chunks": len(ix.chunks), "unique_present": len(jobs), "by_codec": {}}
    try:
        workers = workers or os.cpu_count() or 4
        done = 0
        data_size = 0
        with ProcessPoolExecutor(workers, initializer=_winit, initargs=(level, dictionary, chunk_size, allow_lzma)) as ex:
            for res in ex.map(_wjob, batches, chunksize=1):
                for cid, codec, di, payload in res:
                    c = ix.chunks[cid]
                    c.codec, c.dict, c.stored = codec, di, len(payload)
                    tmp.write(payload)
                    data_size += len(payload)
                    bc = stats["by_codec"].setdefault(fmt.CODEC_NAMES[codec], [0, 0, 0])
                    bc[0] += 1; bc[1] += c.raw; bc[2] += len(payload)
                done += len(res)
                if log and done % 4096 < len(res):
                    log(f"  compressed {done}/{len(jobs)} chunks")
        # metadata items in the image: their chunks were placed first, raw and contiguous
        off = 0
        offsets = {}
        for cid, c in enumerate(ix.chunks):
            if not c.flags & fmt.CF_ABSENT:
                offsets[cid] = off; off += c.stored
        for s in meta_streams:
            st = streams[s][0]
            fi = ix.discs[st.disc].first_file + streams[s][5]
            ids = st.chunks
            assert all(ix.chunks[i].codec == fmt.C_RAW for i in ids) and ids == list(range(ids[0], ids[0] + len(ids)))
            ix.meta.append(fmt.MetaItem(streams[s][4], st.disc, offsets[ids[0]], ix.files[fi].size, fmt.MF_IN_IMAGE))
        for kind, blob in ((fmt.META_BOX, (art or {}).get("box")), (fmt.META_DISC_ART, (art or {}).get("disc"))):
            if blob:
                ix.meta.append(fmt.MetaItem(kind, 0, data_size, len(blob), 0))
                tmp.write(blob); data_size += len(blob)
        tmp.close()
        t_comp = time.time() - t_comp

        # ---- pass 5: index and file
        d0 = discs[0]
        sfo = d0.sfo
        ix.info.update({
            "encoder": ENCODER, "created": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "source": os.path.basename(inputs[0]), "chunk_size": chunk_size,
            "disc_id": sfo.get("DISC_ID", ""), "title": sfo.get("TITLE", ""), "region": region_of(sfo.get("DISC_ID", "")),
            "category": sfo.get("CATEGORY", ""), "disc_version": sfo.get("DISC_VERSION", ""),
            "system_version": sfo.get("PSP_SYSTEM_VER", ""), "parental_level": sfo.get("PARENTAL_LEVEL", ""),
            "volume_id": d0.iso.volume_id if d0.iso else "", "discs": len(discs),
            "stripped": "UPDATE" if any_stripped else "", "stripped_kept": "1" if keep_stripped and any_stripped else "0",
        })
        if info:
            ix.info.update(info)
        if settings:
            ix.settings.update(settings)
        write_container(out_path, ix, chunk_size, tmp.name, data_size,
                        flags=(fmt.HF_STRIPPED if any_stripped and not keep_stripped else 0))
    finally:
        try:
            tmp.close()
        except Exception:
            pass
        if os.path.exists(tmp.name):
            os.remove(tmp.name)
    for d in discs:
        if d.iso:
            d.iso.close()
    stats.update({"seconds": time.time() - t0, "hash_seconds": t_hash, "compress_seconds": t_comp,
                  "size": os.path.getsize(out_path), "iso_size": sum(d.length for d in discs),
                  "disc_id": ix.info.get("disc_id"), "title": ix.info.get("title")})
    return stats


def write_container(out_path, ix, chunk_size, data_path, data_size, flags=0, level=19):
    """Writes header + index + hash table + data area (copied from data_path, or b"" when None)."""
    from compression import zstd
    if ix.layers:
        flags |= fmt.HF_OVERLAYS
    index = fmt.build_index(ix)
    packed = zstd.compress(index, options=codecs.zstd_options(level))
    hashes = b"".join(c.hash for c in ix.chunks)
    assert len(hashes) == fmt.HASH_BYTES * len(ix.chunks)
    hash_offset = fmt.HEADER_SIZE + len(packed)
    data_offset = fmt.align(hash_offset + len(hashes), fmt.DATA_ALIGN)
    h = fmt.Header(flags, chunk_size, fmt.HEADER_SIZE, len(packed), len(index), fmt.IDX_ZSTD, len(ix.chunks),
                   hash_offset, data_offset, data_size, hashlib.sha256(index).digest())
    tmp_out = out_path + ".part"
    with open(tmp_out, "wb") as o:
        o.write(h.pack()); o.write(packed); o.write(hashes)
        o.write(b"\0" * (data_offset - o.tell()))
        if data_path:
            with open(data_path, "rb") as src:
                while True:
                    b = src.read(8 << 20)
                    if not b:
                        break
                    o.write(b)
        assert o.tell() == data_offset + data_size, (o.tell(), data_offset, data_size)
    os.replace(tmp_out, out_path)
