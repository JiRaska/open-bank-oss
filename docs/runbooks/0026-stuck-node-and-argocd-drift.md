# Runbook 0026 — A Ready node that cannot start pods, and ArgoCD drift that will not heal

**Alerts that link here:** `NodePodSandboxCreationFailing`, `NodePodsStuckStarting`
(`prometheus-rules-node-pod-startup.yaml`), `ArgoCDAppOutOfSyncNotHealing`
(`prometheus-rules-argocd.yaml`). The trace-pipeline alerts in
`prometheus-rules-trace-ingestion.yaml` were born in the same incident and are covered in §3.
The EBS attachment alerts in `prometheus-rules-finops.yaml` also link here
for the node and volume diagnosis in §1; the alert alone does not prove
which device or workload caused the condition.

**Why these exist.** Three faults on 2026-09-29/30 each ran 20-120 minutes before anyone
noticed, and every dashboard was green throughout:

1. A Karpenter node joined with two of its four ENIs stuck in EC2 attachment state
   `attaching`. The VPC CNI had half its pod IPs; once they were used, every further pod
   scheduled there sat in `ContainerCreating` with `FailedCreatePodSandBox … failed to assign
   an IP address to container`. The node stayed `Ready`. Restarting `aws-node` changed nothing;
   deleting the NodeClaim fixed it. Earlier the same week an EBS volume stuck `attaching` took
   two DB-backed services down the same way. Same class: a device hot-attach hangs and the
   node reports healthy.
2. A mutating admission policy change made controller pod templates get rewritten at
   admission. ArgoCD diffs desired state against a **server-side dry-run**, so every workload
   admitted before the change was `OutOfSync` — and stayed so, because each self-heal sync
   applied a no-op that never re-entered the webhook, and reported "successfully synced".
3. Single-replica Tempo was rolled by (2) and nothing could have said how long it was
   without ingestion, because nothing scraped it.

CI cannot see any of this: none of it is in a manifest. The control is the alert, and this
runbook is what to do when it fires.

---

## 1. `NodePodSandboxCreationFailing` / `NodePodsStuckStarting` — a node that cannot start pods

### 1.1 Confirm the shape: several pods stuck on ONE node

```bash
# stuck-per-node count: pods waiting in ContainerCreating/PodInitializing/Init:*, by node
kubectl get pods -A -o wide --no-headers \
  | awk '$4 ~ /ContainerCreating|PodInitializing|^Init:/ {n[$8]++} END {for (k in n) print n[k], k}' \
  | sort -rn
```

One node with several and every other node at zero is a node fault. Several nodes each with
one pod is not — read those pods' events instead (a missing Secret, a missing image).

Read one stuck pod's events on that node:

```bash
NODE=<node>
kubectl get pods -A -o wide --field-selector spec.nodeName=$NODE | grep -E 'ContainerCreating|Init'
kubectl -n <ns> describe pod <pod> | sed -n '/^Events/,$p'
```

| event text | cause | go to |
| --- | --- | --- |
| `FailedCreatePodSandBox … failed to assign an IP address to container` | VPC CNI has no free pod IPs on the node — usually an ENI that never finished attaching | §1.2 |
| `FailedAttachVolume` / `Multi-Attach error` / `VolumeInUse` | an EBS volume stuck attaching (here) or stuck attached to a previous node | §1.3 |
| `FailedCreatePodSandBox` with a containerd / runtime error | hung container runtime | §1.4 (replace the node) |

### 1.2 ENI stuck attaching

The kubelet's view (`kubectl describe node $NODE` → `Allocatable pods`) does not show this;
the CNI's view does, and EC2 is the ground truth:

```bash
INSTANCE=$(kubectl get node $NODE -o jsonpath='{.spec.providerID}' | sed 's|.*/||')

# every ENI on the instance with its attachment state — a healthy node reads `attached` on all
aws ec2 describe-network-interfaces \
  --filters Name=attachment.instance-id,Values=$INSTANCE \
  --query 'NetworkInterfaces[].{eni:NetworkInterfaceId,status:Status,attach:Attachment.Status,ips:length(PrivateIpAddresses)}' \
  --output table

# ipamd's own accounting, for the same node
kubectl -n kube-system exec $(kubectl -n kube-system get pod -l k8s-app=aws-node --field-selector spec.nodeName=$NODE -o name) \
  -c aws-node -- curl -s http://localhost:61679/v1/enis | python3 -m json.tool | head -60
```

An ENI in `attaching` for more than a couple of minutes will not complete. Restarting
`aws-node` re-reads the same EC2 state and does not help — measured. **Replace the node
(§1.4).** Do not detach the ENI by hand while the instance runs; Karpenter's termination
releases it.

### 1.3 EBS volume stuck attaching / detaching

```bash
kubectl get volumeattachment -o wide | grep $NODE
aws ec2 describe-volumes --filters Name=attachment.instance-id,Values=$INSTANCE \
  --query 'Volumes[].{vol:VolumeId,state:State,attach:Attachments[0].State}' --output table
```

`attaching` that never completes: replace the node (§1.4) — the instance termination releases
the volume. `detaching` that never completes on a node being drained is the other half and
has its own alert (`NodeClaimAwaitingVolumeDetachment`, `prometheus-rules-capacity.yaml`):
do not strip VolumeAttachment finalizers while the instance is still running.

### 1.4 Replace the node

