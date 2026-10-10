from pathlib import Path
import hashlib
root=Path(__file__).resolve().parents[1]
FILES={'app/src/main/java/com/aegis/vpn/SingBoxConfig.java': 'bca78aa874b2994a7d3e171da1c4b3d6cbd26fa4eb8ef7eca8e58fd66f5f1869', 'app/src/main/java/com/aegis/vpn/SingVpnService.java': '7a98090e9f97054e7004f87d382bae6142597ddbcbd133d1008b6d9eb3603f02', 'app/src/main/java/com/aegis/vpn/SingBoxPlatform.java': '160a9cb1e8dede9c278345a61276d96a2e3b64ebaa4ee68f2d0489f3bd906a8c', 'app/src/main/java/com/aegis/vpn/SourceHub.java': 'b15fcf3b7dec085060f06e6b60ea17d6b855f63dd38718f2b58662f08fa8a005', 'app/src/main/java/com/aegis/vpn/FeedParser.java': 'e9bb627762c902c3a435587f5595fe776ecf8780f6d85da25aa965a2fffcf173', 'app/src/main/java/com/aegis/vpn/EndpointProbe.java': '9633cc2b189de2e9109697901830f3689ec64638533a9eafbf7720ca9997634e', 'app/src/main/java/com/aegis/vpn/FreeDirectory.java': 'df1f55f4b5589cc8ee4444df40d7c1c1078f481703409e80945ba5b0e41b5b65', 'app/src/main/java/com/aegis/vpn/VpnController.java': '1576f2c5c042fd3454ce73a3979aa135242883fcc1e94db60ffc67e8a2ebbc20', 'app/src/main/java/com/aegis/vpn/ProfileStore.java': 'bac0f3895b095a4c6b56def623370863822d3ebd64e29f87920a0d9298e0dd85', 'app/src/main/java/com/aegis/vpn/RefreshJob.java': '9ae8ad55edc9915b9e99c1897aa945411224325665172d65fbc8788892b28c0d'}
METHODS={'void startVpn()': '7d919f9bbe94b8ae29352bfec41dbe0e609e6ed8b162fa0cd1e5cec009384fa2', 'boolean hasNativeCandidates()': '4c601df1184c59f5588d9dc327af5f5dff25c466939a4d21e11ea1e6d6d6a652', 'void connectNative(String config)': 'b50d7d48ad1f907290294a8d7266b40b8165bf8aa72cf2abd070efe3193456d1', 'void startNativeService(String config)': '30313a174fdc882c0e21ff4da3f0f44a5ed8d1c708fd1c5c73ee2f17501e8452'}
for name,digest in FILES.items():
    assert hashlib.sha256((root/name).read_bytes()).hexdigest()==digest, "V5 source changed: "+name
source=(root/'app/src/main/java/com/aegis/vpn/V5Connection.java').read_text()
for signature,digest in METHODS.items():
    start=source.index('    '+signature)
    k=source.index('{',start)+1
    depth=1
    while depth:
        if source[k]=='{':depth+=1
        if source[k]=='}':depth-=1
        k+=1
    assert hashlib.sha256(source[start:k].encode()).hexdigest()==digest, "V5 orchestration changed: "+signature
print('10 original V5 source files and 4 connection methods match build-25')
