import struct
def chunks(d):
    assert d[:4]==b'SBIN'
    p=8; out={}
    while p<len(d):
        t=d[p:p+4].decode(); l=struct.unpack('<I',d[p+4:p+8])[0]
        out[t]=(p+12,d[p+12:p+12+l]); p=(p+12+l+3)&~3
    return out
