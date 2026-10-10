#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify real push/PR service selection, including external JVM test inputs."""
import json,os,subprocess,tempfile,yaml
from pathlib import Path
root=Path(__file__).resolve().parents[2]
w=yaml.safe_load((root/'.github/workflows/services-ci.yml').read_text())
script=next(x['run'] for x in w['jobs']['changes']['steps'] if x.get('id')=='detect')
# Exercise the real selection body without recursively invoking this test.
prefix=script.split('pact_build_modules_self_test\n',1)[0]
marker='if [ "${{ github.event_name }}" = "schedule" ]'
assert marker in script
script=prefix+script[script.index(marker):]
allmods={p.name for p in root.glob('openbank-*') if (p/'build.gradle.kts').is_file()}
sample=['.github/gates/gates.yaml','.github/scripts/check-tofu-image-pull-through.py']+['openbank-infra/aws/envs/sandbox-platform/'+x+'.tf' for x in ['arc-runner-reaper','arc-runners','main','providers']]
cases=[('measured-infra',sample,'empty'),('mixed-account',sample+['openbank-account-service/src/main/kotlin/Sample.kt'],'account'),('gitops-reader',['openbank-infra/gitops/components/payments/transaction-service-msg-override.yaml'],'full'),('adr-reader',['docs/adr/0039-ledger-as-golden-source-balance-as-projection.md'],'full'),('unknown-helper',['.github/scripts/check-tofu-image-pull-through-test-helper.py'],'full')]
results=[]
with tempfile.TemporaryDirectory(prefix='ob-selector-') as tmp:
 t=Path(tmp);g=t/'git';g.write_text('#!/bin/bash\nif [ "$1" = diff ] && [[ " $* " == *" --name-only "* ]]; then printf "%s\\n" "$OB_TEST_FILES"; elif [ "$1" = fetch ]; then exit 0; elif [ "$1" = merge-base ]; then /usr/bin/git rev-parse HEAD; else /usr/bin/git "$@"; fi\n');g.chmod(0o755)
 for event in ['push','pull_request']:
  s=script.replace('${{ github.event_name }}',event).replace('${{ github.base_ref }}','main');assert '${{' not in s
  for name,files,expected in cases:
   out=t/(event+'-'+name+'.out');out.write_text('')
   env=dict(os.environ,PATH=str(t)+':'+os.environ['PATH'],OB_TEST_FILES='\n'.join(files),GITHUB_OUTPUT=str(out))
   r=subprocess.run(['bash','-c',s],cwd=root,env=env,text=True,capture_output=True)
   assert r.returncode==0,(event,name,r.stderr[-2000:])
   values=dict(line.split('=',1) for line in out.read_text().splitlines() if '=' in line);selected=set(json.loads(values['services']))
   if expected=='empty':assert not selected,(event,name,selected)
   elif expected=='full':assert selected==allmods,(event,name,selected)
   else:assert 'openbank-account-service' in selected and 'openbank-libs-runtime' in selected and selected!=allmods,(event,name,selected)
   results.append({'event':event,'case':name,'selected':sorted(selected),'exit':0})
print('PASS',len(results),'actual push/PR selector scenarios')
