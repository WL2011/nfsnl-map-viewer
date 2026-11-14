#!/usr/bin/env python3
"""viewer/data/*.js -> minimap/assets/maps.json (compact track lines + race spans for the NFSNL Minimap app).

Each track keeps race lane 0 resampled every STEP metres along its own distance, as integer decimetres
(x, z interleaved). Races keep the lane-0 distances of their start and finish lines, which is what the
in-game PROGRESS percentage is mapped onto.
"""
import json, glob, os, re, sys
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STEP = 4.0


def load(path):
    s = open(path, encoding='utf-8').read()
    return json.loads(s[s.index('(') + 1:s.rindex(')')])


def label(name):
    m = re.match(r'race_?0*(\d+)_?(.*)$', name, re.I)
    if not m:
        return name
    rest = m.group(2).lower()
    rest = {'forward': '', 'reverse': '', '': ''}.get(rest, rest)
    tag = {'long': '长', 'short': '短', 'pursuit': '追逐', 'appledemo': '演示'}.get(rest, rest)
    return f"R{int(m.group(1)):02d}" + (f" {tag}" if tag else '')


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, 'minimap', 'assets', 'maps.json')
    index = load(os.path.join(ROOT, 'viewer', 'data', 'index.js'))
    maps = []
    for m in index:
        if m.get('event'):
            continue
        j = load(os.path.join(ROOT, 'viewer', 'data', m['key'] + '.js'))
        tracks = []
        for t in j['tracks']:
            P = np.array(t['lane0']['p'], float)
            D = np.array(t['lane0']['d'], float)
            keep = np.r_[True, np.diff(D) > 1e-6]
            P, D = P[keep], D[keep]
            s = np.arange(0, D[-1] + 1e-9, STEP)
            x = np.interp(s, D, P[:, 0]); z = np.interp(s, D, P[:, 2])
            xz = np.round(np.c_[x, z] * 10).astype(int).ravel().tolist()
            w = t.get('edges', {}).get('width') or [14]
            races = []
            for r in t['races']:
                if 'start' not in r or 'finish' not in r or 'tutorial' in r['name'].lower():
                    continue
                races.append({'id': r['name'], 'label': label(r['name']), 'rev': bool(r['reverse']),
                              's0': round(r['start']['d'], 1), 's1': round(r['finish']['d'], 1),
                              'len': round(r.get('length') or abs(r['finish']['d'] - r['start']['d']), 1)})
            races.sort(key=lambda r: (r['label'], r['rev']))
            tracks.append({'name': t['name'], 'len': round(float(D[-1]), 1), 'loop': bool(t['loop']),
                           'w': round(float(np.median(w)), 1), 'step': STEP, 'xz': xz, 'races': races})
        maps.append({'key': m['key'], 'region': m['region'], 'place': m['place'], 'tracks': tracks})
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump({'v': 1, 'maps': maps}, f, ensure_ascii=False, separators=(',', ':'))
    n = sum(len(t['races']) for m in maps for t in m['tracks'])
    print(f"{out_path}: {len(maps)} maps, {n} races, {os.path.getsize(out_path) // 1024} KB")


if __name__ == '__main__':
    main()
