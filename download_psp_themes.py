import os
import sys
import io
import time
import zipfile
import subprocess
import urllib.request
import urllib.parse

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

BASE_DIR = r"c:\Users\Ben\Desktop\pxp2\themes"
OFFICIAL_DIR = os.path.join(BASE_DIR, "official")
CTF_DIR = os.path.join(BASE_DIR, "ctf")
CACHE_DIR = os.path.join(BASE_DIR, "_cache")

os.makedirs(OFFICIAL_DIR, exist_ok=True)
os.makedirs(CTF_DIR, exist_ok=True)
os.makedirs(CACHE_DIR, exist_ok=True)

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

class HTTPRangeFile(io.RawIOBase):
    def __init__(self, url, headers=None):
        self.url = url
        self.headers = headers or {'User-Agent': USER_AGENT}
        self.pos = 0
        req = urllib.request.Request(url, headers={**self.headers, 'Range': 'bytes=0-0'})
        with urllib.request.urlopen(req, timeout=30) as resp:
            cr = resp.headers.get('Content-Range')
            self.length = int(cr.split('/')[-1])

    def seek(self, offset, whence=io.SEEK_SET):
        if whence == io.SEEK_SET:
            self.pos = offset
        elif whence == io.SEEK_CUR:
            self.pos += offset
        elif whence == io.SEEK_END:
            self.pos = self.length + offset
        else:
            raise ValueError("Invalid whence")
        return self.pos

    def tell(self):
        return self.pos

    def seekable(self):
        return True

    def readable(self):
        return True

    def read(self, size=-1):
        if size < 0 or self.pos + size > self.length:
            size = self.length - self.pos
        if size == 0:
            return b''
        end = self.pos + size - 1
        req = urllib.request.Request(self.url, headers={**self.headers, 'Range': f'bytes={self.pos}-{end}'})
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read()
        self.pos += len(data)
        return data

def validate_theme(data: bytes, min_size: int = 10240) -> bool:
    if len(data) < min_size:
        return False
    # Check for HTML errors
    header_start = data[:100].lower()
    if b'<!doctype' in header_start or b'<html' in header_start or b'error 404' in header_start:
        return False
    # Check magic header: standard Sony PTF/CTF has b'\x00PTF' at offset 0
    if data[:4] == b'\x00PTF' or b'PTF' in data[:16]:
        return True
    # Some custom CTF may begin with standard container header
    return True

def save_theme(dest_path: str, data: bytes, desc: str):
    if not validate_theme(data):
        print(f"[ERROR] Validation failed for {desc} ({len(data)} bytes). Skipping.")
        return False
    with open(dest_path, 'wb') as f:
        f.write(data)
    print(f"[OK] Saved {desc} -> {os.path.basename(dest_path)} ({len(data) / 1024:.1f} KB)")
    return True

def download_direct(url: str, dest_path: str, desc: str):
    try:
        req = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read()
        return save_theme(dest_path, data, desc)
    except Exception as e:
        print(f"[FAIL] Download {desc} from {url}: {e}")
        return False

print("=" * 60)
print("1. Extracting Daxter Official Theme from cached 7z")
print("=" * 60)
daxter_7z = os.path.join(CACHE_DIR, "daxter.7z")
if os.path.exists(daxter_7z):
    # Extract Daxter.ptf using 7z
    res = subprocess.run([r'C:\Program Files\7-Zip\7z.exe', 'e', daxter_7z, 'daxter_ptf_3.71\\Daxter PTF 3.71\\Daxter.ptf', f'-o{CACHE_DIR}', '-y'], capture_output=True)
    extracted_daxter = os.path.join(CACHE_DIR, "Daxter.ptf")
    if os.path.exists(extracted_daxter):
        with open(extracted_daxter, 'rb') as f:
            save_theme(os.path.join(OFFICIAL_DIR, "Daxter_Official.ptf"), f.read(), "Daxter Official")

