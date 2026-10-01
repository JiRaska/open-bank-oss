# DR restore templates (docs/bcp/automated-dr-restore.md)

Templates for the two manifests `dr-restore-verify.yml` needs, applied into a
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
