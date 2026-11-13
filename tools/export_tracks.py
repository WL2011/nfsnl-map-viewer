"""Extract track layouts (splines, road edges, race checkpoints) from NFS No Limits *.scene.sb files for the viewer.

usage: export_tracks.py [--packs DIR] [--out DIR] [--with-events] [scene-substring ...]
Only the base maps are exported unless --with-events is given.
Writes <out>/index.js (map list) and <out>/<key>.js (one map each, loaded on demand by viewer/index.html).

scene.sb layout used here (plain SBIN, see sbtyped.py):
  track_info/SplineLayer/<track>/track_spline    TrackSplineCollection -> centre TrackSpline + TrackRib road widths
  track_info/SplineLayer/<track>/race_spline     4 race lanes (racespline_00..03)
  track_info/SplineLayer/<track>/traffic_spline  traffic lanes (pieces of the route)
  TrackLayers/<track>/Races/<raceNN_forward|reverse>/{start_race,finish_race,end_race}   Checkpoint actors
TrackSpline.Data = TrackSplineData(Position, Tangent, Distance, CornerRadius); Y is up, metres."""
import sys, os, re, json, math, argparse, hashlib
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from export_map import Store
from sbtyped import TypedSB

HERE = os.path.dirname(os.path.abspath(__file__))
PLACES = {'cornerstone': 'Cornerstone', 'hopebridge': 'Hopebridge', 'thesquare': 'The Square', 'eastside': 'Eastside',
          'beachfront': 'Beachfront', 'rugged_coast': 'Rugged Coast', 'mountain': 'Mountain', 'desert': 'Desert',
          'rural': 'Rural', 'airport': 'Airport', 'speedway': 'Speedway'}


class Scene:
    def __init__(self, raw):
        self.t = TypedSB(raw)

    def lst(self, v):
        t = self.t
        if v is None: return []
        if isinstance(v, tuple) and v[0] == 'ref': v = t.get(v[1], 0)
        return [x[1] for x in v if isinstance(x, tuple) and x[0] == 'ref'] if isinstance(v, list) else []

    def actors(self):
        """Yield (path tuple, {component type: component}) for every actor."""
        t = self.t
        root = t.get(t.root, 0)
        sc = t.get(root['data'][1], 0)
        stack = [(r, ()) for r in reversed(self.lst(sc['actors']))]
        while stack:
            ref, path = stack.pop()
            a = t.get(ref, 0)
            if not isinstance(a, dict) or a.get('_type') != 'actor': continue
            p = path + (a['name'] or '?',)
            comps = {}
            for c in self.lst(a['components']):
                si, _ = t.raw_struct(c)
                comps.setdefault(t.structs[si][0], c)
            yield p, comps
            stack.extend((ch, p) for ch in reversed(self.lst(a['children'])))

    def spline(self, ref):
        s = self.t.get(ref, 3)
        P = np.array([[d['Position']['X'], d['Position']['Y'], d['Position']['Z']] for d in s['Data']], dtype=np.float64)
        T = np.array([[d['Tangent']['X'], d['Tangent']['Y'], d['Tangent']['Z']] for d in s['Data']], dtype=np.float64)
        D = np.array([d['Distance'] for d in s['Data']], dtype=np.float64)
        R = np.array([d['CornerRadius'] for d in s['Data']], dtype=np.float64)
        ribs = [(r['RoadMin'], r['RoadMax']) for r in s['Rib']] if isinstance(s['Rib'], list) else []
        return dict(name=s['Name'], length=s['Length'], P=P, T=T, D=D, R=R, ribs=ribs, rib_step=s['RibIncrement'],
                    shortcut=bool(s['IsShortcut']))


def project(P, q):
    """Distance along polyline P (cumulative) of the point nearest to q, and that distance to the line."""
    a = P[:-1]; b = P[1:]; ab = b - a
    L = np.maximum((ab ** 2).sum(1), 1e-12)
    u = np.clip(((q - a) * ab).sum(1) / L, 0, 1)
    c = a + ab * u[:, None]
    dd = np.linalg.norm(c - q, axis=1); k = int(np.argmin(dd))
    seg = np.r_[0, np.cumsum(np.linalg.norm(ab, axis=1))]
    return float(seg[k] + u[k] * math.sqrt(L[k])), float(dd[k])


