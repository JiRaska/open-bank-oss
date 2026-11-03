#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify real push/PR service selection, including external JVM test inputs."""
import os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
# Exercise the extracted production selector, not its former inline workflow copy.
script='bash .github/scripts/service-ci-select-modules.sh'
allmods={p.name for p in root.glob('openbank-*') if (p/'build.gradle.kts').is_file()}
sample=['.github/gates/gates.yaml','.github/scripts/check-tofu-image-pull-through.py']+['openbank-infra/aws/envs/sandbox-platform/'+x+'.tf' for x in ['arc-runner-reaper','arc-runners','main','providers']]
cases=[('governance-reader',['openbank-libs/governance/agents.yaml'],'charter'),('measured-infra',sample,'empty'),('mixed-account',sample+['openbank-account-service/src/main/kotlin/Sample.kt'],'account'),('gitops-reader',['openbank-infra/gitops/components/payments/transaction-service-msg-override.yaml'],'full'),('adr-reader',['docs/adr/0039-ledger-as-golden-source-balance-as-projection.md'],'full'),('unknown-helper',['.github/scripts/check-tofu-image-pull-through-test-helper.py'],'full')]
cases += [('canonical-runtime-image', ['.github/workflows/Dockerfile.deploy'], 'full'), ('shared-benchmark-build', ['openbank-libs-benchmarks/build.gradle.kts'], 'full'), ('shared-detekt-source', ['openbank-libs-detekt-rules/src/main/kotlin/Sample.kt'], 'full')]
# Content-based exemption (ADR-0362 item 5): a PR whose only change under openbank-infra/gitops is
# image-pin lines (the auto-deploy shape, 52 full-fleet runs/week measured 2026-11-03) builds no
# service; anything else in such a diff — a config key, an env value, an empty diff — still fans
# out to the full fleet. The 4th element is the unified diff the stubbed `git diff` returns.
manifest='openbank-infra/gitops/components/accounts/account-service.yaml'
pin_hunk='--- a/'+manifest+'\n+++ b/'+manifest+'\n@@ -55,1 +55,1 @@\n-          image: 123456789012.dkr.ecr.eu-north-1.amazonaws.com/openbank-account-service:sandbox-7f966f31\n+          image: 123456789012.dkr.ecr.eu-north-1.amazonaws.com/openbank-account-service:sandbox-873a9da1@sha256:'+'0'*64+'\n'
cases += [('deploy-pin-only', [manifest], 'empty', pin_hunk),
          ('deploy-pin-plus-config', [manifest], 'full', pin_hunk+'@@ -70,1 +70,1 @@\n-            value: "false"\n+            value: "true"\n'),
          ('deploy-pin-empty-diff', [manifest], 'full', ''),
          ('deploy-pin-non-sandbox-image', [manifest], 'full', '--- a/'+manifest+'\n+++ b/'+manifest+'\n@@ -55,1 +55,1 @@\n-          image: ghcr.io/x/y:1.0\n+          image: ghcr.io/x/y:1.1\n')]
results=[]
with tempfile.TemporaryDirectory(prefix='ob-selector-') as tmp:
 t=Path(tmp);g=t/'git';g.write_text('#!/bin/bash\nif [ "$1" = diff ] && [[ " $* " == *" --name-only "* ]]; then printf "%s\\n" "$OB_TEST_FILES"; elif [ "$1" = diff ]; then printf "%s\\n" "$OB_TEST_DIFF"; elif [ "$1" = fetch ]; then exit 0; elif [ "$1" = merge-base ]; then /usr/bin/git rev-parse HEAD; else /usr/bin/git "$@"; fi\n');g.chmod(0o755)
 for event in ['push','pull_request']:
  s=script.replace('${{ github.event_name }}',event).replace('${{ github.base_ref }}','main');assert '${{' not in s
  for case in cases:
   name,files,expected=case[:3];diff=case[3] if len(case)>3 else ''
   if event=='push' and len(case)>3: expected='full'   # the exemption is PR-only; a push to main keeps the conservative path
   out=t/(event+'-'+name+'.out');out.write_text('')
   env=dict(os.environ,PATH=str(t)+':'+os.environ['PATH'],OB_TEST_FILES='\n'.join(files),OB_TEST_DIFF=diff,GITHUB_OUTPUT=str(out),EVENT_NAME=event,BASE_REF='main',SELECT_OUT=str(out)+'.selection')
   r=subprocess.run(['bash','-c',s],cwd=root,env=env,text=True,capture_output=True)
   assert r.returncode==0,(event,name,r.stderr[-2000:])
   selected=set((Path(env['SELECT_OUT'])/'changed.txt').read_text().split())
   if expected=='empty':assert not selected,(event,name,selected)
   elif expected=='full':assert selected==allmods,(event,name,selected)
   elif expected=='charter':assert {'openbank-agent-service','openbank-mcp-service'} <= selected and selected!=allmods,(event,name,selected)
   else:assert 'openbank-account-service' in selected and 'openbank-libs-runtime' in selected and selected!=allmods,(event,name,selected)
   results.append({'event':event,'case':name,'selected':sorted(selected),'exit':0})
print('PASS',len(results),'actual push/PR selector scenarios')
