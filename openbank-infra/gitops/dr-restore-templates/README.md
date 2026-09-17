# DR restore templates (docs/bcp/automated-dr-restore.md)

Templates for the restore manifests `dr-restore-verify.yml` needs, applied into a
throwaway `dr-verify-<run-id>` namespace and torn down at the end of the run.
Design and full context: `docs/bcp/automated-dr-restore.md`.

`${NS}` is substituted by the workflow (`envsubst` or `sed`) at apply time — the
templates are not applied as-is.

## Status and isolation limits

The scheduled workflow uses the `openbank-dr` runner and checks its access before
creating resources. A configured runner and passing orchestration tests do not prove
that these templates successfully boot or isolate restored data. Accept a drill only
with evidence from the actual restore.

The template creates a Deployment, with no Service. The workflow forwards directly
to that Deployment. It reads the image from the existing ledger Rollout and waits for
both the restored database and check workload before requesting the trial balance.

The Deployment disables outbox dispatch, OIDC and Flyway startup migration. These
settings express an intent; they do not remove Kafka connectors, Redis health checks,
other schedulers, endpoint role checks or outbound clients from the image. Before a
live drill, verify the actual image's startup requirements and enforce network and
write isolation. Do not add live credentials just to turn a failed probe green.
The workflow's `balanced` assertion and elapsed time do not measure RPO or prove
cross-service consistency; see the design document's remaining acceptance conditions.

## Image

The template pins no tag. The workflow step must set it to the same image the LIVE
`ledger-service` Rollout is currently running (`kubectl get rollout ledger-service -n
ledger -o jsonpath='{.spec.template.spec.containers[0].image}'`) at apply time — a
stale pinned tag here would silently test a different build than the one in
production, which defeats the point of a restore drill.

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

Offline bootstrap regression (requires a separately installed Kyverno CLI):

```sh
python3 .github/scripts/test-dr-network-policy-generation.py --kyverno /path/to/kyverno
```

The seven cases verify generated resources for DR namespaces and no generation in
live/system namespaces or with an empty suffix. This is separate from the ordinary
CI orchestration tests and must be run explicitly. It does not contact a cluster
or prove that its controller is installed and healthy.
