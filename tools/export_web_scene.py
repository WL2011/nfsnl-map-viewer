"""Pack each track's static scene for the browser viewer (viewer/index.html, "原赛道" render mode).

usage: export_web_scene.py [--packs DIR] [--out DIR] [--tex-max 512] [scene-substring ...]
Writes <out>/<stem>.js (base64 GLB, loaded with a <script> tag so it works from file://) and <out>/index.js.

What goes in:
  geometry   every static instance, transforms baked, merged per material (POSITION, TEXCOORD_0, TEXCOORD_1)
  textures   diffuse (+ glow as emissive), downscaled to --tex-max, JPEG unless the texture has alpha
  lightmaps  the Beast lightmap of every instance with its ScaleBias applied (value = texel * scale + bias),
             divided by LM_RANGE so it fits 8 bits, packed into 2048 px atlas pages; TEXCOORD_1 is remapped into
             the atlas. material.extras = {lightmap: <texture index>, lightmapRange: LM_RANGE}.
The game multiplies diffuse by the lightmap in gamma space; the viewer reproduces that by marking the lightmap
as sRGB and using intensity LM_RANGE ** 2.2 (see viewer/index.html)."""
import sys, os, io, re, json, struct, base64, argparse, math
import numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from export_map import (Store, decode_vb, decode_ib, decode_image, find_material_file, material_vars, tex_name,
                        matvar_index, scene_instances, WRAP)
from sbtyped import TypedSB

HERE = os.path.dirname(os.path.abspath(__file__))
LM_RANGE = 1.6
PAGE = 2048
PAD = 2


# ------------------------------------------------------------------ textures
def best_texture(store, name):
    best = None
    for raw in store.read(name):
        try:
            t = TypedSB(raw); tex = t.get(t.root, 2)
        except Exception:
            continue
        if isinstance(tex, dict) and tex.get('_type') == 'Texture' and tex.get('mipmaps'):
            top = max(m['width'] for m in tex['mipmaps'])
            if best is None or top > best[0]: best = (top, t, tex)
    return best


def texture_image(t, tex, tex_max):
    mips = sorted(tex['mipmaps'], key=lambda m: -m['width'])
    pick = next((m for m in mips if max(m['width'], m['height']) <= tex_max), mips[-1])
    for m in [pick] + [m for m in mips if m is not pick]:
        try:
            img = decode_image(m['format'], t.blob(m['data'][1]), m['width'], m['height'])
        except Exception:
            img = None
        if img is not None: return img
    return None


def encode(img, alpha, quality=82):
    b = io.BytesIO()
    if alpha:
        img.save(b, 'PNG', optimize=True); return b.getvalue(), 'image/png'
    img.convert('RGB').save(b, 'JPEG', quality=quality, optimize=True, progressive=False)
    return b.getvalue(), 'image/jpeg'


class Lightmaps:
    """Decode lightmap #N of a *.lightmaps.sba with its ScaleBias, shelf-pack into atlas pages."""
    def __init__(self, store, name):
        self.t = TypedSB(store.read(name)[0])
        t = self.t
        self.list = [r[1] for r in t.get(t.root, 0)]
        ids = [i for i in range(1, len(t.objs)) if t.objs[i][1] == 0]
        self.sb = [t.get(i, 2) for i in ids if t.structs[t.raw_struct(i)[0]][0] == 'ScaleBias']
        self.pages = []; self.cursor = None

    def image(self, n, k):
        tex = self.t.get(self.list[n], 2)
        top = sorted(tex['mipmaps'], key=lambda m: -m['width'])[0]
        img = decode_image(top['format'], self.t.blob(top['data'][1]), top['width'], top['height'])
        a = np.asarray(img.convert('RGB'), dtype=np.float32) / 255.0
        if k is not None and k < len(self.sb):
            s = self.sb[k]['scale']; b = self.sb[k]['bias']
            a = a * np.array([s['x'], s['y'], s['z']], np.float32) + np.array([b['x'], b['y'], b['z']], np.float32)
        a = np.clip(a / LM_RANGE, 0, 1)
        return Image.fromarray((a * 255 + 0.5).astype(np.uint8), 'RGB')

    def place(self, img):
        """-> (page, x, y, w, h) inner rect in pixels."""
        w = min(img.size[0], 128 if img.size[0] <= 128 else 256); h = min(img.size[1], w)
        if img.size != (w, h): img = img.resize((w, h), Image.BILINEAR)
        W, H = w + 2 * PAD, h + 2 * PAD
        if self.cursor is None or self.cursor[0] + W > PAGE:
            if self.cursor is None or self.cursor[1] + self.cursor[2] + H > PAGE:
                self.pages.append(Image.new('RGB', (PAGE, PAGE), (128, 128, 128))); self.cursor = [0, 0, 0]
            else:
                self.cursor = [0, self.cursor[1] + self.cursor[2], 0]
        if self.cursor[1] + H > PAGE:
            self.pages.append(Image.new('RGB', (PAGE, PAGE), (128, 128, 128))); self.cursor = [0, 0, 0]
        x, y = self.cursor[0], self.cursor[1]
        page = self.pages[-1]
        padded = img.resize((W, H), Image.NEAREST) if False else None
        # edge-extend padding so bilinear filtering at tile borders samples the tile itself
        tile = Image.new('RGB', (W, H)); tile.paste(img, (PAD, PAD))
        tile.paste(img.crop((0, 0, w, 1)).resize((w, PAD)), (PAD, 0)); tile.paste(img.crop((0, h - 1, w, h)).resize((w, PAD)), (PAD, H - PAD))
        tile.paste(tile.crop((PAD, 0, PAD + 1, H)).resize((PAD, H)), (0, 0)); tile.paste(tile.crop((PAD + w - 1, 0, PAD + w, H)).resize((PAD, H)), (W - PAD, 0))
        page.paste(tile, (x, y))
        self.cursor[0] += W; self.cursor[2] = max(self.cursor[2], H)
        return len(self.pages) - 1, x + PAD, y + PAD, w, h


