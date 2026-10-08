from pathlib import Path
import json,hashlib,urllib.request,os
root=Path(__file__).resolve().parents[1]
info=json.loads((root/'app/libs/libbox-provenance.json').read_text())
target=root/'app/libs/libbox.aar'
def digest(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()
if target.exists() and digest(target)==info['sha256']:
    print('Pinned libbox already verified.');raise SystemExit(0)
url=info['release'].replace('/tag/','/download/')+'/'+info['asset']
temporary=target.with_suffix('.aar.download')
try:
    with urllib.request.urlopen(url,timeout=90) as response,temporary.open('wb') as output:
        while chunk:=response.read(1024*1024):output.write(chunk)
    if digest(temporary)!=info['sha256']:raise RuntimeError('Downloaded libbox SHA256 mismatch')
    os.replace(temporary,target)
finally:
    temporary.unlink(missing_ok=True)
print('Pinned libbox downloaded and verified.')
