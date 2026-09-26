# Local settlement-to-ledger-to-balance proof

`settlement-real-services-e2e.py` runs the settlement, ledger, and balance Quarkus services as
separate JVMs against isolated local dependencies, with an optional fourth audit-service JVM. It exercises a real settlement origination,
Temporal workflow, Kafka journal projection, Keycloak OIDC, and the checked-in OPA bundles. It also
checks the cover hold lifecycle, settlement protocol, exact projected balances, duplicate
idempotency, posted journal legs, and service-account authorization log evidence.

This is a **local integration proof only**. It does not exercise external payment
rails, full disaster recovery, or a sandbox deployment, and its result is not evidence for those
paths.

## Prerequisites

- A running Docker daemon and Docker CLI. The script respects the caller's `DOCKER_HOST` setting.
- Python 3.10+ with PyYAML installed (`python3 -m pip install PyYAML`).
- JDK 25 available through `JAVA_HOME` or `PATH`.
- Enough local memory for PostgreSQL, Valkey, Keycloak, Redpanda, Temporal, three OPA containers,
  and three Quarkus JVMs.

Build the service fast-jars from the repository root before running the proof:

```sh
./gradlew :openbank-ledger-service:quarkusBuild \
  :openbank-balance-service:quarkusBuild \
  :openbank-settlement-service:quarkusBuild
```

The script does not build services or pull/deploy to any shared environment. Docker pulls the
declared local dependency images when they are not already present.

## Run

From the repository root:

```sh
python3 openbank-infra/scripts/settlement-real-services-e2e.py
```

The run creates synthetic users, service clients, passwords, account IDs, and a temporary evidence
directory with mode `0700`. Published container ports and service/management listeners bind to
`127.0.0.1`. The script records service and dependency logs plus `result.json` in the printed
evidence directory. Before origination it checks that an unauthenticated balance read returns 401
and an unrelated synthetic `ROLE_API` service identity is denied a hold request with 403. It then terminates
the JVMs and removes every container it created, including on failure. Check the final
`PASS real service flow` line and `result.json`; failed runs retain their logs for diagnosis. The
result records the Git HEAD, whether the working tree was dirty, and SHA-256 digests of the three complete
fast-jar runtimes (application, launcher and libraries). This is provenance metadata only; it does not assert that the jars were built from
that checkout. Service JVMs inherit only `PATH`, `HOME`, `TMPDIR`, `LANG`, and `TZ` before the
explicit local proof configuration is applied.

Operator sessions obtain tokens on first use and renew before the issuer's reported expiry.
An HTTP 401 still fails the proof; the client never automatically replays a business write.

## Lost response after ledger commit

```sh
python3 openbank-infra/scripts/settlement-real-services-e2e.py --drop-ledger-response
```

This mode routes only the settlement ledger client through a loopback HTTP proxy. The proxy forwards
real authenticated journal requests and closes the first connection only after the real ledger
returns a successful `POSTED` journal. Later requests reach the same ledger normally. The proof
requires at least two successful upstream posts returning the same journal ID, exactly one dropped
reply, a completed settlement, one journal, final balances of 60/40 CZK and released cover.
`result.json.responseLoss` records these observations without request bodies or credentials.
The ordinary mode remains a direct connection. This fault case proves retry after one lost reply;
it does not prove recovery after exhaustion of all retries or a process crash.

Use `--drop-ledger-response 5` to lose every successful reply across all five Temporal activity
attempts. This case requires `LEDGER_STATE_UNKNOWN` rather than `BOOKED`, exactly five posts returning
one journal ID, and the same 60/40 balances with the consumed cover released by projection. It proves
that exhausted retries preserve uncertainty without refunding a committed transfer. Operator
reconciliation after that state and process-crash recovery remain separate acceptance cases.

## Recover the exhausted workflow

```sh
python3 openbank-infra/scripts/settlement-real-services-e2e.py \
  --drop-ledger-response 5 --recover-after-loss
```

