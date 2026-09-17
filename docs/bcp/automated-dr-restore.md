# Automated DR restore-and-verify

The quarterly and manually dispatchable `.github/workflows/dr-restore-verify.yml`
uses the cluster-attached DR runner. It restores the ledger backup into a temporary
namespace, starts the ledger check workload and requests its trial balance. Missing
cluster access fails the job; a skipped restore is not successful evidence.

## What the current check establishes

A successful run establishes that this ledger backup could be restored, the check
workload became ready and the selected fiscal year's trial balance returned the JSON
boolean `balanced: true`. The job records elapsed time from restore start through that
response. It does **not** measure RPO, prove that every expected posting survived, or
prove consistency with balances, transactions, settlements and pending events. An empty
or incomplete ledger can still balance. Do not treat this check alone as full money-path
recovery evidence or a measured customer-facing RTO.

## Execution and cleanup

The workflow creates `dr-verify-<run-id>-<attempt>` and records whether it created that
namespace. Cleanup deletes only a namespace created by the current attempt; a naming
collision must fail without deleting the existing namespace. It stops and reaps the
port-forward process on success and failure. Namespace deletion has a bounded wait;
a deletion error fails the run instead of hiding an orphaned restore namespace.

The manifests live in `openbank-infra/gitops/dr-restore-templates/`. The ledger image is
read from the existing Rollout. The check connects directly to the generated Deployment,
which has no Service. HTTP connection retries allow the port-forward listener to start;
a fixed delay does not prove readiness. Database restore and workload readiness retain
bounded Kubernetes waits.

The intended isolation is described in the template README. A deployment that merely
sets outbox dispatch off must not be assumed to disable all consumers, schedulers or
outbound clients. Verify actual startup, authorization, network isolation and side
effects before accepting a live drill. Local orchestration tests exercise shell control
flow with fake Kubernetes/HTTP boundaries; they neither restore data nor prove isolation.

## Remaining production proof

- Run the restore with verified least-privilege backup access and isolated network paths.
- Capture a known source watermark and verify retained records, not only balanced totals.
- Measure RPO against the chosen recovery point and the agreed acceptance threshold.
- Restore and reconcile the whole money path, including replay/deduplication and pending
  outboxes, without sending effects into live payment or notification systems.
- Record measured recovery times, consistency checks and drill artifacts in the DR log.

The quarterly schedule is a trigger, not evidence these acceptance conditions passed.

## Check workload network boundary

The trusted Kyverno namespace bootstrap creates `ledger-dr-check-isolation` for
new `dr-verify-*` namespaces. The DR runner has only `get` permission for that
policy name, no network-policy write permission. It polls up to 60 times, two
seconds apart, with a five-second API request timeout, before creating any restore workload. Missing bootstrap fails the drill
and cleans up the attempt's namespace; the runner cannot grant itself access.
The deployed chart's background controller already owns network-policy generation;
this change adds no controller permission or scanner exception.

The generated policy selects only the ledger check pod, denies pod ingress and
allows egress only to the same namespace's restored CNPG pods on TCP 5432 and
kube-system DNS pods on TCP/UDP 53. CNPG pods are not selected, so backup access
requires its own boundary. The check pod mounts no Kubernetes API token.

Creation is not proof that the CNI has programmed the policy. The cluster's
standard enforcement mode can initially allow traffic while rules are installed.
A live drill must prove enforcement from startup and actual DNS/database access;
neither these manifests nor fake-boundary tests establish runtime isolation.
Startup, authorization, write isolation and full money-path recovery remain open.
Port-forward and kubelet readiness are not pod-to-pod ingress tests.
