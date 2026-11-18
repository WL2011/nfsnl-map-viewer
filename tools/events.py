"""Limited-time (holiday) map versions of NFS No Limits.

Each event ships, in the packs tagged `prefabs`, two static scenes with their own baked lighting:
  <name>.variant.scene_static.sba   the whole base map re-lit for the event (same geometry as the base map)
  <name>.layer.scene_static.sba     the props the event adds (trees, lights, pumpkins, ...)
plus a `remap_LTS_*` pack whose textures replace the base textures of the same name while the event runs
(snowy ground, frosted trees, a different skydome, ...). The races are those of the base map.

key      viewer key (data/index.js entry and scene/<key>.js)
base     base map key whose track data the event reuses
parts    static scenes merged into one viewer scene
lit      lighting label for the viewer (sky colour + caption)
remap    remap tags whose textures win over the base ones
layer    top-level actor of <base>.scene.sb holding the event's props and effects (tools/export_fx.py)
"""

EVENTS = [
    dict(key='christmas_2022_region02_hopebridge', base='final_region02_hopebridge', event='Christmas 2022', zh='圣诞 2022',
         parts=['Christmas_2022.variant', 'Christmas_2022.layer'], layer='LTS_2022_Christmas', lit='twilight', remap=['LTS_CHRISTMAS_2022_HOPEBRIDGE']),
    dict(key='winter_2024_region02_hopebridge', base='final_region02_hopebridge', event='Winter 2024', zh='冬季 2024',
         parts=['Winter_2024.variant', 'Winter_2024.layer'], layer='winter_2024_lts', lit='night', remap=['LTS_WINTER_2024_HOPEBRIDGE']),
    dict(key='halloween_2024_region02_hopebridge', base='final_region02_hopebridge', event='Halloween 2024', zh='万圣节 2024',
         parts=['Halloween_2024.variant', 'Halloween_2024.layer'], layer='halloween_2024_hopebridge_lts', lit='night', remap=['LTS_HALLOWEEN_2024_HOPEBRIDGE']),
    dict(key='halloween_2023_region08_mountain', base='final_region08_mountain', event='Halloween 2023', zh='万圣节 2023',
         parts=['halloween_2023.variant', 'halloween_2023.layer'], layer='lts_2023_halloween', lit='bloodmoon', remap=['LTS_HALLOWEEN_2023_MOUNTAINS']),
    dict(key='halloween_2022_region09_desert', base='region09_desert', event='Halloween 2022', zh='万圣节 2022',
         parts=['Halloween_2022.variant', 'Halloween_2022.layer'], layer='LTS_2022_Halloween', lit='night', remap=['LTS_HALLOWEEN_2022_DESERT']),
    dict(key='christmas_2023_region10_rural', base='region10_rural', event='Christmas 2023', zh='圣诞 2023',
         parts=['christmas_2023.variant', 'christmas_2023.layer'], layer='lts_2023_christmas', lit='night', remap=['LTS_CHRISTMAS_2023_RURAL']),
]


def pack_remaps(packs_dir):
    """{pack id: set of remap tags} from packs/manifest.sb (empty set = base pack)."""
    import os, re
    from pull_packs import manifest_entries
    man = os.path.join(packs_dir, 'manifest.sb')
    if not os.path.exists(man): return {}
    return {e['id']: set(re.findall(r'(?:^|\s)remap_(\S+)', e['tags'])) for e in manifest_entries(man)}
