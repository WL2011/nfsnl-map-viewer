"""Export the particle effects of the limited-time maps (tools/events.py) for the viewer's race camera.

usage: export_fx.py [--packs DIR] [--out DIR] [--tex-max 256] [event-key-substring ...]
Writes <out>/<event key>.js: NFSNL.addFx(key, {defs, textures, instances}).

Where the effects come from:
  <base>.scene.sb, layer <event layer>   NFSModel actors (animated .m3g + .bin) and VFXInstanceComponent actors,
                                         with TransformComponent (local, chained through the actor tree)
  <model>.bin (IFF FORM: MSTR/HEDR/DATA/ROOT/STRT)   STRT holds "vfx_attach:<vfx name>:<node>"
  <vfx name>.sbfx (SBIN, pack tagged `vfx`)   EffectParticleTemplate: forces, clouds (emitter + process graph +
                                         quad renderer + material texture), parameter ramps
The .m3g meshes themselves are in an unknown compressed format, so effects are placed at the model origin and
models are not drawn. Parameter ramps are written as keys [time, min..., range...]: value = min + random * range."""
import sys, os, re, io, json, math, struct, base64, argparse
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from export_map import Store
from export_tracks import Scene
from export_web_scene import best_texture, texture_image
from sbtyped import TypedSB
from events import EVENTS, pack_remaps

HERE = os.path.dirname(os.path.abspath(__file__))
ALIASES = {'fx_env_leaves_falling2': 'fx_leaves_falling2', 'fx_env_cloud_hw23': 'fx_env_cloud_01'}
SKIP_MODELS = re.compile(r'^(train\d*|cable_car)\.m3g$')       # moving along an animation we cannot read


# ------------------------------------------------------------------ scene side
def bin_vfx(store, model_file):
    """vfx names a model's .bin attaches ("vfx_attach:<name>:<node>")."""
    raws = store.read(model_file[:-4] + '.bin') if model_file.endswith('.m3g') else []
    if not raws: return []
    out = []

    def walk(b):
        p = 0
        while p + 8 <= len(b):
            tag = b[p:p + 4]; n = struct.unpack('>I', b[p + 4:p + 8])[0]; body = b[p + 8:p + 8 + n]
            if tag == b'FORM': walk(body)
            elif tag == b'STRT':
                cnt = struct.unpack_from('<I', body, 0)[0]; q = 4
                for _ in range(cnt):
                    ln = struct.unpack_from('<I', body, q)[0]; sname = body[q + 4:q + 4 + ln].decode('latin1'); q += 4 + ln
                    if sname.startswith('vfx_attach:'): out.append(sname.split(':')[1])
            p += 8 + n
    walk(raws[0])
    return out


def euler(r):
    """EulerRotation (degrees) -> 3x3, yaw about Y, pitch about X, roll about Z (R = Ry Rx Rz)."""
    y, p, ro = (math.radians(r[k]) for k in ('yaw', 'pitch', 'roll'))
    cy, sy, cp, sp, cr, sr = math.cos(y), math.sin(y), math.cos(p), math.sin(p), math.cos(ro), math.sin(ro)
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]); Rx = np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]])
    Rz = np.array([[cr, -sr, 0], [sr, cr, 0], [0, 0, 1]])
    return Ry @ Rx @ Rz


def instances(store, sc, layer):
    """[(vfx name, 4x4 world matrix)] for the actors under top-level `layer`."""
    t = sc.t
    root = t.get(t.root, 0); scn = t.get(root['data'][1], 0)
    out = []
    stack = [(r, np.eye(4), False, 0) for r in reversed(sc.lst(scn['actors']))]
    while stack:
        ref, parent, under_model, depth = stack.pop()
        a = t.get(ref, 0)
        if not isinstance(a, dict) or a.get('_type') != 'actor': continue
        if depth == 0 and a['name'] != layer: continue
        comps = {}
        for c in sc.lst(a['components']):
            si, _ = t.raw_struct(c); comps.setdefault(t.structs[si][0], c)
        M = parent
        if 'TransformComponent' in comps:
            tr = t.get(comps['TransformComponent'], 2)
            L = np.eye(4); s = tr['LocalScale']
            L[:3, :3] = euler(tr['LocalRotation']) @ np.diag([s['x'], s['y'], s['z']])
            p = tr['LocalTranslation']; L[:3, 3] = [p['x'], p['y'], p['z']]
            M = parent @ L
        here = under_model
        if 'NFSModel' in comps:
            m = t.get(comps['NFSModel'], 2)
            fp = ((m.get('AnimatedM3GFile') or {}).get('Filepath') or (m.get('Model') or {}).get('Filepath') or '')
            fn = fp.split('/')[-1]
            if fn and not SKIP_MODELS.match(fn):
                for v in bin_vfx(store, fn): out.append((v, M))
            here = True
        if 'VFXInstanceComponent' in comps and not under_model and 'NFSModel' not in comps:
            v = t.get(comps['VFXInstanceComponent'], 1)['VfxId']
            out.append((re.sub(r'__\d+$', '', v), M))
        stack.extend((ch, M, here, depth + 1) for ch in reversed(sc.lst(a['children'])))
    return out


