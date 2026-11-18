"""Minimal stand-in for the `zstandard` package on Python 3.14+, backed by the standard library's compression.zstd.
Only what these tools use: ZstdDecompressor().decompress(data, max_output_size=...) and ZstdCompressor(level).compress()."""
from compression import zstd as _z


class _Obj:
    def __init__(self):
        self.d = _z.ZstdDecompressor()

    def decompress(self, data):
        return self.d.decompress(data)


class ZstdDecompressor:
    def decompress(self, data, max_output_size=0):
        return _z.decompress(data)

    def decompressobj(self):
        return _Obj()


class ZstdCompressor:
    def __init__(self, level=3, **_):
        self.level = level

    def compress(self, data):
        return _z.compress(data, level=self.level)