print("\n" + "=" * 60)
print("2. Downloading & Extracting from Archive.org collection-of-psp-themes")
print("=" * 60)
collection_items = [
    ("Patapon%C2%AE%202%20PSP%C2%AE%20Theme.ptf", "Patapon_2_Official.ptf", "Patapon 2 Official PSP Theme"),
    ("Space%20Invaders%20Extreme%20%28ULUS-10346%29.ptf", "Space_Invaders_Extreme.ptf", "Space Invaders Extreme Official"),
    ("Mainichi%20Issho.ptf", "Mainichi_Issho_Toro.ptf", "Mainichi Issho (Toro Mascot) Official"),
    ("PlayStation%C2%AE%2015th%20Anniversary%20Official%20PSP%C2%AE%20Theme.ptf", "PlayStation_15th_Anniversary.ptf", "PlayStation 15th Anniversary Official"),
]
for remote_fn, local_fn, desc in collection_items:
    url = f"https://archive.org/download/collection-of-psp-themes/{remote_fn}"
    download_direct(url, os.path.join(OFFICIAL_DIR, local_fn), desc)
    time.sleep(0.5)

print("\n" + "=" * 60)
print("3. Downloading Patapon & Metal Gear Solid 7z archives from 1425 Theme Pack")
print("=" * 60)
archive_7z_list = [
    ("FW%203.7X%2Fpatapon_ptf_by_ferretboy.7z", "patapon_ferretboy.7z", "Patapon Special Edition", "patapon.ptf", os.path.join(OFFICIAL_DIR, "Patapon_Special.ptf")),
    ("FW%203.8X%20-%205.01%2Fmetal_gear_solid_portable_ops_ptf_380.7z", "mgs_ops.7z", "Metal Gear Solid Portable Ops", "metal_gear_solid_portable_ops.ptf", os.path.join(OFFICIAL_DIR, "Metal_Gear_Solid_Portable_Ops.ptf")),
    ("FW%203.7X%2Fmonster_hunter.7z", "mh_hunter.7z", "Monster Hunter Freedom", "monster_hunter.ptf", os.path.join(OFFICIAL_DIR, "Monster_Hunter_Freedom.ptf")),
    ("FW%203.8X%20-%205.01%2Fpersona_4_ptf.7z", "p4.7z", "Persona 4 Official Style", "persona 4.ptf", os.path.join(OFFICIAL_DIR, "Persona_4.ptf")),
]
for subpath, cached_name, desc, inner_target, final_dest in archive_7z_list:
    url = f"https://archive.org/download/psp-themes-pack/PTF_Themes_Pack_by_PSPSOURCE%2F1425PTFThemePackbyPSPSource%2F{subpath}"
    cached_file = os.path.join(CACHE_DIR, cached_name)
    try:
        req = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
        with urllib.request.urlopen(req, timeout=30) as resp, open(cached_file, 'wb') as out:
            out.write(resp.read())
        # extract with 7z
        res = subprocess.run([r'C:\Program Files\7-Zip\7z.exe', 'e', cached_file, f'-o{CACHE_DIR}', '-y'], capture_output=True, text=True)
        # find matching ptf
        extracted = None
        for fn in os.listdir(CACHE_DIR):
            if fn.lower() == inner_target.lower():
                extracted = os.path.join(CACHE_DIR, fn)
                break
        if not extracted:
            for fn in os.listdir(CACHE_DIR):
                if fn.endswith('.ptf') and fn not in ['Daxter.ptf']:
                    extracted = os.path.join(CACHE_DIR, fn)
                    break
        if extracted and os.path.exists(extracted):
            with open(extracted, 'rb') as f:
                save_theme(final_dest, f.read(), desc)
            os.remove(extracted)
    except Exception as e:
        print(f"[FAIL] 7z processing for {desc}: {e}")
    time.sleep(0.5)

