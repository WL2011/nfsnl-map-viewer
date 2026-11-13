"""Pull the track / environment packs of NFS No Limits from an Android device or emulator into ../packs.

usage: pull_packs.py [--serial 127.0.0.1:5555] [--dry-run]
Reads the device's packs/config/manifest.sb, selects packs tagged tracks_region*, remap_*, textures_environments,
props_textures or models_environments, and pulls <id>.pack plus <id>/ (installed cabinets). Packs that ship inside
the APK (InstalledFromBundle, no .pack on the device) are extracted from base.apk."""
import sys, os, re, struct, argparse, subprocess, zipfile, tempfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sbin import chunks

HERE = os.path.dirname(os.path.abspath(__file__))
PKG = 'com.ea.game.nfs14_row'
DEV = f'/sdcard/Android/data/{PKG}/files/packs'
TAGS = re.compile(r'(^|\s)(tracks_region\S*|remap_\S+|textures_environments|props_textures|models_environments)(\s|$)')


def adb(serial, *args, check=True):
    return subprocess.run(['adb', '-s', serial, *args], stdin=subprocess.DEVNULL, capture_output=True, text=True, check=check)


def manifest_entries(path):
    d = open(path, 'rb').read(); c = chunks(d); D = c['DATA'][1]
    cd = c['CDAT'][1]; ch = c['CHDR'][1]
    S = [cd[o:o + l].decode('utf8', 'replace') for o, l in (struct.unpack('<II', ch[i:i + 8]) for i in range(0, len(ch), 8))]
    for sid, s in enumerate(S):
        if not re.fullmatch(r'[0-9a-f]{32}', s): continue
        pat = struct.pack('<H', sid) + b'\xcd\xcd'; i = D.find(pat)
        while i != -1 and (i - 16) % 4: i = D.find(pat, i + 1)
        r = i - 16
        if i < 0 or r < 0: continue
        cs, ln, fl, inst, idd, pad, ci, sku, tags, trl = struct.unpack_from('<IIIIHHIHHI', D, r)
        if idd != sid or tags >= len(S): continue
        yield dict(id=s, length=ln, bundled=bool(fl & 1), tags=S[tags])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', default='127.0.0.1:5555')
    ap.add_argument('--out', default=os.path.join(HERE, '..', 'packs'))
    ap.add_argument('--dry-run', action='store_true')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    man = os.path.join(a.out, 'manifest.sb')
    adb(a.serial, 'pull', f'{DEV}/config/manifest.sb', man)
    sel = [e for e in manifest_entries(man) if TAGS.search(e['tags'])]
    print(f'{len(sel)} packs selected, {sum(e["length"] for e in sel) / 1e6:.0f} MB')
    if a.dry_run:
        for e in sel: print(e['id'], e['length'], 'bundled' if e['bundled'] else '', e['tags'][:100])
        return
    apk = None
    for k, e in enumerate(sel, 1):
        pid = e['id']
        got = adb(a.serial, 'pull', f'{DEV}/{pid}.pack', os.path.join(a.out, f'{pid}.pack'), check=False).returncode == 0
        if adb(a.serial, 'shell', f'[ -d {DEV}/{pid} ] && echo y', check=False).stdout.strip() == 'y':
            os.makedirs(os.path.join(a.out, pid), exist_ok=True)
            adb(a.serial, 'pull', f'{DEV}/{pid}/.', os.path.join(a.out, pid), check=False)
        if not got and e['bundled']:
            if apk is None:
                path = adb(a.serial, 'shell', f'pm path {PKG}').stdout.split()[0].split(':', 1)[1]
                apk = os.path.join(tempfile.mkdtemp(), 'base.apk'); adb(a.serial, 'pull', path, apk)
            with zipfile.ZipFile(apk) as z:
                name = f'assets/bootstrap.android/{pid}.pack'
                if name in z.namelist():
                    open(os.path.join(a.out, f'{pid}.pack'), 'wb').write(z.read(name)); got = True
        print(f'[{k}/{len(sel)}] {pid} {"ok" if got else "MISSING"}')


if __name__ == '__main__':
    main()
