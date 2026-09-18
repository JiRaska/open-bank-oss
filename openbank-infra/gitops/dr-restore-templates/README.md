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

The Deployment disables all Quarkus scheduled jobs, outbox dispatch and Flyway
startup migration. It verifies a drill-specific viewer JWT locally, without OIDC
discovery or outbound token acquisition. Its mounted `ledger-dr-check.properties`
excludes only Redis and the outgoing ledger channel from readiness checks because
this viewer-only read uses neither. Both PostgreSQL health checks remain active.
These settings do not remove Kafka connectors or outbound clients from the image;
network isolation remains a separate prerequisite.
The checker uses a separate CNPG-managed reader role; scheduler shutdown is additional
protection against unwanted work. Before a live drill, verify the actual image's startup
requirements and enforce network and write isolation. Do not add live credentials
just to turn a failed probe green.
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

## Recovery identity

The external-cluster alias identifies the recovery source inside the manifest.
`barmanObjectStore.serverName` explicitly selects the original CNPG archive name;
it must match the source backup configuration, or the source cluster name when
that configuration omits it. Recovery also declares the original application
database and owner so CNPG does not default them to `app`. The regression test
compares these values with the source ledger manifest. This does not establish
cloud credentials, archive availability or a successful restore.

## Temporary viewer authentication

Each attempt generates its own RSA key and a one-hour JWT restricted to `ROLE_VIEWER`,
issuer `urn:openbank:dr-check` and audience `openbank-dr-check`. The signing private
key is deleted immediately after signing. Only the public verification key enters
the namespace in `ledger-dr-check-auth`; the token remains in a mode-0600 header
file in a mode-0700 temporary runner directory. Curl reads that file rather than
putting the bearer value into command arguments. Cleanup removes it on success
and failure. No live issuer, client secret or operator role is used.

`DrOfflineAuthenticationIT` exercises the real bearer verifier against temporary
infrastructure: viewer read, anonymous/expired rejection and mutation rejection.
It also runs with PostgreSQL only and the actual mounted properties file, with
Kafka and Redis pointed at an unavailable local port; readiness must stay UP and
retain its database check. Without the DR properties, readiness returns 503 while
the authenticated trial-balance read succeeds. This is source-level regression
proof, not a deployment test of the selected live image, CNI isolation or
the deployed role's write permissions. The Python workflow suite verifies signing, tamper rejection,
credential permissions and teardown through the workflow shell.

## Database reader identity

CNPG manages `ledger_dr_check` with only `pg_read_all_data` membership and no
superuser, database-creation, role-creation, replication or RLS-bypass privileges.
The checker mounts `ledger-dr-check-db`, never the restored owner's application
credential. Read access covers the restored cluster; it is not a table-specific
privacy boundary and does not bypass RLS.

The trusted Kyverno controller generates that Secret only from the CNPG-created
`ledger-db-restored-app` Secret in a nonempty `dr-verify-*` namespace. Its password
is SHA-256 of `ledger-dr-check:` plus the source password's base64 representation.
The source is CNPG's machine-generated random password; this derivation is not
suitable for human passwords. It produces a distinct, purpose-separated credential
without revealing the owner password to the reader. Missing/empty source passwords
are rejected. `synchronize: false` keeps this one-run credential stable. Namespace
teardown removes both credentials. The runner receives no new Secret API permission;
its existing broad workload-creation permissions are unchanged.

The application can become ready only after CNPG reconciles the role and password.
Local tests use the same role privileges and prove SELECT succeeds while UPDATE,
DELETE and SET ROLE to the owner fail with SQLSTATE 42501. Their fixture initializes
a fresh database using separate Flyway owner credentials; the real restore pod has
Flyway disabled and receives no such credentials. Actual restored-role memberships,
object grants, ownership and source functions must still be inspected during the
live drill; declared managed roles are not proof about every privilege in a backup.