print("\n" + "=" * 60)
print("4. Extracting from Remote Zip: psp.hm7.moe Themes.zip via Range Requests")
print("=" * 60)
moe_zip_url = 'https://archive.org/download/psp-themes-pack/psp.hm7.moe%20Themes.zip'
try:
    print("Connecting to psp.hm7.moe Themes.zip...")
    moe_range = HTTPRangeFile(moe_zip_url)
    moe_zf = zipfile.ZipFile(moe_range)
    print("Zip directory loaded successfully!")

    moe_extracts = [
        # Official PTF themes
        ("PTF/EP9000-NPEW00072_00-TPCGODOFWA000001.PTF", os.path.join(OFFICIAL_DIR, "God_of_War_Official_PSN.ptf"), "God of War PSN Official Theme"),
        ("PTF/EP9000-NPEW00047_00-LBPPSPTHEME00001.PTF", os.path.join(OFFICIAL_DIR, "LittleBigPlanet_Official.ptf"), "LittleBigPlanet PSN Official Theme"),
        ("PTF/EP9000-NPEW00079_00-TPCRESISTA000001.PTF", os.path.join(OFFICIAL_DIR, "Resistance_Retribution_Official.ptf"), "Resistance Retribution PSN Official Theme"),
        ("PTF/EP9001-NPEW00051_00-TPCGRANTUR000001.PTF", os.path.join(OFFICIAL_DIR, "Gran_Turismo_Official.ptf"), "Gran Turismo PSN Official Theme"),
        ("PTF/HP9000-NPHW00012_00-THMTLOCOROCOCHN5.PTF", os.path.join(OFFICIAL_DIR, "LocoRoco_Official.ptf"), "LocoRoco PSN Official Theme"),
        ("PTF/UT0004-NPUY00005_00-HDPULSEPSPTHEME1.PTF", os.path.join(OFFICIAL_DIR, "Wipeout_Pulse_HD_1.ptf"), "Wipeout Pulse HD Official Theme 1"),
        ("PTF/UT0004-NPUY00005_00-HDPULSEPSPTHEME2.PTF", os.path.join(OFFICIAL_DIR, "Wipeout_Pulse_HD_2.ptf"), "Wipeout Pulse HD Official Theme 2"),
        ("PTF/ps20th3.ptf", os.path.join(OFFICIAL_DIR, "PlayStation_20th_Anniversary.ptf"), "PlayStation 20th Anniversary Official Theme"),
        ("PTF/wipEout.ptf", os.path.join(OFFICIAL_DIR, "Wipeout_Classic.ptf"), "Wipeout Classic Theme"),
        ("PTF/Killzone3.ptf", os.path.join(OFFICIAL_DIR, "Killzone_3.ptf"), "Killzone 3 Official Theme"),
        ("PTF/Ace Combat Zero.ptf", os.path.join(OFFICIAL_DIR, "Ace_Combat_Zero.ptf"), "Ace Combat Zero Theme"),
        ("PTF/Armored Core 3 portable.ptf", os.path.join(OFFICIAL_DIR, "Armored_Core_3_Portable.ptf"), "Armored Core 3 Portable Theme"),
        ("PTF/Armored Core Last Raven.ptf", os.path.join(OFFICIAL_DIR, "Armored_Core_Last_Raven.ptf"), "Armored Core Last Raven Theme"),
        ("PTF/Neon.PTF", os.path.join(OFFICIAL_DIR, "Neon_Official.ptf"), "Neon Sony Official Theme"),
        ("PTF/Ferrari California.ptf", os.path.join(OFFICIAL_DIR, "Ferrari_California.ptf"), "Ferrari California Theme"),
        ("PTF/Star Wars Episode 3.ptf", os.path.join(OFFICIAL_DIR, "Star_Wars_Episode_III.ptf"), "Star Wars Episode III Theme"),
        ("PTF/Dragonball Z 1.PTF", os.path.join(OFFICIAL_DIR, "Dragon_Ball_Z.ptf"), "Dragon Ball Z Theme"),
        ("PTF/UP9000-UCUS98741_00-MNRACERSPSPTHM02.PTF", os.path.join(OFFICIAL_DIR, "ModNation_Racers_Official.ptf"), "ModNation Racers PSN Official Theme"),
        ("PTF/EP0006-NPEW00073_00-TPCFIFA11P000001.PTF", os.path.join(OFFICIAL_DIR, "FIFA_11_Official.ptf"), "FIFA 11 Official Theme"),
    ]
    for inner_path, dest_path, desc in moe_extracts:
        try:
            data = moe_zf.read(inner_path)
            save_theme(dest_path, data, desc)
        except Exception as e:
            print(f"[FAIL] Extracting {inner_path} ({desc}): {e}")
        time.sleep(0.2)
except Exception as e:
    print(f"[FAIL] Opening psp.hm7.moe Themes.zip: {e}")

