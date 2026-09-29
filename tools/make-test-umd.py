"""Builds a tiny, original ISO 9660 UMD image for testing PXP2's disc path (no Sony content).

Layout: /UMD_DATA.BIN, /PSP_GAME/PARAM.SFO, /PSP_GAME/ICON0.PNG, /PSP_GAME/SYSDIR/EBOOT.BIN (placeholder).
Usage: python tools/make-test-umd.py out.iso [DISC_ID] [TITLE]   (defaults PXPT00001, "PXP2 Test Disc")
"""
import struct, sys, zlib, datetime

SECTOR = 2048

def sfo(entries):
    # PARAM.SFO: header, index table (16 bytes each), key table, data table. fmt 0x0204 utf-8 string, 0x0404 int32.
    keys = b''; data = b''; index = b''
    for key, value in entries:
        if isinstance(value, int):
            raw = struct.pack('<I', value); fmt, used, cap = 0x0404, 4, 4
        else:
            raw = value.encode() + b'\0'; used = len(raw); cap = (used + 3) & ~3; raw = raw.ljust(cap, b'\0'); fmt = 0x0204
        index += struct.pack('<HHIII', len(keys), fmt, used, cap, len(data))
        keys += key.encode() + b'\0'; data += raw
    keys = keys.ljust((len(keys) + 3) & ~3, b'\0')
    key_off = 20 + len(index); data_off = key_off + len(keys)
    return struct.pack('<4sIIII', b'\0PSF', 0x0101, key_off, data_off, len(entries)) + index + keys + data

def png(w, h):
    rows = b''
    for y in range(h):
        rows += b'\0' + b''.join(bytes((40 + x, 60 + y * 2, 200, 255)) for x in range(w))
    chunk = lambda t, d: struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')

def both16(v): return struct.pack('<H', v) + struct.pack('>H', v)
def both32(v): return struct.pack('<I', v) + struct.pack('>I', v)
NOW = datetime.datetime(2026, 9, 28, 12, 0, 0)
def rec_date(): return bytes((NOW.year - 1900, NOW.month, NOW.day, NOW.hour, NOW.minute, NOW.second, 0))
def vol_date(): return NOW.strftime('%Y%m%d%H%M%S00').encode() + b'\0'

def record(name, sector, size, directory):
    ident = name if isinstance(name, bytes) else name.encode()
    length = 33 + len(ident); length += length & 1
    r = struct.pack('<BB', length, 0) + both32(sector) + both32(size) + rec_date() + bytes((2 if directory else 0, 0, 0)) + both16(1) + bytes((len(ident),)) + ident
    return r.ljust(length, b'\0')

def build(path, disc_id='PXPT00001', title='PXP2 Test Disc'):
    sfo_data = sfo([('BOOTABLE', 1), ('CATEGORY', 'UG'), ('DISC_ID', disc_id), ('DISC_NUMBER', 1), ('DISC_TOTAL', 1),
                    ('DISC_VERSION', '1.00'), ('PARENTAL_LEVEL', 1), ('PSP_SYSTEM_VER', '1.00'), ('REGION', 0x8000), ('TITLE', title)])
    # UMD_DATA.BIN: the XMB reads 48 bytes, rejects shorter files and looks for the disc kind (G/V/A) from offset 33.
    files = {'UMD_DATA.BIN': (disc_id[:4] + '-' + disc_id[4:9] + '|0000000000000001|0001|G').encode().ljust(64, b'\0'), 'PARAM.SFO': sfo_data, 'ICON0.PNG': png(144, 80),
             'EBOOT.BIN': b'PXP2 placeholder: not an executable\n'}
    # Sector plan: 16 PVD, 17 terminator, 18 path table L, 19 path table M, 20 root, 21 PSP_GAME, 22 SYSDIR, then files.
    root_s, game_s, sys_s = 20, 21, 22
    next_s = 23; loc = {}
    for n, d in files.items(): loc[n] = next_s; next_s += (len(d) + SECTOR - 1) // SECTOR or 1
    total = next_s
    def dir_sector(me, parent, children):
        d = record(b'\0', me, SECTOR, True) + record(b'\1', parent, SECTOR, True)
        for c in children: d += c
        return d.ljust(SECTOR, b'\0')
    root = dir_sector(root_s, root_s, [record('PSP_GAME', game_s, SECTOR, True), record('UMD_DATA.BIN;1', loc['UMD_DATA.BIN'], len(files['UMD_DATA.BIN']), False)])
    game = dir_sector(game_s, root_s, [record('ICON0.PNG;1', loc['ICON0.PNG'], len(files['ICON0.PNG']), False),
                                       record('PARAM.SFO;1', loc['PARAM.SFO'], len(files['PARAM.SFO']), False), record('SYSDIR', sys_s, SECTOR, True)])
    sysd = dir_sector(sys_s, game_s, [record('EBOOT.BIN;1', loc['EBOOT.BIN'], len(files['EBOOT.BIN']), False)])
    def path_table(big):
        p32 = (lambda v: struct.pack('>I', v)) if big else (lambda v: struct.pack('<I', v))
        p16 = (lambda v: struct.pack('>H', v)) if big else (lambda v: struct.pack('<H', v))
        out = b''
        for name, sector, parent in ((b'\0', root_s, 1), (b'PSP_GAME', game_s, 1), (b'SYSDIR', sys_s, 2)):
            e = bytes((len(name), 0)) + p32(sector) + p16(parent) + name
            out += e + (b'\0' if len(name) & 1 else b'')
        return out
    pt = path_table(False)
    pvd = bytearray(SECTOR)
    pvd[0:8] = b'\x01CD001\x01\x00'
    pvd[8:40] = b'PSP GAME'.ljust(32); pvd[40:72] = b'PXP2_TEST_DISC'.ljust(32)
    pvd[80:88] = both32(total); pvd[120:124] = both16(1); pvd[124:128] = both16(1); pvd[128:132] = both16(SECTOR)
    pvd[132:140] = both32(len(pt)); pvd[140:144] = struct.pack('<I', 18); pvd[148:152] = struct.pack('>I', 19)
    pvd[156:190] = record(b'\0', root_s, SECTOR, True)
    for off in (813, 830, 847, 864): pvd[off:off + 17] = vol_date()
    pvd[881] = 1
    term = bytearray(SECTOR); term[0:7] = b'\xffCD001\x01'
    image = bytearray(total * SECTOR)
    image[16 * SECTOR:17 * SECTOR] = pvd; image[17 * SECTOR:18 * SECTOR] = term
    image[18 * SECTOR:18 * SECTOR + len(pt)] = pt; image[19 * SECTOR:19 * SECTOR + len(pt)] = path_table(True)
    image[root_s * SECTOR:(root_s + 1) * SECTOR] = root; image[game_s * SECTOR:(game_s + 1) * SECTOR] = game; image[sys_s * SECTOR:(sys_s + 1) * SECTOR] = sysd
    for n, d in files.items(): image[loc[n] * SECTOR:loc[n] * SECTOR + len(d)] = d
    open(path, 'wb').write(image)

if __name__ == '__main__':
    build(sys.argv[1] if len(sys.argv) > 1 else 'test-umd.iso', *sys.argv[2:4])