# ------------------------------------------------------------------ GLB writer (images with any mime type)
class Glb:
    def __init__(self):
        self.bin = bytearray()
        self.j = dict(asset=dict(version='2.0', generator='nfsnl export_web_scene.py'), scenes=[dict(nodes=[])], scene=0,
                      extensionsUsed=['KHR_mesh_quantization'], extensionsRequired=['KHR_mesh_quantization'],
                      nodes=[], meshes=[], accessors=[], bufferViews=[], materials=[], textures=[], images=[], samplers=[])
        self.smp = {}

    def view(self, data, target=None):
        while len(self.bin) % 4: self.bin.append(0)
        v = dict(buffer=0, byteOffset=len(self.bin), byteLength=len(data))
        if target: v['target'] = target
        self.bin += data; self.j['bufferViews'].append(v); return len(self.j['bufferViews']) - 1

    def acc(self, arr, typ, comp, target, minmax=False):
        a = dict(bufferView=self.view(arr.tobytes(), target), componentType=comp, count=len(arr), type=typ)
        if minmax: a['min'] = arr.min(0).tolist(); a['max'] = arr.max(0).tolist()
        self.j['accessors'].append(a); return len(self.j['accessors']) - 1

    def texture(self, data, mime, ws=10497, wt=10497, name=None):
        self.j['images'].append(dict(mimeType=mime, bufferView=self.view(data), **({'name': name} if name else {})))
        if (ws, wt) not in self.smp:
            self.j['samplers'].append(dict(magFilter=9729, minFilter=9987, wrapS=ws, wrapT=wt)); self.smp[(ws, wt)] = len(self.j['samplers']) - 1
        self.j['textures'].append(dict(source=len(self.j['images']) - 1, sampler=self.smp[(ws, wt)]))
        return len(self.j['textures']) - 1

    def bytes(self):
        for k in ('materials', 'textures', 'images', 'samplers'):
            if not self.j[k]: del self.j[k]
        while len(self.bin) % 4: self.bin.append(0)
        self.j['buffers'] = [dict(byteLength=len(self.bin))]
        js = json.dumps(self.j, separators=(',', ':')).encode()
        while len(js) % 4: js += b' '
        return (struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(self.bin)) + struct.pack('<II', len(js), 0x4E4F534A) + js
                + struct.pack('<II', len(self.bin), 0x004E4942) + bytes(self.bin))