def road_edges(center, lanes):
    """Left/right road edge polylines from the centre spline's ribs (one every rib_step metres)."""
    if not center['ribs']: return None
    P, T, D = center['P'], center['T'], center['D']
    d = np.arange(len(center['ribs'])) * center['rib_step']
    d = np.clip(d, D[0], D[-1])
    pos = np.stack([np.interp(d, D, P[:, k]) for k in range(3)], 1)
    tan = np.stack([np.interp(d, D, T[:, k]) for k in range(3)], 1)
    side = np.stack([tan[:, 2], np.zeros(len(d)), -tan[:, 0]], 1)
    side /= np.maximum(np.linalg.norm(side, axis=1, keepdims=True), 1e-9)
    mn = np.array([r[0] for r in center['ribs']]); mx = np.array([r[1] for r in center['ribs']])
    # pick the side sign that puts the race lanes inside [RoadMin, RoadMax]
    if lanes:
        Q = lanes[0]['P'][::max(1, len(lanes[0]['P']) // 200)]
        score = 0
        for q in Q:
            k = int(np.argmin(np.linalg.norm(pos - q, axis=1)))
            off = float((q - pos[k]) @ side[k])
            score += int(mn[k] <= off <= mx[k]) - int(mn[k] <= -off <= mx[k])
        if score < 0: side = -side
    return pos + side * mn[:, None], pos + side * mx[:, None], mx - mn


def r1(a, nd=1):
    return np.round(np.asarray(a, dtype=np.float64), nd).tolist()


def thin(P, step):
    """Keep every `step`-th point plus the last one."""
    idx = list(range(0, len(P), step))
    if idx[-1] != len(P) - 1: idx.append(len(P) - 1)
    return P[idx]


def describe(key):
    m = re.search(r'region_?(\d+)_(.+)$', key)
    region, place = (int(m.group(1)), PLACES.get(m.group(2), m.group(2).replace('_', ' ').title())) if m else (None, key)
    prefix = key[:m.start()].rstrip('_') if m else ''
    prefix = re.sub(r'(^|_)final$', '', prefix)
    event = ' '.join(w if w.isdigit() else w.capitalize() for w in prefix.split('_') if w) if prefix else ''
    title = f'Region {region:02d} · {place}' if region is not None else place
    return dict(region=region, place=place, event=event, title=title)


def export(store, scene_name):
    sc = Scene(store.read(scene_name)[0])
    colls = {}; cps = {}
    for path, comps in sc.actors():
        if 'TrackSplineCollection' in comps and len(path) >= 4 and path[1] == 'SplineLayer':
            c = sc.t.get(comps['TrackSplineCollection'], 0)
            colls[(path[2], path[3])] = sc.lst(c['Splines'])
        if 'Checkpoint' in comps and 'TransformComponent' in comps:
            tr = sc.t.get(comps['TransformComponent'], 2); cp = sc.t.get(comps['Checkpoint'], 2)
            p = tr['LocalTranslation']
            track = path[1] if len(path) > 1 and path[0] == 'TrackLayers' else '?'
            race = path[-2] if len(path) >= 2 else '?'
            cps.setdefault((track, race), {})[path[-1]] = dict(
                p=[p['x'], p['y'], p['z']], yaw=tr['LocalRotation']['yaw'],
                bonus=(cp['TimeBonus'] or {}).get('TotalSeconds') if isinstance(cp['TimeBonus'], dict) else None)
    tracks = []
    for tname in sorted({k[0] for k in colls}):
        race_refs = colls.get((tname, 'race_spline'), [])
        if not race_refs: continue
        lanes = [sc.spline(r) for r in race_refs]
        center = next((sc.spline(r) for r in colls.get((tname, 'track_spline'), [])), None)
        traffic = [sc.spline(r) for r in colls.get((tname, 'traffic_spline'), [])]
        L0 = lanes[0]['P']
        loop = float(np.linalg.norm(L0[0] - L0[-1])) < 100.0
        edges = road_edges(center, lanes) if center else None
        races = []
        for (trk, race), pts in sorted(cps.items()):
            if trk.lower() != tname.lower(): continue
            r = dict(name=race, reverse='reverse' in race.lower())
            for kind in ('start', 'finish', 'end'):
                hit = next((v for k, v in pts.items() if k.lower().startswith(kind)), None)
                if hit:
                    d, off = project(L0, np.array(hit['p']))
                    r[kind] = dict(p=r1(hit['p']), yaw=round(hit['yaw'], 1), d=round(d, 1), off=round(off, 1))
                    if hit.get('bonus') is not None: r['bonus'] = hit['bonus']
            if 'start' in r and 'finish' in r:
                r['length'] = round(abs(r['finish']['d'] - r['start']['d']), 1)
            races.append(r)
        cum = np.r_[0, np.cumsum(np.linalg.norm(np.diff(L0, axis=0), axis=1))]
        all_splines = lanes + traffic + ([center] if center else [])
        stats = dict(splines=len(all_splines), points=int(sum(len(s['P']) for s in all_splines)),
                     checkpoints=sum(k in r for r in races for k in ('start', 'finish', 'end')))
        trk = dict(name=tname, length=round(lanes[0]['length'], 1), loop=loop, stats=stats,
                   lane0=dict(p=r1(L0), d=r1(cum), radius=r1(np.interp(cum, lanes[0]['D'], lanes[0]['R']), 0)),
                   lanes=[r1(thin(l['P'], 2)) for l in lanes[1:]],
                   traffic=[r1(thin(s['P'], 3)) for s in traffic if len(s['P']) > 1],
                   races=races)
        if center is not None:
            trk['center'] = dict(name=center['name'], length=round(center['length'], 1))
        if edges is not None:
            left, right, width = edges
            trk['edges'] = dict(left=r1(left), right=r1(right), width=r1(width))
        tracks.append(trk)
    return tracks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('scenes', nargs='*')
    ap.add_argument('--packs', default=os.path.join(HERE, '..', 'packs'))
    ap.add_argument('--out', default=os.path.join(HERE, '..', 'viewer', 'data'))
    ap.add_argument('--with-events', action='store_true', help='also export event variants (Lunar, Shadowfall, ...)')
    a = ap.parse_args()
    store = Store(a.packs)
    names = sorted(n for n in store.index if n.endswith('.scene.sb') and (not a.scenes or any(s in n for s in a.scenes)))
    os.makedirs(a.out, exist_ok=True)
    index = []; seen = {}
    for n in names:
        key = n[:-len('.scene.sb')]
        try:
            tracks = export(store, n)
        except Exception as e:
            print(f'{n}: FAILED {type(e).__name__}: {e}'); continue
        if not tracks:
            print(f'{n}: no race splines, skipped'); continue
        info = describe(key)
        if info['event'] and not a.with_events:
            print(f'{n}: event variant ({info["event"]}), skipped'); continue
        payload = dict(key=key, scene=n, **info, tracks=tracks)
        body = json.dumps(payload, separators=(',', ':'))
        h = hashlib.sha1(json.dumps(tracks, separators=(',', ':')).encode()).hexdigest()
        if h in seen and describe(seen[h])['event'] == info['event']:
            print(f'{n}: identical to {seen[h]}, skipped'); continue
        seen[h] = key
        with open(os.path.join(a.out, key + '.js'), 'w') as fh:
            fh.write('NFSNL.add(' + body + ');\n')
        all_y = [p[1] for t in tracks for p in t['lane0']['p']]
        index.append(dict(key=key, scene=n, **info, tracks=[dict(name=t['name'], length=t['length'], loop=t['loop'],
                                                                 races=len(t['races']), **t['stats']) for t in tracks],
                          ymin=round(min(all_y), 1), ymax=round(max(all_y), 1), kb=round(len(body) / 1024)))
        print(f'{n}: {len(tracks)} track(s), {sum(len(t["races"]) for t in tracks)} races, {len(body) / 1024:.0f} KB')
    index.sort(key=lambda m: (m['region'] if m['region'] is not None else 99, m['event'] != '', m['event'], m['key']))
    with open(os.path.join(a.out, 'index.js'), 'w') as fh:
        fh.write('NFSNL.setIndex(' + json.dumps(index, ensure_ascii=False, indent=1) + ');\n')
    print(f'{len(index)} maps -> {a.out}')


if __name__ == '__main__':
    main()
