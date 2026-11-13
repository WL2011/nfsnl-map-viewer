"""Export NFS No Limits track environments (static scene geometry + diffuse/glow textures) to .glb.

usage: export_map.py [--tracks DIR] [--out DIR] [--tex-max N] [--no-tex] [--list] [scene ...]
  scene: a '*.scene_static.sba' name (or a substring of one); default = every static scene found.

Sources (all plain SBIN, see sbtyped.py):
  <map>.scene_static.sba                     Model -> Instances (transform, Primitive -> VertexBuffer/IndexBuffer)
  <map>.lit_scene_<x>.scene_static.lightmaps.sba   BeastMaterialVariables, in order = '#materialvars#N'
  texture_*.sba                              Texture -> Image mips (ETC/ETC2/ASTC/PNG/...)
Packs live in ../packs (pulled by pull_packs.py); installed cabinets (flag 0x20) are read from packs/<pack id>/<n>.cab or <n>-<offset>.*"""
import sys, os, io, re, json, gzip, struct, argparse, zlib
from functools import lru_cache
import numpy as np
import zstandard
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sbin import chunks
from sbtyped import TypedSB, NULL

HERE = os.path.dirname(os.path.abspath(__file__))
NP_TYPE = {0: np.int8, 1: np.uint8, 2: np.int16, 3: np.uint16, 4: np.int32, 5: np.float32, 6: np.uint32}
USAGE = ['Position', 'PositionRHW', 'BoneWeight', 'BoneIndex', 'Normal', 'Tangent', 'Binormal',
         'PointSize', 'TexCoord', 'Color', 'DeltaPosition', 'DeltaNormal']
WRAP = {0: 10497, 1: 33648, 2: 33071}


# ------------------------------------------------------------------ pack store
class Store:
    """Index of every file in the .pack files of a folder; reads cabinets lazily (one cabinet cached)."""
    def __init__(self, root):
        self.root = root; self.index = {}; self.toc = {}
        for fn in sorted(os.listdir(root)):
            if not fn.endswith('.pack'): continue
            pid = fn[:-5]
            with open(f'{root}/{fn}', 'rb') as fh:
                head = fh.read(20); a, b = struct.unpack('<II', head[4:12])
                if head[:4] != b'PACK': continue
                toc_gz = head[20:] + fh.read(12 + a - 20)
            size = os.path.getsize(f'{root}/{fn}')
            cabs, files = parse_toc(gzip.decompress(toc_gz), size - b)
            self.toc[pid] = (b, cabs, files)
            for f in files:
                self.index.setdefault(f['name'], []).append((pid, f))

    @lru_cache(maxsize=4)
    def cabinet(self, pid, ci):
        data_start, cabs, _ = self.toc[pid]; c = cabs[ci]
        if c['flags'] & 0x20:
            return None                                   # truncated: installed next to the pack
        with open(f'{self.root}/{pid}.pack', 'rb') as fh:
            fh.seek(data_start + c['packed_offset']); blob = fh.read(c['packed_length'])
        if c['flags'] & 4: return zstandard.ZstdDecompressor().decompressobj().decompress(blob)
        if c['flags'] & 1: return zlib.decompress(blob)
        if c['flags'] & 2: raise ValueError('LZHAM cabinets are not supported')
        return blob

    def read_entry(self, pid, f):
        cab = self.cabinet(pid, f['cabinet'])
        if cab is not None:
            return cab[f['offset']:f['offset'] + f['length']]
        d = f'{self.root}/{pid}'
        p = f'{d}/{f["cabinet"]}.cab'
        if os.path.exists(p):
            with open(p, 'rb') as fh:
                fh.seek(f['offset']); return fh.read(f['length'])
        if os.path.isdir(d):
            for x in os.listdir(d):
                if x.startswith(f'{f["cabinet"]}-{f["offset"]}.'):
                    return open(f'{d}/{x}', 'rb').read()
        return None

    def read(self, name):
        """All readable copies of `name` as bytes (several packs may carry the same file)."""
        out = []
        for pid, f in self.index.get(name, []):
            try:
                b = self.read_entry(pid, f)
            except Exception:
                b = None
            if b: out.append(b)
        return out