# ------------------------------------------------------------------ scene
def export(store, scene_name, tex_max, log=print):
    t, inst_ids = scene_instances(store, scene_name)
    mat_file = find_material_file(store, scene_name)
    if not inst_ids or not mat_file:
        return None
    mv = material_vars(store, mat_file)
    lms = Lightmaps(store, mat_file)
    lit = re.search(r'lit_scene_?([a-z]*)', mat_file).group(1) or 'day'
    buckets = {}; tex_cache = {}; g = Glb(); missing = set()

    def texture(name, maxsize):
        key = (name, maxsize)
        if key not in tex_cache:
            tex_cache[key] = None
            bt = best_texture(store, name) if name else None
            if bt:
                img = texture_image(bt[1], bt[2], maxsize)
                if img is not None:
                    alpha = img.getextrema()[3][0] < 250
                    data, mime = encode(img, alpha)
                    tex_cache[key] = (g.texture(data, mime, WRAP.get(bt[2].get('wrap_s'), 10497), WRAP.get(bt[2].get('wrap_t'), 10497), name), alpha)
            elif name:
                missing.add(name)
        return tex_cache[key]

    vb_cache = {}
    for ref in inst_ids:
        inst = t.get(ref[1], 2)
        prim_ref = struct.unpack_from('<I', t.D, t.raw_struct(ref[1])[1] + 68)[0]
        prim = t.get(prim_ref, 8)
        if prim['type'] != 1 or not prim['vertex_buffer'] or not prim['index_buffer']: continue
        vbk = id(prim['vertex_buffer'])
        if vbk not in vb_cache: vb_cache[vbk] = decode_vb(t, prim['vertex_buffer'])
        V = vb_cache[vbk]
        I = decode_ib(t, prim['index_buffer'])[prim['index_offset']:prim['index_offset'] + prim['index_count']]
        used, I2 = np.unique(I, return_inverse=True)
        P = V[('Position', 0)][used][:, :3]
        T = inst['transform']
        M = np.array([[T[r][c] for c in ('x', 'y', 'z', 'w')] for r in ('x', 'y', 'z', 'translation')])
        P = (np.c_[P, np.ones(len(P))] @ M)[:, :3]
        UV0 = V[('TexCoord', 0)][used][:, :2] if ('TexCoord', 0) in V else np.zeros((len(used), 2))
        UV1 = V[('TexCoord', 1)][used][:, :2] if ('TexCoord', 1) in V else None
        k = matvar_index(inst)
        v = mv[k] if k is not None and k < len(mv) else None
        diffuse = tex_name(v['diffuse']) if v else None
        glow = tex_name(v['glow_map']) if v else None
        flags = v['flags'] if v else 0
        page = -1
        if v and UV1 is not None and isinstance(v.get('light_map'), dict):
            m = re.search(r'#lightmaps#(\d+)$', v['light_map'].get('path', ''))
            sbm = re.search(r'#scalebias#(\d+)#', v.get('scale') or '')
            if m:
                try:
                    img = lms.image(int(m.group(1)), int(sbm.group(1)) if sbm else None)
                    page, x, y, w, h = lms.place(img)
                    u = np.clip(UV1, 0, 1)
                    UV1 = np.c_[(x + u[:, 0] * w) / PAGE, (y + u[:, 1] * h) / PAGE]
                except Exception as e:
                    page = -1
        key = (diffuse, glow, flags, page)
        b = buckets.setdefault(key, dict(P=[], UV0=[], UV1=[], I=[], n=0))
        b['P'].append(P.astype(np.float32)); b['UV0'].append(UV0.astype(np.float32))
        b['UV1'].append((UV1 if page >= 0 else np.zeros((len(used), 2))).astype(np.float32))
        b['I'].append(I2.astype(np.int64) + b['n']); b['n'] += len(used)
    # lightmap atlas pages
    lm_tex = [g.texture(encode(pg, False, 85)[0], 'image/jpeg', 33071, 33071, f'lightmap_atlas_{i}') for i, pg in enumerate(lms.pages)]
    ntri = nvert = 0
    for (diffuse, glow, flags, page), b in buckets.items():
        P = np.concatenate(b['P']); UV0 = np.concatenate(b['UV0']); UV1 = np.concatenate(b['UV1']); I = np.concatenate(b['I'])
        idx = I.astype(np.uint32 if len(P) > 65535 else np.uint16)
        # KHR_mesh_quantization: positions as normalized int16 around the group's centre (node scale/translation
        # undo it), lightmap UVs as normalized uint16
        c = (P.min(0) + P.max(0)) / 2; h = np.maximum((P.max(0) - P.min(0)) / 2, 1e-3)
        Q = np.zeros((len(P), 4), np.int16); Q[:, :3] = np.clip(np.round((P - c) / h * 32767), -32767, 32767)
        pa = dict(bufferView=g.view(Q.tobytes(), 34962), byteOffset=0, componentType=5122, normalized=True, count=len(Q), type='VEC3',
                  min=Q[:, :3].min(0).tolist(), max=Q[:, :3].max(0).tolist())
        g.j['bufferViews'][pa['bufferView']]['byteStride'] = 8
        g.j['accessors'].append(pa)
        attrs = dict(POSITION=len(g.j['accessors']) - 1, TEXCOORD_0=g.acc(np.ascontiguousarray(UV0), 'VEC2', 5126, 34962))
        if page >= 0:
            U = np.clip(np.round(UV1 * 65535), 0, 65535).astype(np.uint16)
            ua = dict(bufferView=g.view(U.tobytes(), 34962), componentType=5123, normalized=True, count=len(U), type='VEC2')
            g.j['accessors'].append(ua); attrs['TEXCOORD_1'] = len(g.j['accessors']) - 1
        mat = dict(name=(diffuse or 'untextured').replace('.sba', ''), pbrMetallicRoughness=dict(metallicFactor=0, roughnessFactor=1),
                   extras=dict(lightmapRange=LM_RANGE))
        d = texture(diffuse, tex_max)
        if d:
            mat['pbrMetallicRoughness']['baseColorTexture'] = dict(index=d[0])
            if d[1]: mat['alphaMode'] = 'MASK'; mat['alphaCutoff'] = 0.5
        else:
            mat['pbrMetallicRoughness']['baseColorFactor'] = [0.62, 0.62, 0.6, 1]
        if flags & 1: mat['doubleSided'] = True
        gl = texture(glow, min(256, tex_max)) if glow else None
        if gl: mat['emissiveTexture'] = dict(index=gl[0]); mat['emissiveFactor'] = [1, 1, 1]
        if page >= 0: mat['extras']['lightmap'] = lm_tex[page]
        g.j['materials'].append(mat)
        g.j['meshes'].append(dict(primitives=[dict(attributes=attrs, indices=g.acc(idx, 'SCALAR', 5125 if idx.dtype == np.uint32 else 5123, 34963),
                                                   material=len(g.j['materials']) - 1, mode=4)]))
        g.j['nodes'].append(dict(mesh=len(g.j['meshes']) - 1, translation=c.tolist(), scale=h.tolist()))
        g.j['scenes'][0]['nodes'].append(len(g.j['nodes']) - 1)
        ntri += len(idx) // 3; nvert += len(P)
    data = g.bytes()
    log(f'{scene_name}: {len(inst_ids)} instances -> {len(buckets)} draw groups, {nvert} verts, {ntri} tris, '
        f'{len(lms.pages)} lightmap page(s), lit={lit}, {len(data) / 1e6:.1f} MB' + (f', missing {sorted(missing)}' if missing else ''))
    return data, dict(lit=lit, tris=ntri, verts=nvert, pages=len(lms.pages), material_file=mat_file)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('scenes', nargs='*')
    ap.add_argument('--packs', default=os.path.join(HERE, '..', 'packs'))
    ap.add_argument('--out', default=os.path.join(HERE, '..', 'viewer', 'scene'))
    ap.add_argument('--tex-max', type=int, default=512)
    a = ap.parse_args()
    store = Store(a.packs)
    names = sorted(n for n in store.index if n.endswith('.scene_static.sba') and (not a.scenes or any(s in n for s in a.scenes)))
    os.makedirs(a.out, exist_ok=True)
    idx_path = os.path.join(a.out, 'index.js')
    index = {}
    if os.path.exists(idx_path):
        try: index = {e['stem']: e for e in json.loads(open(idx_path).read()[len('NFSNL.setScenes('):-3])}
        except Exception: index = {}
    for n in names:
        stem = n[:-len('.scene_static.sba')]
        m = re.search(r'region_?(\d+)', stem)
        res = export(store, n, a.tex_max)
        if res is None:
            print(f'{n}: no lightmaps file of its own (event overlay), skipped'); continue
        data, meta = res
        with open(os.path.join(a.out, stem + '.js'), 'w') as fh:
            fh.write('NFSNL.addScene(' + json.dumps(stem) + ',"')
            fh.write(base64.b64encode(data).decode('ascii'))
            fh.write('");\n')
        index[stem] = dict(stem=stem, region=int(m.group(1)) if m else None, mb=round(len(data) / 1e6, 1), **meta)
    with open(idx_path, 'w') as fh:
        fh.write('NFSNL.setScenes(' + json.dumps(sorted(index.values(), key=lambda e: (e['region'] or 99, e['stem'])), indent=1) + ');\n')
    print(f'{len(index)} scenes -> {a.out}')


if __name__ == '__main__':
    main()
