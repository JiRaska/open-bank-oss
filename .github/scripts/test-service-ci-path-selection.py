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
# 'readers:a,b' = exactly the modules whose tests name the path by literal (derived inventory);
# the anchors are real readers measured 2026-11-04 and go stale only when such a test is
# removed, which is the failure direction that should be reviewed, not silently absorbed.
cases=[('governance-reader',['openbank-libs/governance/agents.yaml'],'charter'),('measured-infra',sample,'empty'),('mixed-account',sample+['openbank-account-service/src/main/kotlin/Sample.kt'],'account'),('admin-ui-deploy-pin',['openbank-infra/gitops/components/admin-ui/admin-ui.yaml'],'empty'),
 ('gitops-reader',['openbank-infra/gitops/components/payments/transaction-service-msg-override.yaml'],'readers:openbank-mcp-service'),
 ('adr-reader',['docs/adr/0039-ledger-as-golden-source-balance-as-projection.md'],'readers:openbank-docs-truth-agent,openbank-release-steward'),
 ('unknown-helper',['.github/scripts/check-tofu-image-pull-through-test-helper.py'],'empty'),
 ('gate-manifest',['.github/gates/gates.yaml','.github/scripts/check-remote-build-cache-write-scope.py'],'empty'),
 ('recipe-helper',['.github/scripts/service-ci-steps/build-test.sh'],'canary'),
 ('root-security-md',['SECURITY.md'],'empty'),
 ('root-readme',['README.md'],'readers:openbank-docs-truth-agent'),
 ('governance-rules',['openbank-libs/governance/rules.yaml'],'readers:openbank-authz-policy-auditor,openbank-docs-truth-agent,openbank-domestic-payment,openbank-flaky-test-hunter,openbank-libs-runtime,openbank-sca-service,openbank-sdd-service'),
 ('gitops-payments',['openbank-infra/gitops/components/payments/payments-services.yaml'],'readers:openbank-domestic-payment,openbank-mcp-service,openbank-sepa-instant,openbank-settlement-service'),
 ('gitops-no-reader',['openbank-infra/gitops/components/analytics/cronjob-gate-health-puller.yaml'],'empty'),
 ('detekt-config',['config/detekt/detekt.yml'],'full')]
cases += [('canonical-runtime-image', ['.github/workflows/Dockerfile.deploy'], 'full'), ('shared-benchmark-build', ['openbank-libs-benchmarks/build.gradle.kts'], 'full'), ('shared-detekt-source', ['openbank-libs-detekt-rules/src/main/kotlin/Sample.kt'], 'full')]
results=[]
with tempfile.TemporaryDirectory(prefix='ob-selector-') as tmp:
 t=Path(tmp);g=t/'git';g.write_text('#!/bin/bash\nif [ "$1" = diff ] && [[ " $* " == *" --name-only "* ]]; then printf "%s\\n" "$OB_TEST_FILES"; elif [ "$1" = fetch ]; then exit 0; elif [ "$1" = merge-base ]; then /usr/bin/git rev-parse HEAD; else /usr/bin/git "$@"; fi\n');g.chmod(0o755)
 for event in ['push','pull_request']:
  s=script.replace('${{ github.event_name }}',event).replace('${{ github.base_ref }}','main');assert '${{' not in s
  for name,files,expected in cases:
   out=t/(event+'-'+name+'.out');out.write_text('')
   env=dict(os.environ,PATH=str(t)+':'+os.environ['PATH'],OB_TEST_FILES='\n'.join(files),GITHUB_OUTPUT=str(out),EVENT_NAME=event,BASE_REF='main',SELECT_OUT=str(out)+'.selection')
   r=subprocess.run(['bash','-c',s],cwd=root,env=env,text=True,capture_output=True)
   assert r.returncode==0,(event,name,r.stderr[-2000:])
   selected=set((Path(env['SELECT_OUT'])/'changed.txt').read_text().split())
   if expected=='empty':assert not selected,(event,name,selected)
   elif expected=='full':assert selected==allmods,(event,name,selected)
   elif expected=='charter':assert {'openbank-agent-service','openbank-mcp-service'} <= selected and selected!=allmods,(event,name,selected)
   elif expected=='canary':assert selected=={'openbank-libs'},(event,name,selected)
   elif expected.startswith('readers:'):assert selected==set(expected[8:].split(',')),(event,name,sorted(selected))
   else:assert 'openbank-account-service' in selected and 'openbank-libs-runtime' in selected and selected!=allmods,(event,name,selected)
   results.append({'event':event,'case':name,'selected':sorted(selected),'exit':0})
print('PASS',len(results),'actual push/PR selector scenarios')