After proving `LEDGER_STATE_UNKNOWN` with one posted journal and correct balances, this mode waits
for the original Temporal execution to complete. It selects the workflow-task completion that
scheduled `BookToLedger` and resets that exact run at that event. The recovered activity uses the
original settlement ID and ledger idempotency key. The proof requires `BOOKED`, exactly one journal
with the original ID, six successful posts total (five lost responses and one recovery response),
and unchanged balances and balance versions. It also requires a different Temporal run ID and one
durable outbox fact for `LEDGER_STATE_UNKNOWN` → `BOOKED`. The evidence directory retains histories
for the explicit original/recovered executions and the
reset command result. This is a controlled local recovery exercise; it does not reset any shared
workflow or prove production operator authorization.

## Verify delivery into the audit service

Build `:openbank-audit-service:quarkusBuild`, then add `--with-audit` to any of the modes above.
This starts a fourth real JVM, its own PostgreSQL database and the checked-in audit OPA bundle.
The local broker contains the audit consumer's configured topics; the fixture selects `earliest`
so records emitted before consumer readiness remain visible. The synthetic operator additionally
receives `ROLE_AUDITOR` only in this mode.

The proof compares the complete JSON payload of every settlement outbox event with the persisted
`SETTLEMENT_STATE_CHANGED` audit entries for the same settlement. Missing, duplicate or changed
entries fail. It then calls the authenticated `/api/v1/audit/integrity` endpoint and requires an
intact chain, no unchained records and a non-empty checked count covering those events.
`result.json.audit` records the counts and integrity response. This proves local Kafka delivery and
chain persistence; external signed anchors, archival retention and disaster recovery remain separate.

## Insufficient cover before ledger booking

Add `--reject-cover` to originate a second settlement for 61 CZK after the payer has 60 CZK
available. The real balance service refuses the reservation. The proof waits for the workflow
to close, verifies that no BookToLedger activity was scheduled, no journal or hold exists,
and balance versions are unchanged after idempotent replay. Exactly one durable
BALANCE_STATE_UNKNOWN outbox fact must exist. The completed history is retained.

This verifies the current conservative failure state, not a terminal business rejection or
automatic reconciliation. A failed cover activity is still reported as BALANCE_STATE_UNKNOWN;
this test does not claim that distinction has been resolved or exercise lost cover replies.

## Lose every committed cover response

```sh
python3 openbank-infra/scripts/settlement-real-services-e2e.py --drop-cover-responses
```

This standalone three-service mode routes the settlement balance client through the loopback
fault proxy. It drops all five replies only after the real balance service returns a successful
active hold. Every upstream response must identify the same hold. The closed workflow must
record BALANCE_STATE_UNKNOWN without scheduling BookToLedger. The proof requires one active
40 CZK hold, no settlement journal, payer booked/reserved/available amounts of 100/40/60,
zero payee balances, and unchanged balance versions after origination replay. One durable
uncertainty outbox fact and the completed workflow history are retained.

This is the counterpart to insufficient cover: both can leave a failed activity, but here
money really is reserved. It proves safe retention, not completed reconciliation or cancellation.
Do not turn an exhausted reservation request into a blind release or terminal rejection.

## Crash the settlement worker after journal commit

```sh
python3 openbank-infra/scripts/settlement-real-services-e2e.py --crash-worker-after-ledger-commit
```

The loopback proxy waits for a real POSTED ledger response, then sends SIGKILL only to the
settlement JVM owned by this run, before forwarding that response. The fixture reaps that
process, verifies exit code -9, and starts the same runtime with the same local configuration.
All other process and container health checks remain active. Recovery must finish the same
Temporal run without reset, with journal activity attempt 2 and a recorded 60-second
Start-To-Close timeout. The normal one-journal, two-leg, 60/40 balances and released-cover
assertions still apply. Original and recovered histories are retained separately.

`--with-operator-approval` enables the settlement origination four-eyes gate and uses two
separate, real OIDC operator sessions. Each origination checks that no settlement was inserted
while approval is pending, refuses self-approval and a checker-supplied changed amount, then
approves and executes the unchanged instruction. The proof requires the three durable approval
transitions and retains their IDs. Idempotency replays use fresh approvals and must return the
same settlement; consuming one approval does not authorize another execution.

Combine it with `--with-audit` to compare the complete maker/checker event payloads from the
settlement outbox to independently ingested audit rows. This mode gates settlement origination;
it does not claim that the ledger funding fixture has its separate human four-eyes gate enabled,
or that the deployed operator UI has been accepted.
