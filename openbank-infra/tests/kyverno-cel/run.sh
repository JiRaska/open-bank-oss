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
OUT="$T/tmp-mutated"
trap 'rm -rf "$ROOT/$OUT"' EXIT
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

# ── Mutation parity: ecr-pull-through-rewrite (v1, pinned) vs -cel ───────────────
# The v1 ClusterPolicy was deleted when its CEL port was enabled (#11437). Its output
# image per (namespace, pod, list, container) is pinned in V1_IMAGES, measured with this
# harness (Kyverno CLI v1.19.1, v1 file as on main) on 2026-09-29, immediately before
# the deletion. The CEL policy runs live and must produce exactly that map. It then
# runs a second time on its own output, which must change nothing: the idempotency the
# one-sync v1 -> CEL swap relied on, kept as a property of the policy.
mkdir -p "$ROOT/$OUT"
mut() { docker run --rm -v "$ROOT:/w" -w /w "$CLI_IMAGE" apply "$1" -r "$2" -o "$3" >/dev/null 2>&1 || true; }
mut "$K/ecr-pull-through-rewrite-cel.yaml" "$T/resources.yaml" "$OUT/cel.yaml"
python3 - "$ROOT/$OUT/cel.yaml" "$ROOT/$OUT/cel-pods.yaml" <<'PY'
import sys, yaml
pods = {}  # the CLI emits one document per mutation; the last one carries them all
for d in yaml.safe_load_all(open(sys.argv[1])):
    if d and d.get('kind') == 'Pod':
        pods[(d['metadata'].get('namespace', ''), d['metadata']['name'])] = d
open(sys.argv[2], 'w').write(yaml.safe_dump_all(list(pods.values())))
PY
mut "$K/ecr-pull-through-rewrite-cel.yaml" "$OUT/cel-pods.yaml" "$OUT/cel-twice.yaml"
python3 - "$ROOT/$OUT/cel.yaml" "$ROOT/$OUT/cel-twice.yaml" "$ROOT/$OUT/cel-pods.yaml" <<'PY'
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
V1_IMAGES = {  # measured 2026-09-29 by this harness before the v1 policy was deleted (#11437)
    ('arc-runners', 'dr-in-arc', 'containers', 'c'): '265175468565.dkr.ecr.eu-north-1.amazonaws.com/docker-hub/library/busybox:1.36',
    ('default', 'cnpg-1', 'containers', 'postgres'): 'ghcr.io/cloudnative-pg/postgresql:18.1',
    ('default', 'dr-in-default', 'containers', 'c'): '265175468565.dkr.ecr.eu-north-1.amazonaws.com/docker-hub/library/busybox:1.36',
    ('default', 'multi-registry', 'containers', 'ecr'): '265175468565.dkr.ecr.eu-north-1.amazonaws.com/quay/prometheus/prometheus:v3.0.0',
    ('default', 'multi-registry', 'containers', 'ecrpub'): '265175468565.dkr.ecr.eu-north-1.amazonaws.com/ecr-public/docker/library/redis:7',
    ('default', 'multi-registry', 'containers', 'hub'): '265175468565.dkr.ecr.eu-north-1.amazonaws.com/docker-hub/library/nginx:1.27-alpine',
    ('default', 'multi-registry', 'containers', 'k8s'): '265175468565.dkr.ecr.eu-north-1.amazonaws.com/k8s/pause:3.10',
    ('default', 'multi-registry', 'containers', 'lookalike'): 'myquay.io/x:1',
    ('default', 'multi-registry', 'containers', 'quay'): '265175468565.dkr.ecr.eu-north-1.amazonaws.com/quay/prometheus/prometheus:v3.0.0',
    ('default', 'multi-registry', 'initContainers', 'init-bare'): 'busybox:1.36',
    ('default', 'multi-registry', 'initContainers', 'init-ghcr'): '265175468565.dkr.ecr.eu-north-1.amazonaws.com/ghcr/cloudnative-pg/postgresql:18.1',
    ('default', 'no-sa', 'containers', 'c'): 'busybox:1.36',
    ('default', 'plain-sa', 'containers', 'c'): 'busybox:1.36',
    ('kube-system', 'eks-addon', 'containers', 'k8s'): '265175468565.dkr.ecr.eu-north-1.amazonaws.com/k8s/pause:3.10',
    ('kube-system', 'eks-addon', 'containers', 'quay'): 'quay.io/foo/bar:1',
}
cel = imgs(sys.argv[1])
if not cel:
    sys.exit("mutation run produced no Pods")
# A second-pass Pod the CLI skips is not re-emitted; fall back to its first-pass copy.
twice = {**imgs(sys.argv[3]), **imgs(sys.argv[2])}
rewritten = sum(1 for v in V1_IMAGES.values() if v.startswith('265175468565.dkr.ecr.'))
bad = 0
for k in sorted(set(V1_IMAGES) | set(cel)):
    a, b = V1_IMAGES.get(k), cel.get(k)
    bad += a != b
    print(f"OK   ecr-pull-through-rewrite {'/'.join(k):48} {b}" if a == b else
          f"DIFF ecr-pull-through-rewrite {'/'.join(k):48} v1={a} cel={b}")
redo = [k for k in cel if twice.get(k) != cel[k]]
for k in redo:
    print(f"DIFF ecr-pull-through-rewrite second pass {'/'.join(k)}: {cel[k]} -> {twice.get(k)}")
print(f"{len(cel)} images, {rewritten} rewritten by v1, {bad} divergent, {len(redo)} changed on a second pass")
sys.exit(1 if bad or redo or not rewritten else 0)
PY