# ------------------------------------------------------------------ effect side
def rnd(x, nd=4):
    if isinstance(x, float): return 0.0 if abs(x) > 1e30 else round(x, nd)
    if isinstance(x, list): return [rnd(v, nd) for v in x]
    return x


def ramp(p):
    """Param*_Ramp -> {'t': kind, 'k': [[time, min, range], ...]} (min/range are numbers or 4-lists)."""
    ty = p['_type']; keys = []
    if ty not in ('ParamFloat_Ramp', 'ParamVector4_Ramp', 'ParamColor_Ramp'):
        return {'t': ty, 'raw': {k: rnd(v) for k, v in p.items() if k not in ('_type', 'label', 'name', 'public', 'hint')
                                 and isinstance(v, (int, float, list, str))}}
    for k in p.get('values') or []:
        if not isinstance(k, dict): continue
        v = k['value']
        if ty == 'ParamColor_Ramp': keys.append([rnd(k['time']), rnd(v['color0']), rnd(v['color1'])])
        else: keys.append([rnd(k['time']), rnd(v['min']), rnd(v['range'])])
    return {'t': {'ParamFloat_Ramp': 'f', 'ParamVector4_Ramp': 'v', 'ParamColor_Ramp': 'c'}[ty], 'k': keys}


def graph_nodes(g, acc):
    """Every node dict reachable from a ProcessGraphTemplate."""
    def visit(n):
        if not isinstance(n, dict) or id(n) in acc: return
        acc[id(n)] = n
        for e in n.get('inputEdges') or []:
            if isinstance(e, dict): visit(e.get('sourceNode'))
    for r in (g or {}).get('roots') or []: visit(r)
    return acc


def convert(t):
    e = t.get(t.find_root('EffectParticleTemplate'), 40)
    forces = []
    for f in e.get('entities') or []:
        if isinstance(f, dict) and f.get('_type', '').startswith('ForceTemplate'):
            forces.append({'name': f.get('name'), 'p': {p['name']: ramp(p) for p in f.get('params') if isinstance(p, dict)}})
    clouds = []
    for c in e.get('clouds') or []:
        if not isinstance(c, dict): continue
        nodes = graph_nodes(c.get('processGraph'), {})
        kinds = sorted({n.get('_type') for n in nodes.values()})
        life = {}
        for n in nodes.values():
            if n.get('_type') == 'ProcessNodeData_Param' and isinstance(n.get('param'), dict):
                nm = n.get('param')['name'].replace(' ', '')
                if nm.startswith('life:'): life[nm[5:]] = ramp(n.get('param'))
        emitters = []
        for em in c.get('emitters') or []:
            if not isinstance(em, dict): continue
            births = {}
            for d in em.get('streamDefaults') or []:
                if d.get('_type') == 'StreamDefaultTemplate_FloatParam' and isinstance(d.get('param'), dict):
                    births[d.get('stream')] = ramp(d.get('param'))
                elif d.get('_type') == 'StreamDefaultTemplate_FloatConst':
                    births[d.get('stream')] = {'t': 'f', 'k': [[0, rnd(d.get('value')), 0]]}
            emitters.append({'shape': em.get('shape'), 'dir': em.get('emissionDirection'), 'prob': rnd(em.get('spawnProbability', 1.0)),
                             'cycles': em.get('cycleCount'), 'p': {p['name']: ramp(p) for p in em.get('params') if isinstance(p, dict)},
                             'birth': births})
        rends = []
        for r in c.get('renderers') or []:
            if not isinstance(r, dict) or r.get('_type') != 'RendererQuad': continue
            rs = (r.get('material') or {}).get('renderstate') or {}
            texs = [p.split('/')[-1] for p in (r.get('material') or {}).get('texturePaths') or [] if isinstance(p, str)]
            fb = next((g for g in r.get('textureGenerators') or [] if isinstance(g, dict) and g['_type'] == 'TextureGenFlipbook'), None)
            rnodes = graph_nodes(r.get('processGraph'), {})
            for n in rnodes.values():
                if n.get('_type') == 'ProcessNodeData_Param' and isinstance(n.get('param'), dict):
                    nm = n.get('param')['name'].replace(' ', '')
                    if nm.startswith('life:'): life.setdefault(nm[5:], ramp(n.get('param')))
            rends.append({'half': rnd(r.get('halfSize')), 'mode': r.get('quadMode'), 'sort': r.get('sortMode'), 'pivot': rnd(r.get('rotationPoint')),
                          'pull': rnd(r.get('pullToCamera')), 'velMul': rnd(r.get('velocityMultiplier')), 'velClamp': rnd(r.get('velocityClamp')),
                          'velX': r.get('velocityScalingX'), 'velY': r.get('velocityScalingY'), 'orient': r.get('inheritOrientation'),
                          'blend': 'add' if rs.get('destBlend') == 1 else 'alpha', 'tex': texs[0] if texs else None,
                          'flip': {'u': fb.get('uTiles'), 'v': fb.get('vTiles'), 'n': rnd(fb.get('totalTiles')), 'lerp': fb.get('interpolated'),
                                   'seq': fb.get('sequence')} if fb else None,
                          'p': {p['name']: ramp(p) for p in r.get('params') or [] if isinstance(p, dict) and p.get('_type', '').startswith('Param')}})
        pt = c.get('particleType') or {}
        clouds.append({'name': c.get('name'), 'max': c.get('maxParticleCount'), 'lifetime': rnd(c.get('Lifetime')), 'delay': rnd(c.get('DelayTime')),
                       'loop': c.get('LoopCount'), 'off': c.get('StartDisabled'), 'age': [rnd(pt.get('ageMin', 1.0)), rnd(pt.get('ageRange', 0.0))],
                       'nodes': [k.replace('ProcessNodeData_', '') for k in kinds], 'emitters': emitters, 'renderers': rends, 'life': life})
    return {'preRoll': rnd(e.get('preRoll')), 'total': rnd(e.get('totalTime')), 'speed': rnd(e.get('timeMultiplier')),
            'cycles': e.get('cycleCount', 0), 'forces': forces, 'clouds': clouds}


