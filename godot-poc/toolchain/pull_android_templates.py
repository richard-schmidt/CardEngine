import urllib.request, struct, sys, os, zlib

URL = "https://github.com/godotengine/godot/releases/download/4.7-stable/Godot_v4.7-stable_export_templates.tpz"
DEST = os.path.expanduser(sys.argv[1])
WANT = ["templates/version.txt", "templates/android_debug.apk"]

def get_range(start, end):
    req = urllib.request.Request(URL)
    req.add_header('Range', f'bytes={start}-{end}')
    req.add_header('User-Agent','curl/8')
    return urllib.request.urlopen(req)

def size_of():
    req = urllib.request.Request(URL, method='HEAD')
    req.add_header('User-Agent','curl/8')
    with urllib.request.urlopen(req) as r:
        return int(r.headers['Content-Length'])

size = size_of()
tail = get_range(size-70000, size-1).read()
idx = tail.rfind(b'PK\x05\x06')
eocd = tail[idx:idx+22]
_, _, _, cd_count, _, cd_size, cd_off = struct.unpack('<IHHHHII', eocd[:20])
cd = get_range(cd_off, cd_off+cd_size-1).read()

entries = {}
p = 0
while p < len(cd) and cd[p:p+4] == b'PK\x01\x02':
    (_, _, _, _, method, _, _, crc, csize, usize,
     nlen, elen, clen, _, _, _, lho) = struct.unpack('<IHHHHHHIIIHHHHHII', cd[p:p+46])
    name = cd[p+46:p+46+nlen].decode('utf-8','replace')
    entries[name] = dict(method=method, csize=csize, usize=usize, lho=lho, crc=crc)
    p += 46 + nlen + elen + clen

os.makedirs(DEST, exist_ok=True)
for name in WANT:
    e = entries[name]
    # local header: fixed 30 bytes, then name+extra (lengths can differ from central dir)
    lh = get_range(e['lho'], e['lho']+29).read()
    if lh[:4] != b'PK\x03\x04':
        sys.exit(f"bad local header for {name}")
    lnlen, lelen = struct.unpack('<HH', lh[26:30])
    data_off = e['lho'] + 30 + lnlen + lelen
    out = os.path.join(DEST, os.path.basename(name))
    print(f"fetching {name} -> {out} ({e['csize']/1048576:.1f} MB compressed)")
    dec = zlib.decompressobj(-15) if e['method'] == 8 else None
    crc = 0
    got = 0
    with get_range(data_off, data_off+e['csize']-1) as r, open(out, 'wb') as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            got += len(chunk)
            raw = dec.decompress(chunk) if dec else chunk
            crc = zlib.crc32(raw, crc)
            f.write(raw)
            if got % (20 << 20) < (1 << 20):
                print(f"   {got/1048576:.0f}/{e['csize']/1048576:.0f} MB", flush=True)
        if dec:
            raw = dec.flush()
            crc = zlib.crc32(raw, crc)
            f.write(raw)
    actual = os.path.getsize(out)
    ok_size = actual == e['usize']
    ok_crc = crc == e['crc']
    print(f"   done: {actual} bytes  size_ok={ok_size}  crc_ok={ok_crc}")
    if not (ok_size and ok_crc):
        sys.exit(f"INTEGRITY FAILURE on {name}")
print("all templates verified")