def parse_toc(toc, data_len):
    """Cabinet + file records from a decompressed pack TOC (same heuristics as texpack.Pack)."""
    c = chunks(toc)
    cd = c['CDAT'][1]; ch = c['CHDR'][1]
    S = [cd[o:o + l].decode('utf8', 'replace') for o, l in (struct.unpack('<II', ch[i:i + 8]) for i in range(0, len(ch), 8))]
    D = c['DATA'][1]; cabs = []
    for p in range(0, len(D) - 8, 4):
        if struct.unpack_from('<I', D, p)[0] != 0x10: continue
        n = struct.unpack_from('<I', D, p + 4)[0]
        if not n or p + 8 + 16 * n > len(D): continue
        recs = [struct.unpack_from('<4I', D, p + 8 + 16 * k) for k in range(n)]
        ok = all(r[0] < 0x100 for r in recs) and all(r[0] & 0x20 or r[2] + r[3] <= data_len for r in recs)
        st = sorted((r[2], r[3]) for r in recs if not r[0] & 0x20)
        ok = ok and (not st or st[0][0] == 0) and all(0 <= st[k + 1][0] - (st[k][0] + st[k][1]) < 16 for k in range(len(st) - 1))
        if ok:
            cabs = [dict(flags=r[0], length=r[1], packed_offset=r[2], packed_length=r[3]) for r in recs]; break
    files = []; p = 0
    while p + 20 <= len(D):
        nm, cab, off, ln, fl = struct.unpack_from('<5I', D, p)
        if 0 < nm < len(S) and '.' in S[nm] and cab < max(1, len(cabs)) and fl < 16:
            files.append(dict(name=S[nm], cabinet=cab, offset=off, length=ln)); p += 20
        else:
            p += 4
    return cabs, files


