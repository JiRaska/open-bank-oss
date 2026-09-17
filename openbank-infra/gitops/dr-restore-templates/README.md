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

The workflow creates `ledger-check-network-policy.yaml.tmpl` before either restore
manifest. A creation failure aborts before the workload starts and cleans up the
attempt's namespace. The policy selects only the ledger check pod, denies incoming
pod traffic and permits outgoing TCP 5432 only to `ledger-db-restored` pods in the
same namespace, plus TCP/UDP 53 to kube-system DNS pods. It grants no egress to live
services or external endpoints. CNPG pods are not selected: backup access and
replication require their own boundary. The check pod mounts no Kubernetes API token.
The DR runner receives only `create` for NetworkPolicies; namespace deletion handles
cleanup. Port-forward and kubelet readiness are not pod-to-pod ingress tests.

Policy creation is not proof that the CNI has programmed it. In particular, the
cluster's documented standard enforcement mode can initially allow traffic while
pod rules are being installed. A live drill must prove enforcement from startup
and actual DNS/database access before using restored data; do not label this
manifest or the fake-boundary tests as runtime isolation evidence. The remaining
startup, authorization, write-isolation and full money-path recovery requirements
above still apply.

The additional create grant is constrained by the native
`openbank-dr-network-policy-scope` ValidatingAdmissionPolicy and Deny binding.
GitOps sync waves install the policy and binding before the RBAC grant. This
requires Kubernetes with the `admissionregistration.k8s.io/v1` policy API; validate
its live admission behavior before deploying the grant outside GitOps. Unlike a
webhook rule, this boundary does not depend on Kyverno namespace filters.

Offline policy regression (requires a separately installed Kyverno CLI):

```sh
python3 .github/scripts/test-dr-network-policy-admission.py --kyverno /path/to/kyverno
```

The seven cases exercise native policy evaluation for denied live/system
namespaces, denied empty DR suffix, an allowed DR namespace and an unaffected
other identity. This test is separate from the CI orchestration tests and must be
run explicitly; neither verifies live admission registration or CNI behavior.