```bash
# the NodeClaim owning the node
NC=$(kubectl get nodeclaim -o json | python3 -c \
  "import json,sys; print([n['metadata']['name'] for n in json.load(sys.stdin)['items'] if n['status'].get('nodeName')=='$NODE'][0])")

# what is on it that cannot be rescheduled freely — a single-instance CNPG primary here means
# a brief database outage, so do this with eyes open (ADR-0325)
kubectl get pods -A -o wide --field-selector spec.nodeName=$NODE | grep -E 'db-|openbao|prometheus|loki|tempo'

kubectl cordon $NODE
kubectl delete nodeclaim $NC          # Karpenter drains (honours PDBs), then terminates
kubectl get nodeclaim -w              # a replacement is provisioned within ~2 min
```

If the drain wedges on a PDB (`ALLOWED DISRUPTIONS 0` on a single-replica stateful
workload) the NodePool's `terminationGracePeriod` force-terminates at its deadline; deleting
the primary pod by hand moves it sooner (pod DELETE bypasses the PDB, the eviction API does
not). Both alerts clear once the stuck pods are rescheduled off the node.

### 1.5 Afterwards

Record the ENI / volume id and the EC2 instance id: a hot-attach that hangs is an EC2-side
event, and the ids are what an AWS support case needs. If it recurs on the same instance
family or AZ, that is the pattern to take to the NodePool requirements.

---

## 2. `ArgoCDAppOutOfSyncNotHealing` — drift that a "successful" sync does not fix

### 2.1 Measure how many, and which

```bash
kubectl -n argocd get applications -o json | python3 -c '
import json,sys
for a in json.load(sys.stdin)["items"]:
    s=a["status"]
    if s["sync"]["status"]!="Synced":
        op=s.get("operationState",{})
        print(a["metadata"]["name"], s["sync"]["status"], op.get("phase"), op.get("finishedAt"))
        for r in s.get("resources",[]):
            if r.get("status") not in (None,"Synced"): print("   ", r["kind"], r.get("namespace"), r["name"], r.get("status"))'
```

`Succeeded` with a recent `finishedAt` on an app that is still `OutOfSync` is the signature:
the sync ran, changed nothing, and the diff is still there. Many apps at once, right after
a Kyverno / admission-policy merge, is cause A below. One app, forever, is cause B.

### 2.2 Cause A — a mutating admission policy changed after the workload was admitted

ArgoCD's desired state is the manifest **after** a server-side dry-run, i.e. after every
mutating webhook. The live object was mutated by the policy as it was when it was admitted.
Compare the two for one OutOfSync resource:

```bash
NS=<ns>; DEPLOY=<deployment>
# live template image and any injected fields
kubectl -n $NS get deploy $DEPLOY -o jsonpath='{.spec.template.spec.containers[*].image}'; echo
# what admission would produce NOW for the same manifest
kubectl -n $NS get deploy $DEPLOY -o yaml \
  | kubectl apply --dry-run=server -f - -o jsonpath='{.spec.template.spec.containers[*].image}'; echo
# or the full diff
kubectl -n $NS get deploy $DEPLOY -o yaml | kubectl diff -f - 2>/dev/null | head -40
```

If they differ (a rewritten image registry, an added annotation, an injected sidecar), the
remedy is to re-admit the pod template:

```bash
kubectl -n $NS rollout restart deploy/$DEPLOY        # or statefulset/, daemonset/
```

A `rollout restart` rolls the workload: for single-replica stateful things (Tempo, Loki,
Prometheus, OpenBao) that is a short outage, so do those last and one at a time. After each
restart the app goes `Synced` within one reconcile (~3 min).

**Post-merge control for any mutating policy change** — see runbook 0011 §6: count OutOfSync
apps before and after the merge, and compare live vs dry-run for one controller that carries
an upstream image. CI cannot run this; the alert is the control.

### 2.3 Cause B — a manifest value the API server cannot store

If `git show origin/main:<app manifest>` and `kubectl get -o yaml` differ by a key that is
present in git and absent live — a `null` value, a field the API server defaults away — the
desired state can never equal the live state and every sync is a no-op. Fix the manifest:
omit the key instead of writing `null`. The `root` app carried exactly this for the kyverno
Application on 2026-09-30 (`config.webhooks: null`).

### 2.4 Not this alert

`ComparisonError` — ArgoCD cannot compute a diff at all (an immutable field changed) — reports
`Synced` and is invisible to every `argocd_app_info` rule; the `argocd-sync-verifier` CronJob
carries it via `KubeJobFailed`. See `openbank-infra/CLAUDE.md`.

---

## 3. `TempoTraceIngestionStalled` / `TempoMetricsAbsent`

`TempoMetricsAbsent`: the scrape is gone before the ingestion is — check the ServiceMonitor
and the pod:

```bash
kubectl -n observability get servicemonitor tempo
kubectl -n observability get pods -l app.kubernetes.io/name=tempo
kubectl get --raw /api/v1/namespaces/observability/services/tempo:3200/proxy/metrics | grep '^tempo_distributor_spans_received_total'
```

`TempoTraceIngestionStalled`: Tempo is up and receiving nothing. The producers are the OTel
collector (`otel-collector.yaml`, exporting to `tempo.observability.svc:4317`); check it is
running and its logs for `context canceled` / connection refused against Tempo, then Tempo's
own log for receiver errors. A counter reset (pod roll) is not a stall — `rate()` handles it
and the 15 m dwell absorbs a restart.

---

## 4. Related

- `NodeClaimAwaitingVolumeDetachment` (`prometheus-rules-capacity.yaml`) — the detach half of the same class.
- Runbook 0011 §6 — post-merge check for mutating admission policy changes.
- ADR-0325 — CNPG update resilience; why a node replacement holding a single-instance primary
  is a decision, not a reflex.
- `openbank-infra/CLAUDE.md` — Karpenter drift / consolidation blocked by single-replica PDBs,
  and the `ComparisonError` class of ArgoCD silence.
