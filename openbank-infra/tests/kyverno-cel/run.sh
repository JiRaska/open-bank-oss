#!/usr/bin/env bash
# Parity harness for the kyverno.io/v1 -> policies.kyverno.io/v1 (CEL) migration.
# Applies the v1 ClusterPolicies and their CEL ports to the SAME fixtures with the
# Kyverno CLI and requires an identical verdict per (policy, resource). Exit 1 on
# any divergence, and on an empty result set (a harness that evaluated nothing is
# not a pass). Needs docker; the CLI is pinned to the cluster's Kyverno version.
set -euo pipefail
CLI_IMAGE="${KYVERNO_CLI_IMAGE:-ghcr.io/kyverno/kyverno-cli:v1.19.1}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)" # openbank-infra/
K=gitops/components/kyverno
T=tests/kyverno-cel
run() {
  docker run --rm -v "$ROOT:/w" -w /w "$CLI_IMAGE" apply "$@" \
    -r "$T/resources.yaml" --policy-report 2>&1 || true
}
# rollout-bypass-prevention.yaml (block-deployment-if-rollout-exists) was never in the
# v1 run: the 1.19.1 CLI panics on its apiCall urlPath (query string -> empty GVR in
# the fake client), with or without values. Its v1 verdicts are pinned in V1_ROLLOUT
# below, measured against the live Enforce policy with `kubectl create
# --dry-run=server`. The file was deleted when its CEL port went to Enforce (#11437).
# deny-nginx-snippet-annotations.yaml is NOT in the v1 run either: it was deleted when
# its CEL port went to Enforce (#11437). Its v1 verdicts are pinned in V1_NGINX below,
# measured with this harness (Kyverno CLI v1.19.1, v1 file as on main) on 2026-09-29,
# immediately before the deletion.
# tool-ingress-gate-policy.yaml (require-gated-or-declared-tool-ingress) went the same
# way (#11437): deleted when its CEL port went to Enforce. Its v1 verdicts are pinned
# in V1_TOOL_INGRESS below, measured with this harness (Kyverno CLI v1.19.1, v1 file
# as on main) on 2026-09-29, immediately before the deletion.
# openbank-dr-sa-pin (formerly the last document of platform/dr-runner-rbac.yaml)
# went the same way (#11437), in two PRs because it lived in another Argo app. Its v1
# verdicts are pinned in V1_DR_SA_PIN below, measured with this harness (Kyverno CLI
# v1.19.1, ClusterPolicy extracted from the file as on main) on 2026-09-29,
# immediately before the removal. With it, no v1 validate policy is left to run, so
# the v1 side of the comparison is made entirely of pinned verdicts.
MP="$T/tmp-ecr-rewrite-cel-enabled.yaml"
OUT="$T/tmp-mutated"
trap 'rm -rf "$ROOT/$MP" "$ROOT/$OUT"' EXIT
CEL=$(run "$K/cel-validating-policies.yaml" -f "$T/values.yaml" \
  --context-file "$T/context.yaml" --crd-paths "$T/rollout-crd-stub.yaml")
python3 - "$CEL" <<'PY'
import sys, yaml

def table(txt):
    i = max(txt.find('apiVersion: wgpolicyk8s'), txt.find('apiVersion: openreports'))
    if i < 0:
        print(txt[-2000:]); sys.exit("no policy report in CLI output")
    out = {}
    for d in yaml.safe_load_all(txt[i:]):
        for r in (d or {}).get('results') or []:
            pol = r['policy'].removesuffix('-cel')
            for res in r['resources']:
                out[(pol, res['kind'], res.get('namespace', ''), res['name'])] = r['result']
    return out

