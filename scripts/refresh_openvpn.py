"""Refresh VPN Gate volunteer profiles outside networks blocking the origin.

Only public *.ovpn files are saved; never store private subscription credentials.
Keep last good snapshot on any error and indicate source health in status JSON.
"""
import base64, csv, io, json, pathlib, re, time, urllib.request
ROOT=pathlib.Path("feeds");ROOT.mkdir(exist_ok=True)
TARGET=ROOT/"openvpn.json"
URL="https://www.vpngate.net/api/iphone/"
start=int(time.time()*1000)
try:
    req=urllib.request.Request(URL,headers={"User-Agent":"AegisVPN-Public-Mirror/1.0"})
    with urllib.request.urlopen(req,timeout=25) as response:
        raw=response.read(12_000_001)
    if len(raw)>12_000_000:raise ValueError("VPN Gate directory exceeded 12MB")
    nodes=[];seen=set()
    for row in csv.reader(io.StringIO(raw.decode("utf-8-sig",errors="replace"))):
        if len(row)!=15 or row[0].startswith(("*","#")):continue
        host=row[1].strip()
        if not re.fullmatch(r"[0-9a-fA-F.:]{7,48}",host) or host in seen:continue
        try:
            config=base64.b64decode(row[14],validate=True).decode("utf-8")
            if not (80<len(config)<75000 and re.search(r"(?m)^client\\s*$",config) and re.search(r"(?m)^remote\\s+\\S+",config)):continue
            if re.search(r"(?im)^\\s*(script-security|up|down|plugin|client-connect|client-disconnect)\\s+",config):continue
            ping=int(row[3]) if row[3].isdigit() else 0
            country=row[5].strip()[:45]
            nodes.append({"host":host,"country":country,"ping":ping,"ovpn":config})
            seen.add(host)
        except (ValueError,UnicodeError,IndexError):continue
        if len(nodes)>=45:break
    if not nodes:raise ValueError("No valid OpenVPN relay in origin CSV")
    nodes.sort(key=lambda n:n["ping"] if n["ping"]>0 else 999999)
    TARGET.write_text(json.dumps({"updated":start,"nodes":nodes},separators=(",",":")),encoding="utf-8")
    print(f"Published {len(nodes)} OpenVPN profiles")
except Exception as ex:
    print(f"VPN Gate mirror refresh failed: {ex}; preserving last good snapshot")
    # A blocked public upstream must not replace a previous valid snapshot.
