# DR restore templates (docs/bcp/automated-dr-restore.md)

Templates for the two manifests `dr-restore-verify.yml` needs, applied into a
throwaway `dr-verify-<run-id>` namespace and torn down at the end of the run.
Design and full context: `docs/bcp/automated-dr-restore.md`.

`${NS}` is substituted by the workflow (`envsubst` or `sed`) at apply time — the
templates are not applied as-is.

## Status — NOT verified against a live restore

These are derived from `openbank-infra/gitops/components/ledger/postgres.yaml` and
`.../ledger-service.yaml`, stripped to what a read-only trial-balance check needs, and
from reading `application.yaml`'s profile toggles for the pieces that must be off. They
have not been applied to a real cluster and booted — that needs the cluster-capable
runner tracked separately in #4757, and no session without one should claim this is
proven. Treat a first real run as the actual test, not this file.

## What was deliberately left out of the ledger-service ephemeral Deployment

Compared to the live `Rollout` (`ledger-service.yaml`), all removed for a reason stated
in `docs/bcp/automated-dr-restore.md`'s isolation section:

- **No Kafka.** `openbank.outbox.dispatch-enabled: "false"` stops the outbox dispatcher
  from ever calling the emitter, so the `KAFKA_*` env block (mTLS keystore/truststore
  secrets that do not exist in a throwaway namespace) is not needed to boot. Left
  entirely unset rather than pointed at PLAINTEXT — a restored pod must not be able to
  publish, even by accident, and an unreachable broker at default config is a boot
  hazard this avoids outright.
- **No OIDC.** `QUARKUS_OIDC_TENANT_ENABLED=false` disables the resource-server tenant.
  The only call this Deployment needs to serve is the read-only trial-balance endpoint;
  requiring a live Keycloak reachable from a throwaway namespace would be one more
  cross-namespace dependency for zero benefit.
- **No Redis.** The four-eyes `ApprovalStore` is not exercised by a read-only restore
  check. `QUARKUS_REDIS_HOSTS` left unset; if this later needs `POST` paths, add it
  back rather than assume it is unused forever.
- **No OPA sidecar.** `AUTHZ_ENFORCE` defaults `false`, and the sidecar exists to
  enforce the base allow/deny; a namespace with one throwaway pod calling itself
  in-cluster is not the surface that decision protects.
- **Plain `Deployment`, not `Rollout`.** No canary makes sense for a pod that lives for
  one CI job and is deleted at the end of it; Argo Rollouts CRDs may also not be
  installed in every runner's target context.

## Image

The template pins no tag. The workflow step must set it to the same image the LIVE
`ledger-service` Rollout is currently running (`kubectl get rollout ledger-service -n
ledger -o jsonpath='{.spec.template.spec.containers[0].image}'`) at apply time — a
stale pinned tag here would silently test a different build than the one in
production, which defeats the point of a restore drill.
