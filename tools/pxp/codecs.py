"""
Chunk codecs for .pxp: raw, zstd, zstd with a trained dictionary, raw LZMA1, and zero.

Uses only the Python standard library (Python 3.14+: compression.zstd; lzma).

Per-file policy (chosen by content, not by file name):
    P_STORE  already-compressed media (ATRAC3/AT3/OMA, PMF/PSMF, PNG, JPEG, MPEG-PS, gzip/zip/7z, encrypted PRX):
             stored as-is, no trial compression, zero decode cost
    P_ZSTD   code, tables, raw data: zstd (with the library dictionary when one is loaded)
    P_BULK   large data that is rarely read: zstd, and LZMA when it wins by LZMA_MARGIN
    P_PINNED metadata files (PARAM.SFO, ICON0...): always raw, so the metadata section can point straight at them
Every chunk is also checked individually: one that does not shrink below RAW_MARGIN of its size is stored raw.
"""

import lzma
import struct

from compression import zstd

from . import fmt

P_STORE, P_ZSTD, P_BULK, P_PINNED = 0, 1, 2, 3
POLICY_NAMES = {P_STORE: "store", P_ZSTD: "zstd", P_BULK: "bulk", P_PINNED: "pinned-raw"}
RAW_MARGIN = 0.97      # keep a compressed chunk only if it is at most 97% of the raw size
LZMA_MARGIN = 0.97     # use LZMA only if it is at most 97% of the zstd size (LZMA decodes ~5x slower)
LZMA_LC, LZMA_LP, LZMA_PB = 3, 0, 2


def detect(head, size):
    """(policy, reason) for a file from its first bytes."""
    h = bytes(head[:64])
    if h[:4] == b"RIFF" and h[8:12] == b"WAVE":
        # fmt tag 0x0270 = ATRAC3, 0xFFFE = extensible (ATRAC3plus GUID); either way already compressed
        return P_STORE, "riff-atrac"
    if h[:3] in (b"EA3", b"ea3") or h[:4] == b"OMG\x00":
        return P_STORE, "oma"
    if h[:4] == b"PSMF":
        return P_STORE, "psmf"
    if h[:8] == b"\x89PNG\r\n\x1a\n":
        return P_STORE, "png"
    if h[:3] == b"\xff\xd8\xff":
        return P_STORE, "jpeg"
    if h[:4] == b"\x00\x00\x01\xba":
        return P_STORE, "mpeg-ps"
    if h[:2] == b"\x1f\x8b" or h[:6] == b"7z\xbc\xaf\x27\x1c" or h[:4] == b"PK\x03\x04" or h[:4] == b"\x28\xb5\x2f\xfd":
        return P_STORE, "compressed-archive"
    if h[:4] in (b"~PSP", b"~SCE"):
        return P_STORE, "encrypted-prx"
    if h[:4] == b"\x00PBP":
        return P_ZSTD, "pbp"            # mixed container: per-chunk trial decides
    return (P_BULK if size >= 1 << 20 else P_ZSTD), "data"


def zstd_options(level):
    return {zstd.CompressionParameter.compression_level: level,
            zstd.CompressionParameter.checksum_flag: 0,
            zstd.CompressionParameter.dict_id_flag: 0,
            zstd.CompressionParameter.content_size_flag: 1}


def lzma_filters(dict_size):
    return [{"id": lzma.FILTER_LZMA1, "preset": 9, "dict_size": dict_size,
             "lc": LZMA_LC, "lp": LZMA_LP, "pb": LZMA_PB}]


def lzma_props(dict_size):
    return bytes([(LZMA_PB * 5 + LZMA_LP) * 9 + LZMA_LC]) + struct.pack("<I", dict_size)


class Compressor:
    """Holds codec state for one worker process."""

    def __init__(self, level=19, dictionary=None, dict_index=0, chunk_size=65536, allow_lzma=True):
        self.level, self.chunk_size, self.allow_lzma = level, chunk_size, allow_lzma
        self.plain = zstd.ZstdCompressor(options=zstd_options(level))
        self.dict = zstd.ZstdDict(dictionary) if dictionary else None
        self.dict_index = dict_index
        self.with_dict = zstd.ZstdCompressor(options=zstd_options(level), zstd_dict=self.dict) if self.dict else None
        self.lzma_dict = max(4096, chunk_size)

    def compress(self, data, policy):
        """(codec, dict_index, payload) for one chunk."""
        if policy == P_PINNED:          # metadata files: raw even when zero, so their bytes stay contiguous
            return fmt.C_RAW, 0, bytes(data)
        if not any(data):
            return fmt.C_ZERO, 0, b""
        if policy == P_STORE:
            return fmt.C_RAW, 0, bytes(data)
        if self.with_dict:
            z = self.with_dict.compress(data, zstd.ZstdCompressor.FLUSH_FRAME)
            codec, di = fmt.C_ZSTD_DICT, self.dict_index
        else:
            z = self.plain.compress(data, zstd.ZstdCompressor.FLUSH_FRAME)
            codec, di = fmt.C_ZSTD, 0
        if len(z) > RAW_MARGIN * len(data):
            return fmt.C_RAW, 0, bytes(data)
        if policy == P_BULK and self.allow_lzma and len(data) >= 4096:
            l = lzma_props(self.lzma_dict) + lzma.compress(data, format=lzma.FORMAT_RAW, filters=lzma_filters(self.lzma_dict))
            if len(l) <= LZMA_MARGIN * len(z):
                return fmt.C_LZMA, 0, l
        return codec, di, z


class Decompressor:
    def __init__(self, dicts=()):
        self.dicts = [zstd.ZstdDict(content) for _, content in dicts]

    def decompress(self, codec, dict_index, payload, raw_size):
        if codec == fmt.C_RAW:
            out = bytes(payload)
        elif codec == fmt.C_ZERO:
            out = bytes(raw_size)
        elif codec == fmt.C_ZSTD:
            out = zstd.decompress(payload)
        elif codec == fmt.C_ZSTD_DICT:
            out = zstd.decompress(payload, zstd_dict=self.dicts[dict_index])
        elif codec == fmt.C_LZMA:
            props, dsize = payload[0], struct.unpack_from("<I", payload, 1)[0]
            lc, rest = props % 9, props // 9
            lp, pb = rest % 5, rest // 5
            d = lzma.LZMADecompressor(format=lzma.FORMAT_RAW, filters=[{"id": lzma.FILTER_LZMA1, "dict_size": dsize, "lc": lc, "lp": lp, "pb": pb}])
            out = d.decompress(bytes(payload[5:]), max_length=raw_size)
        else:
            raise ValueError(f"unknown chunk codec {codec}")
        if len(out) != raw_size:
            raise ValueError(f"chunk decoded to {len(out)} bytes, expected {raw_size}")
        return out
