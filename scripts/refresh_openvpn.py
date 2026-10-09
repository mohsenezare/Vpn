"""Mirror public VPN Gate volunteer profiles from authenticated HTTPS sources.

The upstream community mirror's index supplies a commit SHA + content digest.
Never replace a valid cache with an empty, invalid, or stale response.
"""
import base64, csv, datetime, hashlib, io, json, pathlib, re, time, urllib.request

ROOT=pathlib.Path("feeds"); ROOT.mkdir(exist_ok=True)
TARGET=ROOT/"openvpn.json"
OFFICIAL="https://www.vpngate.net/api/iphone/"
INDEX="https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/main/latest.json"
MIRROR="https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror"
NOW=int(time.time()*1000)

def get(url,limit=13_000_000):
    req=urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0 (Aegis VPN directory updater)"})
    with urllib.request.urlopen(req,timeout=25) as response:
        data=response.read(limit+1)
    if len(data)>limit: raise ValueError("download exceeded size limit")
    return data

def directory_nodes(raw):
    text=raw.decode("utf-8-sig",errors="replace")
    if "#HostName" not in text or "OpenVPN_ConfigData_Base64" not in text:
        raise ValueError("directory is not a VPN Gate CSV")
    nodes=[];seen=set()
    for row in csv.reader(io.StringIO(text)):
        if len(row)!=15 or row[0].startswith(("*","#")):continue
        host=row[1].strip()
        if not re.fullmatch(r"[0-9a-fA-F.:]{7,48}",host) or host in seen:continue
        try:
            config=base64.b64decode(row[14],validate=True).decode("utf-8")
            if not (80<len(config)<75000 and
                    re.search(r"(?m)^client\s*$",config) and re.search(r"(?m)^remote\s+\S+",config)):continue
            if re.search(r"(?im)^\s*(script-security|up|down|plugin|client-connect|client-disconnect)\s+",config):continue
            ping=int(row[3]) if row[3].isdigit() else 0
            nodes.append({"host":host,"country":row[5].strip()[:45],"ping":ping,"ovpn":config})
            seen.add(host)
        except (ValueError,UnicodeError,IndexError):continue
        if len(nodes)>=80:break
    if not nodes:raise ValueError("no valid OpenVPN relays in origin CSV")
    return sorted(nodes,key=lambda n:n["ping"] if n["ping"]>0 else 999999)

def mirror_csv():
    meta=json.loads(get(INDEX,65536))
    if meta.get("schema_version")!=1:raise ValueError("unsupported mirror schema")
    stamp=datetime.datetime.fromisoformat(meta["fetched_at"].replace("Z","+00:00")).timestamp()
    if abs(time.time()-stamp)>24*3600:raise ValueError("mirror is older than 24 hours")
    sha=meta["data_commit"]
    if not re.fullmatch(r"[0-9a-f]{40}",sha):raise ValueError("bad mirror commit")
    file=meta["files"]["data/vpngate.csv"]
    raw=get(f"{MIRROR}/{sha}/data/vpngate.csv")
    if len(raw)!=file["bytes"] or hashlib.sha256(raw).hexdigest()!=file["sha256"]:
        raise ValueError("mirror CSV failed SHA256 and length verification")
    return raw

raw=None
try:
    raw=get(OFFICIAL)
    nodes=directory_nodes(raw)
    source="VPN Gate original"
except Exception as upstream:
    print("VPN Gate direct unavailable; trying verified GitHub CSV snapshot:",upstream)
    try:
        raw=mirror_csv()
        nodes=directory_nodes(raw)
        source="verified public mirror"
    except Exception as other:
        print("All VPN Gate sources failed, preserving last valid snapshot:",other)
        nodes=None

if nodes:
    TARGET.write_text(json.dumps({"updated":NOW,"nodes":nodes},separators=(",",":")),encoding="utf-8")
    print("Published",len(nodes),"public OpenVPN nodes from",source)