v1, cel = {}, table(sys.argv[1])
V1_ROLLOUT = {  # measured live (re-measured 2026-09-29 before the v1 file was deleted, #11437)
    ('block-deployment-if-rollout-exists', 'Deployment', 'ledger', 'ledger-service'): 'fail',
    ('block-deployment-if-rollout-exists', 'Deployment', 'ledger', 'redis'): 'pass',
}
v1.update(V1_ROLLOUT)
V1_NGINX = {  # measured 2026-09-29 by this harness before the v1 file was deleted (#11437)
    ('deny-nginx-snippet-annotations', 'Ingress', 'default', 'clean'): 'pass',
    ('deny-nginx-snippet-annotations', 'Ingress', 'default', 'empty-snippet'): 'pass',
    ('deny-nginx-snippet-annotations', 'Ingress', 'default', 'snippet'): 'fail',
    ('deny-nginx-snippet-annotations', 'Ingress', 'default', 'tool-ungated-elsewhere'): 'pass',
    ('deny-nginx-snippet-annotations', 'Ingress', 'observability', 'tool-declared'): 'pass',
    ('deny-nginx-snippet-annotations', 'Ingress', 'observability', 'tool-gated'): 'pass',
    ('deny-nginx-snippet-annotations', 'Ingress', 'observability', 'tool-ungated'): 'pass',
}
v1.update(V1_NGINX)
V1_TOOL_INGRESS = {  # measured 2026-09-29 by this harness before the v1 file was deleted (#11437)
    ('require-gated-or-declared-tool-ingress', 'Ingress', 'observability', 'tool-declared'): 'pass',
    ('require-gated-or-declared-tool-ingress', 'Ingress', 'observability', 'tool-gated'): 'pass',
    ('require-gated-or-declared-tool-ingress', 'Ingress', 'observability', 'tool-ungated'): 'fail',
}
v1.update(V1_TOOL_INGRESS)
V1_DR_SA_PIN = {  # measured 2026-09-29 by this harness before the v1 policy was removed (#11437)
    ('openbank-dr-sa-pin', 'Deployment', 'default', 'dr-deploy'): 'fail',
    ('openbank-dr-sa-pin', 'Deployment', 'default', 'ledger-service'): 'pass',
    ('openbank-dr-sa-pin', 'Deployment', 'ledger', 'ledger-service'): 'pass',
    ('openbank-dr-sa-pin', 'Deployment', 'ledger', 'redis'): 'pass',
    ('openbank-dr-sa-pin', 'Pod', 'default', 'cnpg-1'): 'pass',
    ('openbank-dr-sa-pin', 'Pod', 'default', 'dr-in-default'): 'fail',
    ('openbank-dr-sa-pin', 'Pod', 'default', 'multi-registry'): 'pass',
    ('openbank-dr-sa-pin', 'Pod', 'default', 'no-sa'): 'pass',
    ('openbank-dr-sa-pin', 'Pod', 'default', 'plain-sa'): 'pass',
    ('openbank-dr-sa-pin', 'Pod', 'kube-system', 'eks-addon'): 'pass',
}
v1.update(V1_DR_SA_PIN)
EXPECTED = {'block-deployment-if-rollout-exists', 'deny-nginx-snippet-annotations',
            'require-gated-or-declared-tool-ingress', 'openbank-dr-sa-pin'}
for name, t in (('v1', v1), ('cel', cel)):
    missing = EXPECTED - {k[0] for k in t}
    if missing:  # a policy the CLI never loaded reports nothing, which reads as parity
        sys.exit(f"{name}: no results at all for {sorted(missing)}")
keys = sorted(set(v1) | set(cel))
if not keys:
    sys.exit("no results — harness evaluated nothing")
bad = 0
admit = {'-', 'pass', 'skip'}  # not matched / passed / excluded all admit
for k in keys:
    a, b = v1.get(k, '-'), cel.get(k, '-')
    same = a == b or (a in admit and b in admit)
    bad += not same
    print(f"{'OK  ' if same else 'DIFF'} {k[0]:40} {k[1]:10} {k[2] + '/' + k[3]:38} v1={a:5} cel={b}")
fails = sum(1 for v in v1.values() if v == 'fail')
print(f"{len(keys)} rows, {fails} v1 denials, {bad} divergent")
sys.exit(1 if bad or not fails else 0)
PY

# ── Mutation parity: ecr-pull-through-rewrite (v1) vs -cel ────────────────────────
# The CEL port ships behind an always-false kill-switch matchCondition; test it with
# the switch removed, and compare every container image both engines produce.
python3 - "$ROOT/$K/ecr-pull-through-rewrite-cel.yaml" "$ROOT/$MP" <<'PY'
import sys, yaml
d = yaml.safe_load(open(sys.argv[1]))
mc = d['spec']['matchConditions']
d['spec']['matchConditions'] = [c for c in mc if c['name'] != 'stage-1-disabled-until-v1-removed']
assert len(d['spec']['matchConditions']) == len(mc) - 1, "kill switch not found"
open(sys.argv[2], 'w').write(yaml.safe_dump(d))
PY
mkdir -p "$ROOT/$OUT"
mut() { docker run --rm -v "$ROOT:/w" -w /w "$CLI_IMAGE" apply "$1" -r "$T/resources.yaml" -o "$2" >/dev/null 2>&1 || true; }
mut "$K/ecr-pull-through-rewrite.yaml" "$OUT/v1.yaml"
mut "$MP" "$OUT/cel.yaml"
python3 - "$ROOT/$OUT/v1.yaml" "$ROOT/$OUT/cel.yaml" <<'PY'
import sys, yaml
def imgs(p):
    out = {}
    for d in yaml.safe_load_all(open(p)):
        if not d or d.get('kind') != 'Pod':
            continue
        for f in ('initContainers', 'containers'):
            for c in d['spec'].get(f) or []:
                out[(d['metadata'].get('namespace', ''), d['metadata']['name'], f, c['name'])] = c['image']
    return out
a, b = imgs(sys.argv[1]), imgs(sys.argv[2])
if not a or not b:
    sys.exit("mutation run produced no Pods")
rewritten = sum(1 for k in a if a[k].startswith('265175468565.dkr.ecr.'))
bad = 0
for k in sorted(set(a) | set(b)):
    same = a.get(k) == b.get(k)
    bad += not same
    print(f"{'OK  ' if same else 'DIFF'} ecr-pull-through-rewrite {'/'.join(k):48} {b.get(k)}")
print(f"{len(a)} images, {rewritten} rewritten by v1, {bad} divergent")
sys.exit(1 if bad or not rewritten else 0)
PY
