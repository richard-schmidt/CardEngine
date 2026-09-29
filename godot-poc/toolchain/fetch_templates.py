import urllib.request, struct, sys, os, zlib

URL = "https://github.com/godotengine/godot/releases/download/4.7-stable/Godot_v4.7-stable_export_templates.tpz"

def opener():
    o = urllib.request.build_opener()
    o.addheaders = [('User-Agent','curl/8')]
    return o

def get_range(start, end):
    req = urllib.request.Request(URL)
    req.add_header('Range', f'bytes={start}-{end}')
    req.add_header('User-Agent','curl/8')
    with urllib.request.urlopen(req) as r:
        return r.read()

def total_size():
    req = urllib.request.Request(URL, method='HEAD')
    req.add_header('User-Agent','curl/8')
    with urllib.request.urlopen(req) as r:
        return int(r.headers['Content-Length'])

size = total_size()
print(f"tpz total size: {size} bytes ({size/1048576:.1f} MB)")

tail = get_range(size-70000, size-1)
idx = tail.rfind(b'PK\x05\x06')
if idx < 0:
    sys.exit("no EOCD found")
eocd = tail[idx:idx+22]
_, _, _, cd_count, _, cd_size, cd_off = struct.unpack('<IHHHHII', eocd[:20])
print(f"central dir: {cd_count} entries, size={cd_size}, offset={cd_off}")

cd = get_range(cd_off, cd_off+cd_size-1)
entries = {}
p = 0
while p < len(cd):
    if cd[p:p+4] != b'PK\x01\x02':
        break
    (_, _, _, _, method, _, _, crc, csize, usize,
     nlen, elen, clen, _, _, _, lho) = struct.unpack('<IHHHHHHIIIHHHHHII', cd[p:p+46])
    name = cd[p+46:p+46+nlen].decode('utf-8', 'replace')
    entries[name] = dict(method=method, csize=csize, usize=usize, lho=lho, crc=crc)
    p += 46 + nlen + elen + clen

wanted = [n for n in entries if 'android' in n.lower() or n.endswith('version.txt')]
print("\nmatching entries:")
for n in sorted(wanted):
    e = entries[n]
    print(f"  {e['csize']/1048576:8.2f} MB compressed  {e['usize']/1048576:8.2f} MB raw  {n}")