print("\n" + "=" * 60)
print("5. Extracting from Remote Zip: pspthemes-neko.zip & themes_discord_mix.zip & kippykip.zip")
print("=" * 60)
for zip_name, items in [
    ('pspthemes-neko.zip', [
        ("pspthemes-neko/GodofWarCOO_Theme-PSP.ptf", os.path.join(OFFICIAL_DIR, "God_of_War_Chains_of_Olympus.ptf"), "God of War: Chains of Olympus Official"),
        ("pspthemes-neko/MGS32.ptf", os.path.join(OFFICIAL_DIR, "Metal_Gear_Solid_32.ptf"), "Metal Gear Solid 32 Theme"),
        ("pspthemes-neko/PursuitForce1.ptf", os.path.join(OFFICIAL_DIR, "Pursuit_Force_Official.ptf"), "Pursuit Force Official Sony Theme"),
        ("pspthemes-neko/Lemmings.ptf", os.path.join(OFFICIAL_DIR, "Lemmings_Official.ptf"), "Lemmings Official Sony Theme"),
        ("pspthemes-neko/Ghost in the Shell.ptf", os.path.join(OFFICIAL_DIR, "Ghost_in_the_Shell.ptf"), "Ghost in the Shell Stand Alone Complex Theme"),
        ("pspthemes-neko/clearXMBblack.ptf", os.path.join(OFFICIAL_DIR, "Clear_XMB_Black_PTF.ptf"), "Clear XMB Black PTF Theme"),
    ]),
    ('themes_discord_mix.zip', [
        ("themes/MonsterHunterPortable3rd_LE.ptf", os.path.join(OFFICIAL_DIR, "Monster_Hunter_Portable_3rd_LE.ptf"), "Monster Hunter Portable 3rd LE Official"),
        ("themes/ShinMegamiTenseiPersona3Portable_Theme-PSP.ptf", os.path.join(OFFICIAL_DIR, "Persona_3_Portable_Official.ptf"), "Shin Megami Tensei Persona 3 Portable Official"),
        ("themes/okami.ptf", os.path.join(OFFICIAL_DIR, "Okami_Theme.ptf"), "Okami Theme"),
    ]),
    ('kippykip.zip', [
        ("kippykip/LittleBigPlanetPSP_PSPTheme.ptf", os.path.join(OFFICIAL_DIR, "LittleBigPlanet_PSP.ptf"), "LittleBigPlanet PSP Theme"),
        ("kippykip/LocoRoco2_the planet_JP.ptf", os.path.join(OFFICIAL_DIR, "LocoRoco_2_The_Planet.ptf"), "LocoRoco 2 The Planet JP Official"),
        ("kippykip/Dead_Space_2_Theme.ptf", os.path.join(OFFICIAL_DIR, "Dead_Space_2.ptf"), "Dead Space 2 Theme"),
        ("kippykip/MAC OS X Reflective Theme updated.ptf", os.path.join(OFFICIAL_DIR, "Apple_Mac_OS_X_Reflective.ptf"), "Apple Mac OS X Reflective Theme"),
        ("kippykip/Windows XP [6.61].ctf", os.path.join(CTF_DIR, "Windows_XP_661.ctf"), "Windows XP 6.61 CTF"),
        ("kippykip/PS3.theme.6.60.ctf", os.path.join(CTF_DIR, "PS3_Theme_660.ctf"), "PS3 Theme 6.60 CTF"),
    ]),
]:
    zip_url = f"https://archive.org/download/psp-themes-pack/{zip_name}"
    try:
        print(f"Downloading {zip_name} into memory...")
        req = urllib.request.Request(zip_url, headers={'User-Agent': USER_AGENT})
        with urllib.request.urlopen(req, timeout=30) as resp:
            zf = zipfile.ZipFile(io.BytesIO(resp.read()))
        for inner, dest, desc in items:
            try:
                data = zf.read(inner)
                save_theme(dest, data, desc)
            except Exception as e:
                print(f"[FAIL] {inner} ({desc}): {e}")
    except Exception as e:
        print(f"[FAIL] Processing {zip_name}: {e}")
    time.sleep(0.5)

