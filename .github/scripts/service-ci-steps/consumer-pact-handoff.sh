#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
python3 - "$@" <<'PY'
import hashlib,json,os,pathlib,re,subprocess,sys
mode=sys.argv[1]
service=os.environ['SERVICE']
if not re.fullmatch(r'openbank-[a-z0-9-]+',service): raise SystemExit('invalid consumer service')
handoff=pathlib.Path('pact-handoff')
manifest=handoff/'manifest.json'
def files(directory):
    result={}
    for p in sorted(pathlib.Path(directory).glob(service+'-*.json')):
        if p.is_symlink(): raise SystemExit('symlinked pact file')
        if json.loads(p.read_text()).get('consumer',{}).get('name')!=service: raise SystemExit('wrong pact consumer')
        result[p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
    return result
if mode=='collect':
    import shutil
    source=subprocess.check_output(['git','rev-parse','--verify','HEAD^{commit}'],text=True).strip()
    handoff.mkdir(exist_ok=True)
    collected=files('pacts')
    for name in collected: shutil.copyfile(pathlib.Path('pacts')/name,handoff/name)
    manifest.write_text(json.dumps(dict(schema=1,service=service,source=source,files=collected),sort_keys=True)+'\n')
    (handoff/'manifest.txt').write_text('pact-count='+str(len(collected))+'\n')
    with open(os.environ['GITHUB_OUTPUT'],'a') as output: output.write('source='+source+'\n')
elif mode=='validate':
    d=json.loads(manifest.read_text())
    source=d.get('source','')
    expected=os.environ.get('EXPECTED_CONSUMER_SOURCE','')
    if d.get('schema')!=1 or d.get('service')!=service: raise SystemExit('invalid handoff identity')
    if not re.fullmatch(r'[0-9a-f]{40}',source) or source!=expected: raise SystemExit('handoff source does not match build source')
    subprocess.run(['git','merge-base','--is-ancestor',source,'origin/main'],check=True)
    actual=files(sys.argv[2])
    if d.get('files')!=actual: raise SystemExit('pact file set or hash mismatch')
    if (handoff/'manifest.txt').read_text()!='pact-count='+str(len(actual))+'\n': raise SystemExit('pact count mismatch')
    print(source)
else: raise SystemExit('unknown handoff mode')
PY
