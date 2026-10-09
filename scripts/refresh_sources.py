"""Mirror only public connection links and file-post references, never account data."""
import concurrent.futures, html, json, pathlib, re, time, urllib.request
CHANNELS = ['net_azad','proxyplus','irovpn','mtproto021','netmeli_ir','npv_iran','proxy_netmeli','onevpn','iproxy2','myconfig','miticonfig','proxyrp','mitivpn']
root=pathlib.Path('feeds');root.mkdir(exist_ok=True)
def refresh(channel):
    try:
        req=urllib.request.Request('https://t.me/s/'+channel,headers={'User-Agent':'Mozilla/5.0'})
        with urllib.request.urlopen(req,timeout=20) as r:
            raw=r.read(3_000_001)
        if len(raw)>3_000_000: raise ValueError('feed too large')
        body=html.unescape(raw.decode('utf-8'));entries={}
        for v in re.findall(r'(?:vless|vmess|trojan|ss|hysteria2|hy2)://[^\s<>"\']{8,8192}|(?:tg://proxy\?|https://t\.me/proxy\?)[^\s<>"\']{8,4096}',body):
            kind='PROXY' if v.startswith(('tg:','https:')) else 'V2RAY'
            if kind=='PROXY' and not all(x in v for x in ('server=','port=','secret=')):continue
            entries[v]={'k':kind,'v':v}
            if len(entries)>=300:break
        for post,content in re.findall(r'data-post="([A-Za-z0-9_]+/[0-9]+)"(.*?)(?=data-post="|\Z)',body,re.S):
            if re.search(r'\.npv[st4]?',content,re.I):
                v='https://t.me/'+post;entries[v]={'k':'NAPSTERNETV','v':v}
        if not entries:raise ValueError('no supported public entries')
        data={'updated':int(time.time()*1000),'entries':list(entries.values())[:350]}
        (root/(channel+'.json')).write_text(json.dumps(data,ensure_ascii=False),encoding='utf-8')
        return {'channel':channel,'count':len(entries),'ok':True}
    except Exception as e:
        return {'channel':channel,'ok':False,'error':str(e)[:160]}
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    statuses=list(pool.map(refresh,CHANNELS))
(root/'status.json').write_text(json.dumps({'attempt':int(time.time()*1000),'sources':statuses},ensure_ascii=False),encoding='utf-8')
print(json.dumps(statuses))