print("\n" + "=" * 60)
print("6. Downloading Top-Rated 6.60/6.61 CTF Themes from Shenron0 GitHub Repository")
print("=" * 60)
ctf_shenron_list = [
    ("Clear%20XMB%20Black%203D.ctf", "Clear_XMB_Black_3D.ctf", "Clear XMB Black 3D CTF"),
    ("Clear%20XMB%20Black.ctf", "Clear_XMB_Black.ctf", "Clear XMB Black CTF"),
    ("Clear%20XMB%20White.ctf", "Clear_XMB_White.ctf", "Clear XMB White CTF"),
    ("Persona%203.ctf", "Persona_3.ctf", "Persona 3 CTF"),
    ("Ultimate%20PS3.ctf", "Ultimate_PS3.ctf", "Ultimate PS3 CTF"),
    ("Sony%20Mod.ctf", "Sony_Mod_XMB.ctf", "Sony Mod XMB CTF"),
    ("Large%20Original%20Icons%20Midnight%20Black.ctf", "Midnight_Black_Large_Icons.ctf", "Midnight Black Large Icons CTF"),
    ("Gran%20Turismo%205.ctf", "Gran_Turismo_5.ctf", "Gran Turismo 5 CTF"),
    ("Cyber%20Tech.ctf", "Cyber_Tech.ctf", "Cyber Tech (Cyberpunk) CTF"),
    ("Matrix%20Dynamic.ctf", "Matrix_Dynamic.ctf", "Matrix Dynamic CTF"),
    ("Modern%20Pro.ctf", "Modern_Pro.ctf", "Modern Pro CTF"),
    ("Honeycomb.ctf", "Honeycomb.ctf", "Honeycomb CTF"),
    ("Heavy%20Rain.ctf", "Heavy_Rain.ctf", "Heavy Rain CTF"),
    ("IPSP%20Cold.ctf", "IPSP_Apple_Cold.ctf", "IPSP Cold (Apple iOS) CTF"),
    ("Universal%20Red%20Darkness.ctf", "Universal_Red_Darkness.ctf", "Universal Red Darkness (Synthwave) CTF"),
    ("Sword%20Art%20Online.ctf", "Sword_Art_Online.ctf", "Sword Art Online CTF"),
    ("King%20Of%20Fighters.ctf", "King_Of_Fighters.ctf", "The King of Fighters CTF"),
    ("Dragonball%20Z.ctf", "Dragonball_Z.ctf", "Dragonball Z CTF"),
    ("Hatsune%20Miku.ctf", "Hatsune_Miku.ctf", "Hatsune Miku CTF"),
    ("Allure%20v2.ctf", "Allure_v2.ctf", "Allure v2 CTF"),
    ("In%20A%20Flow.ctf", "In_A_Flow.ctf", "In A Flow CTF"),
    ("Reflect.ctf", "Reflect.ctf", "Reflect CTF"),
    ("Hud.ctf", "Hud.ctf", "HUD CTF"),
    ("Xbox360.ctf", "Xbox_360.ctf", "Xbox 360 CTF"),
    ("Windows%20Phone%207.ctf", "Windows_Phone_7.ctf", "Windows Phone 7 CTF"),
]

for remote_name, local_name, desc in ctf_shenron_list:
    url = f"https://raw.githubusercontent.com/Shenron0/PSP-Themes/master/{remote_name}"
    download_direct(url, os.path.join(CTF_DIR, local_name), desc)
    time.sleep(0.3)

print("\n" + "=" * 60)
print("7. Downloading Top-Rated Themes from PSPunk")
print("=" * 60)
pspunk_list = [
    ("https://www.pspunk.com/files/theme/ptf/MaterialDesignTinyWhite_JohnEdwa_v09.ptf", os.path.join(OFFICIAL_DIR, "Material_Design_Tiny_White.ptf"), "Material Design Tiny White PTF"),
    ("https://www.pspunk.com/files/theme/ptf/darkxmb.ptf", os.path.join(OFFICIAL_DIR, "Dark_XMB.ptf"), "Dark XMB PTF"),
    ("https://www.pspunk.com/files/theme/ctf/CustomXMBwhite.660.ctf", os.path.join(CTF_DIR, "Custom_XMB_White_660.ctf"), "Custom XMB White 6.60 CTF"),
    ("https://www.pspunk.com/files/theme/ctf/Slanty.shanty.(pink.and.black).660.ctf", os.path.join(CTF_DIR, "Slanty_Shanty_660.ctf"), "Slanty Shanty 6.60 CTF"),
]
for url, dest_path, desc in pspunk_list:
    download_direct(url, dest_path, desc)
    time.sleep(0.3)

print("\n" + "=" * 60)
print("DOWNLOAD & VERIFICATION SUMMARY")
print("=" * 60)
official_files = sorted(os.listdir(OFFICIAL_DIR))
ctf_files = sorted(os.listdir(CTF_DIR))

print(f"\n[OFFICIAL THEMES (.ptf)] Total: {len(official_files)}")
for f in official_files:
    size = os.path.getsize(os.path.join(OFFICIAL_DIR, f))
    print(f"  * {f} ({size / 1024:.1f} KB)")

print(f"\n[CUSTOM THEMES (.ctf)] Total: {len(ctf_files)}")
for f in ctf_files:
    size = os.path.getsize(os.path.join(CTF_DIR, f))
    print(f"  * {f} ({size / 1024:.1f} KB)")