# ------------------------------------------------------------------ textures
def decode_image(fmt, data, w, h):
    """-> PIL RGBA image, or None for unsupported formats."""
    from PIL import Image
    import texture2ddecoder as t2d
    if fmt in (13, 14):                                   # PNG / JPEG
        return Image.open(io.BytesIO(data)).convert('RGBA')
    if fmt == 0: return Image.frombytes('RGBA', (w, h), data[:w * h * 4])
    if fmt == 1: return Image.frombytes('RGB', (w, h), data[:w * h * 3]).convert('RGBA')
    if fmt == 35:
        v = np.frombuffer(data[:w * h * 2], np.uint16).reshape(h, w)
        rgb = np.dstack([(v >> 11 & 31) * 255 // 31, (v >> 5 & 63) * 255 // 63, (v & 31) * 255 // 31]).astype(np.uint8)
        return Image.fromarray(rgb, 'RGB').convert('RGBA')
    dec = {12: t2d.decode_etc1, 30: t2d.decode_etc2, 31: t2d.decode_etc2a8, 32: t2d.decode_etc2a1,
           6: t2d.decode_bc1, 7: t2d.decode_bc2 if hasattr(t2d, 'decode_bc2') else None, 8: t2d.decode_bc3,
           9: t2d.decode_atc_rgb4, 10: t2d.decode_atc_rgba8, 11: t2d.decode_atc_rgba8}.get(fmt)
    astc = {16: (4, 4), 17: (5, 4), 18: (5, 5), 19: (6, 5), 20: (6, 6), 21: (8, 5), 22: (8, 6), 23: (8, 8),
            24: (10, 5), 25: (10, 6), 26: (10, 8), 27: (10, 10), 28: (12, 10), 29: (12, 12)}.get(fmt)
    if astc:
        raw = t2d.decode_astc(data, w, h, *astc)
    elif dec:
        raw = dec(data, w, h)
    elif fmt in (2, 3, 4, 5):
        raw = t2d.decode_pvrtc(data, w, h, fmt in (2, 3))
    else:
        return None
    return Image.frombytes('RGBA', (w, h), raw, 'raw', 'BGRA')


def load_texture(store, name, tex_max):
    """Best copy of texture `name` -> (PNG bytes, has_alpha, wrap_s, wrap_t) or None."""
    best = None
    for raw in store.read(name):
        try:
            t = TypedSB(raw); tex = t.get(t.root, 2)
        except Exception:
            continue
        if not isinstance(tex, dict) or tex.get('_type') != 'Texture' or not tex.get('mipmaps'): continue
        top = max(m['width'] for m in tex['mipmaps'])
        if best is None or top > best[0]: best = (top, t, tex)
    if best is None: return None
    _, t, tex = best
    mips = sorted(tex['mipmaps'], key=lambda m: -m['width'])
    pick = next((m for m in mips if max(m['width'], m['height']) <= tex_max), mips[-1])
    for m in [pick] + [m for m in mips if m is not pick]:
        try:
            img = decode_image(m['format'], t.blob(m['data'][1]), m['width'], m['height'])
        except Exception:
            img = None
        if img is not None: break
    else:
        return None
    if max(img.size) > tex_max:
        from PIL import Image
        img = img.resize((min(tex_max, img.size[0]), min(tex_max, img.size[1])), Image.LANCZOS)
    alpha = img.getextrema()[3][0] < 250
    b = io.BytesIO(); (img if alpha else img.convert('RGB')).save(b, 'PNG', optimize=False, compress_level=6)
    return b.getvalue(), alpha, WRAP.get(tex.get('wrap_s'), 10497), WRAP.get(tex.get('wrap_t'), 10497)


# ------------------------------------------------------------------ geometry
def decode_vb(t, vb):
    n = vb['count']; decl = vb['vertex_declaration']; stride = decl['stride']
    raw = t.blob(vb['data'][1])
    sb = vb['scale_bias_entries'] or []
    out = {}
    for s in decl['streams']:
        et, cnt, off = s['element_type'], s['element_count'], s['offset']
        dt = np.dtype(NP_TYPE[et])
        a = np.ndarray((n, cnt), dtype=dt, buffer=raw, offset=off, strides=(stride, dt.itemsize)).astype(np.float64)
        sbi = s['scale_bias_index']
        if sbi != NULL and sbi < len(sb):
            sc = sb[sbi]['scale']; bi = sb[sbi]['bias']
            a = a * np.array([sc['x'], sc['y'], sc['z']][:cnt]) + np.array([bi['x'], bi['y'], bi['z']][:cnt])
        elif et in (0, 2):                                # signed normalized
            a = a / (127.0 if et == 0 else 32767.0)
        elif et in (1, 3):                                # unsigned normalized
            a = a / (255.0 if et == 1 else 65535.0)
        out[(USAGE[s['usage']], s['index'])] = a
    return out


def decode_ib(t, ib):
    raw = t.blob(ib['data'][1]); cnt = ib['count']
    size = len(raw) // cnt if cnt else 2
    return np.frombuffer(raw[:cnt * size], np.uint32 if size >= 4 else np.uint16).astype(np.uint32) + ib['vertex_offset']


def find_material_file(store, scene_name):
    base = scene_name[:-len('.scene_static.sba')]
    cands = sorted(n for n in store.index if n.startswith(base + '.') and n.endswith('.scene_static.lightmaps.sba'))
    for pref in ('day', 'sunset', 'dawn', 'overcast', 'night'):
        for n in cands:
            if f'lit_scene_{pref}' in n: return n
    return cands[0] if cands else None


def material_vars(store, name):
    if not name: return []
    for raw in store.read(name):
        t = TypedSB(raw)
        ids = [i for i in range(1, len(t.objs)) if t.objs[i][1] == 0 and t.structs[t.raw_struct(i)[0]][0] == 'BeastMaterialVariables']
        return [t.get(i, 1) for i in ids]
    return []


def tex_name(ext):
    return os.path.basename(ext['path']) if isinstance(ext, dict) and ext.get('path') else None


def matvar_index(inst):
    mi = inst['material_instance']
    path = mi['variables'].get('path', '') if mi and isinstance(mi.get('variables'), dict) else ''
    m = re.match(r'#materialvars#(\d+)$', path or '')
    return int(m.group(1)) if m else None


def scene_instances(store, scene_name):
    raws = store.read(scene_name)
    if not raws: return None, []
    t = TypedSB(raws[0]); model = t.get(t.root, 0)
    return t, (t.get(model['instances'][1], 0) if model['instances'] else [])


def borrowed_materials(store, scene_name, geo_names, log=print):
    """Event variants (devilsnight_*, lunar_*.variant) ship no lightmaps file of their own: take the material
    variables of the base map that shares the most geometry names, matched per geometry name."""
    best = None
    for other in sorted(n for n in store.index if n.endswith('.scene_static.sba') and n != scene_name):
        mf = find_material_file(store, other)
        if not mf: continue
        t, ids = scene_instances(store, other)
        names = [t.get(r[1], 2)['geometry_name'] for r in ids]
        overlap = len(set(names) & set(geo_names))
        if overlap and (best is None or overlap > best[0]): best = (overlap, other, mf, t, ids)
    if not best: return {}, None
    overlap, other, mf, t, ids = best
    mv = material_vars(store, mf); out = {}
    for r in ids:
        inst = t.get(r[1], 2); k = matvar_index(inst)
        if k is not None and k < len(mv): out.setdefault(inst['geometry_name'], mv[k])
    log(f'  no lightmaps file of its own: borrowing materials of {other} ({overlap}/{len(set(geo_names))} geometries match)')
    return out, mf


# ------------------------------------------------------------------ glTF writer
class Glb:
    def __init__(self):
        self.bin = bytearray(); self.j = dict(asset=dict(version='2.0', generator='nfsnl export_map.py'),
                                              scenes=[dict(nodes=[])], scene=0, nodes=[], meshes=[], accessors=[],
                                              bufferViews=[], materials=[], textures=[], images=[], samplers=[])
        self.mat_ids = {}; self.img_ids = {}; self.smp_ids = {}

    def view(self, data, target=None):
        while len(self.bin) % 4: self.bin.append(0)
        v = dict(buffer=0, byteOffset=len(self.bin), byteLength=len(data))
        if target: v['target'] = target
        self.bin += data; self.j['bufferViews'].append(v); return len(self.j['bufferViews']) - 1

    def acc(self, arr, typ, comp, target, minmax=False):
        a = dict(bufferView=self.view(arr.tobytes(), target), componentType=comp, count=len(arr), type=typ)
        if minmax: a['min'] = arr.min(0).tolist(); a['max'] = arr.max(0).tolist()
        self.j['accessors'].append(a); return len(self.j['accessors']) - 1

    def image(self, name, png):
        if name not in self.img_ids:
            self.j['images'].append(dict(name=name, mimeType='image/png', bufferView=self.view(png)))
            self.img_ids[name] = len(self.j['images']) - 1
        return self.img_ids[name]

    def texture(self, name, png, ws, wt):
        key = (ws, wt)
        if key not in self.smp_ids:
            self.j['samplers'].append(dict(magFilter=9729, minFilter=9987, wrapS=ws, wrapT=wt)); self.smp_ids[key] = len(self.j['samplers']) - 1
        self.j['textures'].append(dict(source=self.image(name, png), sampler=self.smp_ids[key]))
        return len(self.j['textures']) - 1

    def material(self, key, build):
        if key not in self.mat_ids:
            self.j['materials'].append(build()); self.mat_ids[key] = len(self.j['materials']) - 1
        return self.mat_ids[key]

    def write(self, path):
        for k in ('materials', 'textures', 'images', 'samplers'):
            if not self.j[k]: del self.j[k]
        while len(self.bin) % 4: self.bin.append(0)
        self.j['buffers'] = [dict(byteLength=len(self.bin))]
        js = json.dumps(self.j, separators=(',', ':')).encode()
        while len(js) % 4: js += b' '
        with open(path, 'wb') as fh:
            fh.write(struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(self.bin)))
            fh.write(struct.pack('<II', len(js), 0x4E4F534A)); fh.write(js)
            fh.write(struct.pack('<II', len(self.bin), 0x004E4942)); fh.write(self.bin)


def export_scene(store, scene_name, out_path, tex_max=1024, with_tex=True, flip_v=False, log=print):
    t, inst_ids = scene_instances(store, scene_name)
    if t is None: raise FileNotFoundError(scene_name)
    if not inst_ids:
        log(f'{scene_name}: no static instances (overlay layer), skipped')
        return dict(scene=scene_name, skipped='no static instances')
    mat_file = find_material_file(store, scene_name); mv = material_vars(store, mat_file); by_geo = {}
    log(f'{scene_name}: {len(inst_ids)} instances, materials from {mat_file} ({len(mv)})')
    if not mv:
        by_geo, mat_file = borrowed_materials(store, scene_name, [t.get(r[1], 2)['geometry_name'] for r in inst_ids], log)
    g = Glb(); tex_cache = {}; mesh_ids = {}; missing = set(); nverts = ntris = 0
    vb_cache = {}

    def get_tex(name):
        if name not in tex_cache:
            tex_cache[name] = load_texture(store, name, tex_max) if (with_tex and name) else None
            if with_tex and name and tex_cache[name] is None: missing.add(name)
        return tex_cache[name]

    def build_material(inst):
        mi = inst['material_instance']; k = matvar_index(inst)
        v = mv[k] if mv and k is not None and k < len(mv) else by_geo.get(inst['geometry_name'])
        diffuse = tex_name(v['diffuse']) if v else None
        glow = tex_name(v['glow_map']) if v else None
        flags = v['flags'] if v else 0
        key = (diffuse, glow, flags, mi['material'])
        def make():
            mat = dict(name=(diffuse or mi['material'] or 'material').replace('.sba', ''),
                       pbrMetallicRoughness=dict(metallicFactor=0.0, roughnessFactor=0.9))
            d = get_tex(diffuse)
            if d:
                mat['pbrMetallicRoughness']['baseColorTexture'] = dict(index=g.texture(diffuse, d[0], d[2], d[3]))
                if d[1]: mat['alphaMode'] = 'MASK'; mat['alphaCutoff'] = 0.5
            else:
                mat['pbrMetallicRoughness']['baseColorFactor'] = [0.6, 0.6, 0.6, 1.0]
            if flags & 2 and 'alphaMode' not in mat and d and d[1]: mat['alphaMode'] = 'MASK'
            if flags & 1: mat['doubleSided'] = True
            gl = get_tex(glow) if glow else None
            if gl:
                mat['emissiveTexture'] = dict(index=g.texture(glow, gl[0], gl[2], gl[3])); mat['emissiveFactor'] = [1.0, 1.0, 1.0]
            return mat
        return g.material(key, make)

    for ref in inst_ids:
        inst = t.get(ref[1], 2) if isinstance(ref, tuple) else ref
        prim_ref = struct.unpack_from('<I', t.D, t.raw_struct(ref[1])[1] + 68)[0]
        if prim_ref not in mesh_ids:
            prim = t.get(prim_ref, 8)
            if prim['type'] != 1 or not prim['vertex_buffer'] or not prim['index_buffer']:
                mesh_ids[prim_ref] = None
            else:
                vbk = id(prim['vertex_buffer'])
                if vbk not in vb_cache: vb_cache[vbk] = decode_vb(t, prim['vertex_buffer'])
                V = vb_cache[vbk]
                I = decode_ib(t, prim['index_buffer'])[prim['index_offset']:prim['index_offset'] + prim['index_count']]
                used, I2 = np.unique(I, return_inverse=True)
                P = V[('Position', 0)][used].astype(np.float32)
                attrs = dict(POSITION=g.acc(np.ascontiguousarray(P), 'VEC3', 5126, 34962, True))
                if ('Normal', 0) in V:
                    N = V[('Normal', 0)][used]; N = N / np.maximum(np.linalg.norm(N, axis=1, keepdims=True), 1e-8)
                    attrs['NORMAL'] = g.acc(np.ascontiguousarray(N.astype(np.float32)), 'VEC3', 5126, 34962)
                for k in (0, 1):
                    if ('TexCoord', k) in V:
                        UV = V[('TexCoord', k)][used][:, :2].astype(np.float32)
                        if flip_v: UV[:, 1] = 1.0 - UV[:, 1]
                        attrs[f'TEXCOORD_{k}'] = g.acc(np.ascontiguousarray(UV), 'VEC2', 5126, 34962)
                idx = I2.astype(np.uint32 if len(used) > 65535 else np.uint16)
                prim_j = dict(attributes=attrs, indices=g.acc(idx, 'SCALAR', 5125 if idx.dtype == np.uint32 else 5123, 34963), mode=4)
                prim_j['material'] = build_material(inst)
                g.j['meshes'].append(dict(name=inst['geometry_name'] or inst['name'], primitives=[prim_j]))
                mesh_ids[prim_ref] = len(g.j['meshes']) - 1
                nverts += len(used); ntris += len(idx) // 3
        mi = mesh_ids[prim_ref]
        if mi is None: continue
        T = inst['transform']
        mat = [T[r][c] for r in ('x', 'y', 'z', 'translation') for c in ('x', 'y', 'z', 'w')]
        node = dict(name=inst['instance_name'] or inst['name'], mesh=mi)
        if mat != [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]: node['matrix'] = mat
        g.j['nodes'].append(node); g.j['scenes'][0]['nodes'].append(len(g.j['nodes']) - 1)
    g.write(out_path)
    log(f'  -> {out_path}: {len(g.j["nodes"])} nodes, {nverts} verts, {ntris} tris, '
        f'{len(g.j.get("images", []))} textures, {os.path.getsize(out_path) / 1e6:.1f} MB'
        + (f', missing textures: {sorted(missing)}' if missing else ''))
    return dict(scene=scene_name, nodes=len(g.j['nodes']), verts=nverts, tris=ntris, textures=len(g.j.get('images', [])),
                missing=sorted(missing), material_file=mat_file, mb=os.path.getsize(out_path) / 1e6)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('scenes', nargs='*')
    ap.add_argument('--tracks', default=os.path.join(HERE, '..', 'packs'))
    ap.add_argument('--out', default=os.path.join(HERE, '..', 'glb'))
    ap.add_argument('--tex-max', type=int, default=1024)
    ap.add_argument('--no-tex', action='store_true')
    ap.add_argument('--flip-v', action='store_true')
    ap.add_argument('--list', action='store_true')
    a = ap.parse_args()
    store = Store(a.tracks)
    allsc = sorted(n for n in store.index if n.endswith('.scene_static.sba'))
    if a.list:
        print('\n'.join(allsc)); return
    sel = [n for n in allsc if not a.scenes or any(s in n for s in a.scenes)]
    os.makedirs(a.out, exist_ok=True); report = []
    for n in sel:
        out = os.path.join(a.out, n[:-len('.scene_static.sba')] + '.glb')
        try:
            report.append(export_scene(store, n, out, a.tex_max, not a.no_tex, a.flip_v))
        except Exception as e:
            print(f'{n}: FAILED {type(e).__name__}: {e}'); report.append(dict(scene=n, error=f'{type(e).__name__}: {e}'))
    json.dump(report, open(os.path.join(a.out, 'report.json'), 'w'), indent=1, ensure_ascii=False)


if __name__ == '__main__':
    main()
