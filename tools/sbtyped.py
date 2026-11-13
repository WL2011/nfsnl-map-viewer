"""Generic reader for self-describing SBIN v4 files (ENUM/STRU/FIEL schema + OHDR object table + DATA).

STRU: 6-byte records (name, first_field, field_count).  FIEL: 8-byte records (name, type, offset, ref).
OHDR: u32[0] = root object index, then (data_offset << 3 | kind) per object; kind 0 = struct (u16 struct index +
fields), kind 2 = list (u32 element type, u32 count, elements).  References are u32 OHDR indices, 0xffffffff = null.
Field types seen so far: 5 u32, 9 bool (1 byte), 10 f32, 13 string (u16 index into CHDR/CDAT), 15 object ref, 16 inline struct
(ref = struct index), 17 list ref, 18 enum (u32), 22 blob (u32 index into BULK/BARG)."""
import struct
import zstandard
from sbin import chunks

SIZES = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 8, 7: 8, 9: 1, 10: 4, 11: 8, 12: 1, 13: 2, 15: 4, 17: 4, 18: 4, 22: 4}
SCALAR = {0: '<b', 1: '<B', 2: '<h', 3: '<H', 4: '<i', 5: '<I', 6: '<q', 7: '<Q', 9: '<?', 10: '<f', 11: '<d', 12: '<B',
          19: '<I'}                                          # 19 = bit flags (u32)
NULL = 0xffffffff


class TypedSB:
    def __init__(self, raw):
        self.raw = raw
        c = self.c = chunks(raw)
        cd = c['CDAT'][1]; ch = c['CHDR'][1]
        self.S = [cd[o:o + l].decode('utf8', 'replace') for o, l in (struct.unpack('<II', ch[i:i + 8]) for i in range(0, len(ch), 8))]
        st = c['STRU'][1]; fi = c['FIEL'][1]
        self.fields = [struct.unpack_from('<4H', fi, i) for i in range(0, len(fi), 8)]
        self.structs = []
        for i in range(0, len(st), 6):
            name, first, n = struct.unpack_from('<3H', st, i)
            self.structs.append((self.S[name], [(self.S[f[0]], f[1], f[2], f[3]) for f in self.fields[first:first + n]]))
        self.D = c['DATA'][1]
        oh = c['OHDR'][1]
        OH = struct.unpack('<%dI' % (len(oh) // 4), oh)
        self.root = OH[0]
        self.objs = [None] + [(v >> 3, v & 7) for v in OH[1:]]        # 1-based like the references
        self._blobs = None
        self._cache = {}

    # ---- schema helpers
    def struct_index(self, name):
        return next(i for i, (n, _) in enumerate(self.structs) if n == name)

    def describe(self):
        return {n: [(f, t, o, r) for f, t, o, r in fl] for n, fl in self.structs}

    # ---- blobs (BULK index -> BARG payload). Most payloads sit behind a 16-byte header
    # (00000000 ffffffff 0 0 = raw, 02000000 <usize> .. + zstd frame); some (e.g. older textures) have none.
    def blob(self, i):
        c = self.c; bulk = c['BULK'][1]; barg = c['BARG'][1]
        o, s = struct.unpack_from('<II', bulk, 8 * i)
        b = barg[o:o + s]
        if b[:4] == b'\x02\x00\x00\x00' and b[16:20] == b'\x28\xb5\x2f\xfd':
            usz = struct.unpack_from('<I', b, 4)[0]
            return zstandard.ZstdDecompressor().decompress(b[16:], max_output_size=usz)
        if b[:8] == b'\x00\x00\x00\x00\xff\xff\xff\xff':
            return b[16:]
        return b

    # ---- object decoding
    def _field(self, t, off, ref, deep):
        D = self.D
        if t in SCALAR:
            return struct.unpack_from(SCALAR[t], D, off)[0]
        if t == 13:
            return self.S[struct.unpack_from('<H', D, off)[0]]
        if t == 16:
            return self._struct(ref, off, deep)
        if t == 18:
            return struct.unpack_from('<I', D, off)[0]
        if t == 22:
            return ('blob', struct.unpack_from('<I', D, off)[0])
        if t in (15, 17):
            r = struct.unpack_from('<I', D, off)[0]
            if r == NULL or r == 0:
                return None
            return self.get(r, deep - 1) if deep > 0 else ('ref', r)
        return ('?', t, D[off:off + 4].hex())

    def _struct(self, si, base, deep):
        name, fl = self.structs[si]
        out = {'_type': name}
        for fn, t, o, r in fl:
            out[fn] = self._field(t, base + o, r, deep)
        return out

    def get(self, idx, deep=1):
        """Decode object `idx`. deep>0 follows references (deep levels), otherwise leaves ('ref', n)."""
        key = (idx, deep)
        if key in self._cache:
            return self._cache[key]
        off, kind = self.objs[idx]
        D = self.D
        if kind == 0:
            si = struct.unpack_from('<H', D, off)[0]
            v = self._struct(si, off + 2, deep)
        elif kind == 2:
            et, n = struct.unpack_from('<II', D, off)
            if off + 8 + n * SIZES.get(et, 1) > len(D):
                v = ('list', et, n, off + 8)              # element type not understood: leave raw
            elif et in (15, 17):
                refs = struct.unpack_from('<%dI' % n, D, off + 8)
                v = [self.get(r, deep - 1) if deep > 0 and r not in (0, NULL) else ('ref', r) for r in refs]
            elif et in SCALAR:
                sz = SIZES[et]
                v = [struct.unpack_from(SCALAR[et], D, off + 8 + sz * k)[0] for k in range(n)]
            elif et == 13:
                v = [self.S[x] for x in struct.unpack_from('<%dH' % n, D, off + 8)]
            else:
                v = ('list', et, n, off + 8)
        else:
            v = ('kind', kind, off)
        self._cache[key] = v
        return v

    def raw_struct(self, idx):
        """(struct index, data offset of first field) for a kind-0 object."""
        off, kind = self.objs[idx]
        assert kind == 0
        return struct.unpack_from('<H', self.D, off)[0], off + 2