def load_fx(store, name):
    name = ALIASES.get(name, name)
    for n in (name, re.sub(r'_settings$', '', name), 'fx_' + name):
        cands = store.index.get(n + '.sbfx', [])
        if cands:
            pid, f = max(cands, key=lambda c: c[1]['length'])
            return n, TypedSB(store.read_entry(pid, f))
    return None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('events', nargs='*')
    ap.add_argument('--packs', default=os.path.join(HERE, '..', 'packs'))
    ap.add_argument('--out', default=os.path.join(HERE, '..', 'viewer', 'fx'))
    ap.add_argument('--tex-max', type=int, default=256)
    ap.add_argument('--dump', help='print the converted definition of one .sbfx and exit')
    a = ap.parse_args()
    store = Store(a.packs)
    if a.dump:
        n, t = load_fx(store, a.dump); print(json.dumps(convert(t), indent=1)); return
    remaps = pack_remaps(a.packs)
    os.makedirs(a.out, exist_ok=True)
    for ev in EVENTS:
        if a.events and not any(s in ev['key'] for s in a.events): continue
        sc = Scene(store.read(ev['base'] + '.scene.sb')[0])
        defs, insts, texs, missing = {}, [], {}, set()
        for name, M in instances(store, sc, ev['layer']):
            if name not in defs:
                real, t = load_fx(store, name)
                defs[name] = convert(t) if t else None
                if t is None: missing.add(name)
            if defs[name] is None: continue
            # position, 3x3 rotation*scale (column-major for THREE.Matrix3), all rounded
            insts.append([name, rnd(M[:3, 3].tolist(), 2), rnd(M[:3, :3].T.ravel().tolist(), 4)])
        defs = {k: v for k, v in defs.items() if v}
        for d in defs.values():
            for c in d['clouds']:
                for r in c['renderers']:
                    if r['tex'] and r['tex'] not in texs:
                        bt = best_texture(store, r['tex'], remaps, ev['remap'])
                        img = texture_image(bt[1], bt[2], a.tex_max) if bt else None
                        if img is None: texs[r['tex']] = None; missing.add(r['tex']); continue
                        b = io.BytesIO(); img.convert('RGBA').save(b, 'PNG', optimize=True)
                        texs[r['tex']] = 'data:image/png;base64,' + base64.b64encode(b.getvalue()).decode()
        body = json.dumps({'defs': defs, 'textures': {k: v for k, v in texs.items() if v}, 'instances': insts}, separators=(',', ':'))
        with open(os.path.join(a.out, ev['key'] + '.js'), 'w') as fh:
            fh.write(f'NFSNL.addFx({json.dumps(ev["key"])},{body});\n')
        print(f"{ev['key']}: {len(insts)} effect instances, {len(defs)} effects, {len(texs)} textures, {len(body) / 1024:.0f} KB"
              + (f', missing {sorted(missing)}' if missing else ''))


if __name__ == '__main__':
    main()
