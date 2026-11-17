# FINREP frozen source pilot

Tracking: #16356. F01.01 assets only, for offline comparison against approved figures.

The ledger endpoint `GET /api/v1/ledger/periods/MONTH/{date}/frozen-closing-balance`
returns cumulative stock through the close from hash-verified frozen monthly movements.
It is the accounting source for stock figures. The current ledger client requests it by date,
without a reporting context. That response alone cannot be used to label figures as belonging to
a different legal entity, FOS activity, branch or group. A source owner must produce and reconcile
a frozen close for the exact approved accounting perimeter before publishing this input.

A schema-1 input contains the period-end date (`asOf`), the producer's published close evidence
identifier, a versioned reporting scope and a CZK trial balance. The scope contains the context,
legal entity, operating mode, home and host countries, establishment when applicable and accounting
perimeter version. The trial balance carries the producer's balance verdict and the GL lines
(code, account type, exact decimal net and currency). This pilot consumes pre-aggregated GL lines.
The operator cannot derive an approved context by assigning labels to a date-only ledger response.

| Input | Owner and publication rule |
| --- | --- |
| Reporting context and perimeter version | Regulatory governance approves effective dates, legal entity, establishment and scope. The close producer must pin that exact approved version. |
| GL account code, type, currency and decimal net | Ledger publishes frozen stock per reporting perimeter. Derive net as debit minus credit. Reconcile to the attested close and retain source period IDs and content hashes with the close evidence. |
| FOS/branch attribution | Source bookings must carry an approved historical booking context. If the stock cannot be reproduced at that grain, treat the snapshot as unavailable. |
| Expected digest | Approval records bind the bytes to the requested context and date. Store approval independently of the artifact. |

Run from an isolated workstation or worker with the input already staged locally:

```sh
./gradlew :openbank-finrep-service:renderFrozenFinrep \
  --args='snapshot.json APPROVED_SHA256 approved-context 2026-09-30 result.json'
```

The digest must be obtained through an independently authenticated approval channel.
The task checks the digest, requested context and period, and requires both the producer verdict and
FINREP's recomputed balance identity to agree. The output carries provenance and the existing
mapper's limitations. Treat the file as a restricted comparison artifact. The runner opens no
network connections or database pools. Its input, line count and worker heap are capped. The Gradle
build may need to resolve dependencies, so prebuild the runtime before any isolated deployment.

Remaining delivery: an authoritative producer and immutable handoff, permission checks and scoped
artifact delivery, production worker quotas and scheduler, audit and reconciliation including
completeness beyond balance identity, and measured customer-path SLOs during close/export bursts,
backfill and outage/recovery. The 3 million client target is a benchmark requirement. Keep producer
CPU, IO and replay independently budgeted and exercise multiple legal entities plus FOS and branch
runs for the same report and period.

## Scoped close export producer

`scripts/regulatory/scoped_trial_balance.py` ingests an already staged, cumulative closed journal
export. Each NDJSON record has a contiguous integer `sequence` starting at 1, approved
`contextId`, GL `code`, `accountType`, `currency` and signed integer `netCents`.
Each amount is debit minus credit. Supply individual debit or credit lines to preserve gross
control totals. The pilot accepts CZK and expects approved branch clearing entries in its inputs.
For each context the independently approved manifest binds its scope and close evidence to the
expected record count and gross debit/credit totals. It also binds the complete export by SHA-256.
The approved manifest digest is obtained independently of the staged files. A balanced export
alone cannot establish correct attribution; reconciled, reviewed producer controls are required.

```sh
python3 scripts/regulatory/scoped_trial_balance.py \
  manifest.json APPROVED_MANIFEST_SHA256 journal.ndjson snapshots
```

The command checks for gaps, unapproved contexts, currency mismatches and control discrepancies.
It keeps only GL aggregates in memory and publishes the snapshots and digest index together after
validation. Consumers use the index only after accepting the producer output through the approved
handoff. An interrupted run can replay the same frozen input into a fresh directory. The pilot
does not checkpoint mid-file. Replay defaults to a ceiling of 50,000 records per second in small
non-accumulating batches. Tune the ceiling against measured worker and shared resource budgets.
Store the input and output in a restricted worker volume.

## Synthetic replay benchmark

```sh
python3 scripts/regulatory/benchmark_scoped_reporting.py /path/to/new-directory --rate 750
```

The harness creates one balance row and two journal lines per synthetic client, with one domestic
company and FOS and branch scopes for a second company. It measures a fixed-rate mix of 90% point
reads and 10% committed writes against a SQLite stand-in before and during a full journal replay.
It terminates the first producer and restarts from the frozen export. Read `full_sample_overlap`
before comparing latency. Percentiles are measured from intended request arrival, so local
scheduling delays are included.

This is a local capacity experiment on the staged-file producer, with a same-host SQLite stand-in
for client traffic. It does not exercise the production ledger, real Kafka catch-up, snapshot
publication from the operational source, or the bank's client APIs. Production acceptance needs
bank-equivalent rates and mix, actual dependency quotas and client SLOs, and measured source cost
during export, replay and recovery. No legacy booking attribution is inferred. ADR-0380 B2/C11
remains the dependency for a trustworthy journal export from actual banking activity.

Local observations on the same six-million-record synthetic export at 750 requested operations
per second. Each latency sample covered 12 seconds while replay was active.

| Replay | Baseline p95 / p99 | Concurrent p95 / p99 | Replay elapsed |
| --- | ---: | ---: | ---: |
| Initial uncapped prototype | 1.788 / 6.002 ms | 3.249 / 96.539 ms | 59.376 s |
| 50,000 records/s ceiling | 0.801 / 2.305 ms | 0.744 / 3.469 ms | 151.539 s |

The capped run produced byte-identical snapshots and index. The FINREP runner returned
CZK 1,000,000, 2,000,000 and 3,000,000 in F01.01 r0380 for the domestic, FOS and branch
contexts, respectively. Local baseline noise differs between runs. Even with the cap the
observed p99 was higher than its paired baseline. The cap is a starting point for testing,
with no production non-interference claim.

## Ledger history and deployment sequence

Ledger V45 adds optional company, approved context version, operating mode and branch identifiers
to the journal row. The context version must identify the approved historical booking context,
including the market for FOS. The application preserves the attribution on reversal and rejects a
changed attribution on an idempotent retry. V44's posted-row guard protects the added columns.
The check constraint rejects partially populated records and requires the branch ID in branch mode.
The migration adds nullable columns without backfilling or validating the old partitions.

The posting application port is ready to accept attribution from a trusted booking producer.
The public posting request has no new attribution input. Wire the trusted booking decision
before activating the feature, and preserve the approval version through the transactional export
path. Existing records are unknown. A historical overlay needs independent evidence and
reconciliation, with no rewrite of posted journals. Report production must stop for unknown
attribution within the requested scope.

Apply V45 before deploying the new writer. Until trusted inputs are enabled, an older application
can resume with the additional columns in place. Once attributed journals exist, keep an
attribution-aware writer for reversals. Reverting to a writer that drops the original context
would produce incomplete reporting evidence. Do not remove the columns during rollback.

The replay harness now measures the interval with reporting down and continuously samples client
operations until replay exits. It repeats the same number and sequence of client operations with
the worker stopped for the baseline. It still uses a local SQLite stand-in and an already staged
export. Production acceptance must measure actual account, balance and payment APIs in an
isolated stack during source export, reporting outage and every interval of catch-up. Compare
latency/error and throughput deltas by endpoint against agreed limits, including any contention
in source WAL, storage, database pools and broker retention.

Full-duration local run at 750 scheduled SQLite operations/s with 3 million synthetic clients
and a 6 million record export:

| Phase | Operations sampled | p95 | p99 |
| --- | ---: | ---: | ---: |
| Worker stopped, matched baseline | 109,432 | 99.07 ms | 198.12 ms |
| Synthetic export file creation | 23,653 | 257.54 ms | 367.95 ms |
| Reporting down for 12 s | 9,000 | 108.49 ms | 186.80 ms |
| Full capped replay, 145.95 s | 109,432 | 154.17 ms | 589.51 ms |

The full replay regenerated byte-identical outputs. The export file took 31.57 s. Ledger test
processes also ran on the shared workstation, and the export phase was measured in a separate
run. These are diagnostic timings, with no attribution of the latency deltas to reporting alone
and no HTTP measurements. To repeat the export-file phase against an existing synthetic fixture,
pass `--reuse-input --measure-export-only` to the benchmark.

## Customer Edge measurement

Run `perf/k6/regulatory-customer-edge.js` against a dedicated synthetic environment exposed
through a loopback endpoint. Provision three million clients and a representative payment history
in the target. Keep the runner's synthetic fixture outside Git. Each JSON array element supplies
`accountId`, `paymentId` and a short-lived `token` for the customer who owns both.
Ensure the tokens cover the entire experiment and schedule rates and VUs from the agreed traffic
profile. The scenarios read account detail, account balance and domestic payment status through
Customer Edge at constant arrival rates. Run payment initiation load separately when it is part
of the workload profile, using dedicated funded test data.

```sh
k6 run --out json=customer-edge.ndjson perf/k6/regulatory-customer-edge.js
python3 perf/k6/summarize-regulatory-customer.py customer-edge.ndjson phase-windows.json
```

Set `EDGE_URL` to the Customer Edge API base (including its base path), `CUSTOMER_FIXTURE`
to the fixture path, plus `RATE_PER_ENDPOINT`, `VUS_PER_ENDPOINT` and `DURATION`.
Use one run spanning all four phases, with warm-up and a short buffer after catch-up. Record UTC
start and end timestamps independently from the export and worker controllers as JSON arrays
under `baseline`, `export`, `outage` and `catch_up`. End catch-up when the worker confirms
the expected final checkpoint and reconciled output. The summarizer reports p50/p95/p99,
non-200 or transport error rate and dropped iterations per route and window. Samples are
attributed by request completion time. Compare the achieved arrivals with the scheduled rate
for each window and account for dropped iterations before comparing latency.

The source dependency is ADR-0380 C2/C3 and B2/C11. Product Catalog must provide an immutable
approved market grant version. Account and loan origination must persist the consumed decision
with the contract, and the posting producer must resolve that decision from trusted booking
facts. Ledger cannot infer the approval from an account ID, caller role or requested branch.
Cross-context journals also need approved inter-branch clearing and attribution rules before
the journal-level pilot can be enabled for them. Once the source path is wired, build exports
from reconciled scoped closes and run the drill before activation.

Use `scripts/regulatory/export_closed_ledger.sql` on an isolated immutable PostgreSQL copy
of the reconciled close. Run psql with `-XqAt -v ON_ERROR_STOP=1 -v as_of=YYYY-MM-DD` and
capture stdout as NDJSON. Discard the file if psql exits unsuccessfully. This query scans and
sorts the full cumulative population and requires a dedicated database with its own IO budget.
It includes posted originals and reversals, excludes synthetic journals and preserves exact
signed minor units. The approval manifest uses the immutable booking context version UUID as
`scope.contextId`. The producer checks the exported company, mode and branch against the
approved manifest. An unresolved context, missing line, missing GL classification, foreign currency
or non-cent amount prevents publication. The manifest still requires independently reconciled
controls and an approval digest. The exporter does not verify the close or approve the manifest.

## Booking grant in Product Catalog

Product behaviour comes from Product as Code (ADR-0352). Account opening already pins
the published Product Catalog revision and records its canonical signed IR revision hash
when present. Use that pin's revision ID as the grant's `productRevisionId`. Persist the
product pin and booking receipt together when completing the account. The receipt records
the separate compliance and accounting decision for that exact revision. Payments use the
account's persisted attribution so a later product release cannot reclassify historical
bookings.

The opt-in `market-booking` pack registers version 1 of
`org.openbank.market.booking-grant` through the existing Catalog v2 revision lifecycle.
A draft binds a legal entity, booking book, product revision, operating mode and approval
evidence hash. FOS requires a host country and has no foreign establishment. A branch
requires both a host country and an establishment identifier. The domestic mode uses the
home country. The catalog approval policy requires an effective start and independently
authenticated compliance and accounting approvers. Existing catalog publication binds the
approved attributes and interval to the content digest.

Enable the pack by adding `market-booking` to the existing pack list in a dedicated test
deployment. The configured approval authorities require both the role and the respective
`market:compliance-approve` or `market:accounting-approve` scope. The pack defines no grants
or authorization assignments. A published grant revision ID is the candidate for ledger
`booking_context_version_id`. The consumer must still validate the legal-entity and book
references, home/host compatibility, existence and suitability of the referenced product
revision, and resolve a single eligible grant. C3 still needs to consume that decision
atomically with revocation and preserve the evidence through account opening and booking.

Catalog migration V17 adds an immutable booking receipt and a transactional consume operation.
Call it in a short READ COMMITTED transaction with the durable account-opening operation ID,
verified subject, pinned product revision and independently selected grant revision. It locks the
grant row before checking the active interval and records the exact attributes and approval digest.
Concurrent publication or effective-interval changes contend on that row. A committed retry returns
the original receipt even after supersession, and a retry with different bound facts is rejected.

The account service can obtain the receipt via `POST /api/v2/booking-grant-receipts`. In the
catalog deployment set `openbank.catalog.booking.account-client` and
`openbank.catalog.booking.account-subject` to the issuing OAuth client's ID and the dedicated
service account's subject. Both must match the verified bearer. With either setting absent,
the endpoint rejects the caller. Apply the additive migration before configuring access.
C3 still needs trusted grant selection and an account-opening operation that persists its inputs
before consumption and resumes from the original receipt after a restart. For rollback, stop
callers and retain the committed receipts.

Account Service migration V35 adds the storage for that sequence. Commit the pending
operation with the idempotency key, verified party, product pin and selected grant before
calling the catalog. Complete the account, idempotency record, booking receipt and
AccountCreated outbox row in the existing account-opening transaction. The receipt insert
checks the party, request fingerprint and pinned product revision, including the signed IR
hash when present. An interrupted call can resume the original operation using the same
grant and pin. Account Service's internal `openBookedAccount` use case consumes that durable operation
and writes the receipt with the account. The trusted market-admission producer that selects
and commits the operation, and onward propagation into payment and ledger, still need wiring.

A booked opening includes `bookingContext` (schema version 1) in its transactional
`AccountCreated` event. The grant revision UUID identifies the immutable context version.
Attribution contains the legal entity, booking book, operating mode and optional establishment.
The evidence digest stays in the account receipt. Existing and unclassified openings have no
context. Payment must distinguish an unclassified account from a projection that has yet to
receive its event and must preserve the resolved context before sending a journal. When
both legs use different booking scopes, the journal needs approved clearing entries and
attribution for each leg.

Transaction Service projects the account event by account identity and synthetic origin.
An identical Kafka replay is idempotent. A conflicting replay reaches the channel's DLQ.
A row with a null booking context records an observed unclassified opening. An absent row
signals incomplete projection and stops pilot posting. Two account legs may share one journal
only when their complete contexts agree. Attributed clearing for different contexts is still open.

Keep `ACCOUNT_BOOKING_PROJECTION_ENABLED` and
`openbank.transaction.booking-posting.enabled` off until a dedicated rehearsal. Bring up the
consumer first, load and reconcile accounts whose original Kafka events aged out, and verify
consumer lag and DLQ before enabling posting. Route pilot traffic to a cohort with reconciled
source and target account coverage. Drain in-flight payment activities before switching posting
mode. A dated copy of the ledger feeds reporting with separate compute and storage limits.

For the Customer Edge read benchmark, apply each service's Flyway migrations to a dedicated
database named `ob_perf_accounts`, `ob_perf_balances`, `ob_perf_domestic` or `ob_perf_parties`. Run
`psql -X -v clients=3000000 -f perf/k6/seed-regulatory-customer.sql` once against each database.
The script creates one account, balance and settled domestic payment per synthetic party. IDs
are PostgreSQL UUIDs derived from `md5('regulatory-perf/party/' || n)`,
`md5('regulatory-perf/account/' || n)` and `md5('regulatory-perf/payment/' || n)`, respectively,
for each ordinal `n`. Use these IDs in the k6 customer fixture after issuing matching tokens
from the isolated issuer. This data seeds the read APIs. The reporting worker still needs its
own attributed ledger fixture and an independently logged export, outage and recovery schedule.

### Isolated Customer Edge read benchmark, 2026-11-17

Local Docker hosted the real Customer Edge, Party, Account, Balance and Domestic Payment
services behind an internal network. PostgreSQL held 3,000,000 rows each for parties,
accounts, balances and settled payments. Local Keycloak issued tokens for 64 sampled
customers. k6 sent 30 GET requests per second to each of the three Customer Edge routes
continuously throughout the run. JWT and customer ownership checks were active. OPA ran
in advisory mode and the domestic Temporal worker was disabled for this read-only drill.

The reporting container had its own storage, no network, a two-CPU quota and a 1 GiB
memory limit. It exported six million synthetic journal lines, then was stopped to simulate
an outage. After the outage it replayed the entire frozen export at a 50,000-lines/s cap
and produced balanced trial balances for the domestic, FOS and branch fixtures.
The reporting worker used synthetic frozen input; this drill did not post payments or
read from an operational ledger.

Each API cell shows p95 / p99 in milliseconds, followed by HTTP failures / requests.

| Phase | Seconds | Account | Balance | Payment |
| --- | ---: | ---: | ---: | ---: |
| Baseline | 45.0 | 20.5 / 36.5; 0/1353 | 41.5 / 69.0; 0/1352 | 31.6 / 52.4; 0/1352 |
| Export | 9.7 | 13.8 / 22.6; 0/290 | 24.6 / 35.7; 0/290 | 20.4 / 31.6; 0/290 |
| Reporting outage | 45.0 | 11.0 / 204.5; 8/1350 | 21.6 / 156.7; 10/1351 | 16.3 / 176.9; 10/1351 |
| Complete replay | 143.7 | 10.3 / 22.3; 0/4310 | 19.3 / 38.4; 0/4310 | 15.2 / 34.1; 0/4310 |

There were 21,909 requests in the four measured windows and zero dropped iterations.
All 28 failures were HTTP 500 responses completed within an 18 ms interval during the
outage phase. Customer Edge logged `ConnectionPoolTooBusyException` in the Vert.x Redis
client, with a full wait queue of 24. The run does not establish why the queue filled.
The 143.7-second recovery window had 12,930 requests and zero failures. This is a single
run on the isolated fixture at the stated request rate and active customer sample.

### Redis queue regression and OPA enforcement, 2026-11-17

The 28 original errors originated in the Customer Edge per-party limiter's blocking
Redis increment. The shared Redis pool refused acquisition after 24 requests queued.
No Valkey latency trace was captured for that original spike. In the isolated stack,
a controlled 750 ms Valkey command pause reproduced the same exception on all three
routes. Each comparison used the same 22-second k6 workload and enforced OPA.

| Customer Edge | HTTP requests | HTTP 500 | Dropped iterations |
| --- | ---: | ---: | ---: |
| Shared Redis pool | 1,917 | 42 | 64 |
| Dedicated rate-limit pool and atomic increment | 1,983 | 0 | 0 |

The rate limiter now uses a named Redis pool capped at 32 connections and 64 waiting
requests, with a one-second command timeout. A single Lua script increments the counter
and sets its expiry. It also repairs a legacy counter that lacks an expiry. The OPA
instances loaded the repository's GitOps bundles for Customer Edge, Party, Account,
Balance and Domestic Payment. Stopping the Edge policy service returned HTTP 503
`POLICY_DECISION_POINT_UNAVAILABLE` on each route. All three authenticated reads returned
HTTP 200 after OPA recovered.

The fixed build was measured with enforced OPA across the five services, using the same
three-million-row datasets, 64 active synthetic customers, and 30 GET requests per second
per route. The reporting worker exported and replayed six million synthetic lines.
Each API cell shows p95 / p99 in milliseconds and HTTP failures / requests.

| Phase | Seconds | Account | Balance | Payment |
| --- | ---: | ---: | ---: | ---: |
| Baseline | 45.0 | 72.9 / 343.3 · 0/1351 | 143.1 / 519.2 · 0/1351 | 105.4 / 440.1 · 0/1350 |
| Export | 18.5 | 132.5 / 436.4 · 0/554 | 214.4 / 479.3 · 0/554 | 208.3 / 514.5 · 0/554 |
| Reporting outage | 45.0 | 110.6 / 577.5 · 0/1350 | 214.7 / 763.0 · 0/1350 | 162.5 / 775.7 · 6/1350 |
| Complete replay | 145.9 | 28.9 / 85.2 · 0/4376 | 57.0 / 169.5 · 0/4376 | 43.0 / 132.7 · 0/4376 |

The run recorded 22,892 HTTP requests and zero dropped iterations. Customer Edge logged
zero Redis pool exhaustion exceptions. Six payment reads returned HTTP 404 during the
reporting outage window. Domestic Payment logged six OPA deadline errors at the same time.
The Customer Edge payment status route maps the upstream non-200 result to 404.
The 145.9-second recovery window completed 13,128 error-free reads. All three recovered
trial balances matched the index digests and balanced independently.

### OPA response timing in the enforced run

The OPA HTTP logs record a short cluster of slow Domestic Payment responses around the
six client deadlines. In the continuous log window from 09:30:47 to 09:35:07 UTC, the
policy endpoint reported these server-side response durations in milliseconds.

| Policy instance | Responses | p50 | p95 | p99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| Domestic Payment | 7,791 | 0.762 | 3.036 | 8.006 | 544.908 |
| Account | 31,158 | 0.677 | 3.010 | 7.505 | 75.952 |
| Balance | 7,791 | 0.729 | 2.871 | 7.224 | 139.474 |
| Customer Edge | 23,373 | 0.776 | 3.625 | 10.961 | 179.122 |

Two Domestic Payment responses took 542.586 ms and 544.908 ms against the caller's
500 ms exchange deadline. Several nearby responses took 152–449 ms. OPA's server-side
duration covers request handling and response generation. The caller's deadline covers
the entire HTTP exchange, including receipt of the body. The logs have no common request
identifier across this boundary, so the six client failures cannot be paired individually
with the server responses. The underlying source of the brief slowdown remains unresolved.

The follow-up Edge build was checked by pausing Domestic Payment's OPA instance. The
same domestic payment status request returned HTTP 200, 503 and 200 across the pause
and recovery. Account and balance reads returned HTTP 200 throughout the check.

### Correlating OPA exchanges in an isolated benchmark

Set the Quarkus log category
`quarkus.log.category."com.openbank.libs.authz.OpaSidecarPolicyDecisionPoint".level` to
`DEBUG` on the test applications. For each call the adapter generates an opaque UUID
and sends it as `X-OpenBank-OPA-Request-ID`. The log records microseconds from adapter
entry to the exchange invocation, response headers, return of the bounded body, and
completion or failure. A missing headers value means the body handler did not observe
response headers before the failure was logged. The interval to headers includes client
scheduling, transport and server handling. The interval from headers to body also
includes the caller resuming. The adapter uses the existing deadline and logs the
failure class without logging policy inputs or outputs.

Configure the isolated OPA instances with `decision_logs.console: true` and
`decision_logs.request_context.http.headers: [X-OpenBank-OPA-Request-ID]`. Include
`system/log` in the test bundle roots and load this mask before sending fixture traffic.

```rego
package system.log

mask := {"/input", "/result", "/nd_builtin_cache"}
```

Verify the mask against a fixture request before starting load. Join client and OPA
decision records by the UUID, then join the decision to the HTTP response by OPA
instance and `req_id`. Compare `timer_rego_query_eval_ns` with `resp_duration` and
the client phase durations. The decision may be absent if the request has not reached
policy evaluation by the deadline. The UUID is a log field rather than a metric label.

### Enforced OPA with exchange timing, 2026-11-17

The same isolated workload used 3,000,000 rows each for parties, accounts, balances
and settled domestic payments. k6 attempted 30 reads per second per route with
64 active synthetic customers. OPA enforcement and exchange diagnostics were active.
The reporting worker exported six million synthetic journal lines without a network,
with a two-CPU and 1 GiB memory budget. The worker was killed before publishing any
output. It replayed all six million lines after a 45-second outage. The three balanced
trial balances, for domestic, FOS and branch, matched their index digests.

Each cell shows p95 / p99 in milliseconds, HTTP errors / requests and dropped k6
iterations. A dropped iteration is demand that the load generator could not send.

| Phase | Seconds | Account | Balance | Payment |
| --- | ---: | ---: | ---: | ---: |
| Baseline | 45.0 | 74.9 / 149.6 · 0/1353 · 0 | 160.7 / 266.4 · 0/1355 · 0 | 111.1 / 213.6 · 0/1353 · 0 |
| Export | 32.4 | 1057.5 / 1348.9 · 0/971 · 2 | 1584.5 / 2296.6 · 0/918 · 55 | 1282.6 / 1731.0 · 0/943 · 30 |
| Reporting outage | 45.0 | 32.7 / 64.7 · 0/1351 · 0 | 67.0 / 129.0 · 0/1353 · 0 | 48.8 / 108.1 · 0/1353 · 0 |
| Complete replay | 149.7 | 75.8 / 329.5 · 0/4490 · 0 | 148.4 / 500.5 · 0/4488 · 0 | 111.2 / 400.9 · 0/4490 · 0 |

There were 24,418 HTTP requests and zero HTTP errors in the measured phases. Export
missed 87 scheduled requests, and the simultaneous latency increase across all three
routes prevents a finding of performance isolation. The synthetic row count checks
were run near the baseline-to-export transition. A follow-up export control omitted
those checks. Both the banking stack and the reporting worker share a Docker VM in
this experiment, and application and OPA timing logs add diagnostic workload.

There were no OPA deadlines in the four correlated windows. All 8,139 Domestic
Payment OPA calls in those windows matched a server decision. Its p99 time to response
headers rose from 19.3 ms at baseline to 157.3 ms at export. OPA p99 HTTP handling
increased from 11.0 to 68.2 ms, and p99 evaluation from 7.3 to 38.9 ms. The longest
Domestic Payment exchange during export was 465.9 ms, and the longest Edge exchange
was 489.1 ms. These times show longer waits both around and inside OPA, but the
original six uncorrelated deadlines did not recur. Their underlying trigger remains
unconfirmed.

In the export control the worker finished in 20.8 seconds with zero HTTP errors and
zero dropped iterations. Account p95/p99 changed from 45.0/82.0 to 106.1/189.3 ms,
balance from 89.0/137.8 to 174.3/315.5 ms, and payment from 64.6/110.1 to
131.9/243.9 ms. Aggregate VM CPU activity rose from a median 22.6% in 13 sampled
baseline intervals to 30.4% in 20 export intervals. Peak sampled I/O wait rose
from 0.3% to 5.0%. These coarse VM counters do not identify the cause of individual
requests or of the original OPA spike. The export slowdown repeated without the
first run's second-scale tail.

A separate fault check paused only the Domestic Payment OPA. The payment read
returned 200 before the pause, 503 during it, and 200 after recovery. Accounts and
balances stayed at 200. The caller logged `HttpTimeoutException` without response
headers or a body, approximately 535 ms after invoking the exchange. This verifies
the diagnostic on a known pre-header fault.

### Separate CPU and block I/O budgets

For the isolated Customer Edge fixture, pin the edge, account, balance, party and
payment services, their OPA instances, database, broker, Valkey, identity provider and
load generator to the banking CPU set. Pin the reporting container to a disjoint CPU
set. Verify the effective CPU set inside each process namespace before running load.
A CPU quota on its own allows scheduling on the same CPUs.

Run the synthetic reporting worker without a network, with a read-only root and a
768 MiB tmpfs for the fixture, scripts, intermediate state and results. The reporting
cgroup gets a two-CPU budget and 1 GiB of RAM, with swap disabled. Copy the scripts into
tmpfs and warm imports before starting load. Capture the reporting cgroup's block I/O
counters before and after the run. Copy out the manifest and results only after the
last measured request. Kill the replay process during the outage and leave the tmpfs
mounted so the new process can reread the frozen source.

This benchmark isolates the reporting data path from the bank's block device inside the
test VM. It still shares VM memory bandwidth and host scheduling. The outage exercises
process recovery. For production, bulk export and replay require a separate worker pool
and a separate durable storage I/O budget for staged input, checkpoint and artifacts.
The immutable handoff remains the boundary with bank services. Verify this deployment
with durable storage and a workload that represents customer-path SLOs before treating
the pilot as capacity evidence for go-live.

#### CPU and storage separation drill, 2026-11-17

The test VM had 12 logical CPUs. The banking stack and k6 used CPUs 0–9, and the
reporting cgroup used 10–11. The dataset, OPA enforcement, request rate, synthetic
identities and reporting replay cap matched the previous drill.

In the first attempt, every one of 23,493 measured requests failed. Of those,
23,473 were HTTP 429 rejections with the Edge concurrency limiter's error shape
and 20 were client timeouts. The limiter remained saturated after traffic stopped.
The run measured the rejection path, so those latency results cannot be used to
assess the account, balance and payment services under load. Restarting the isolated
Edge restored HTTP 200 on all three routes. The retry required ten seconds of healthy
traffic at the target rate before taking the baseline. The rate limits and the OPA
enforcement settings were retained.

Retry results follow. Each cell gives p95 / p99 in milliseconds and HTTP errors /
requests. k6 scheduled 30 reads/s per route for 64 synthetic identities.

| Phase | Seconds | Account | Balance | Payment | Dropped iterations (A / B / P) |
| --- | ---: | ---: | ---: | ---: | ---: |
| Baseline | 45.0 | 578.5 / 1097.8 · 22/1357 | 979.2 / 1820.7 · 21/1355 | 674.8 / 1114.6 · 21/1358 | 0 / 19 / 1 |
| Export | 11.9 | 109.9 / 206.9 · 0/358 | 186.2 / 255.2 · 0/358 | 138.8 / 240.4 · 0/358 | 0 / 0 / 0 |
| Reporting outage | 45.0 | 139.1 / 245.6 · 0/1349 | 247.1 / 451.7 · 0/1348 | 179.0 / 345.7 · 0/1348 | 0 / 0 / 0 |
| Complete replay | 170.6 | 317.4 / 622.0 · 0/5118 | 846.0 / 1432.0 · 0/5066 | 489.4 / 998.4 · 0/5102 | 0 / 51 / 13 |

The retry completed 24,475 measured reads. All 64 HTTP errors were 503s in a baseline
cluster before the reporting worker started. Edge logged 64 OPA exchanges that timed out
before receiving headers. Forty-eight matched an OPA decision by correlation ID. Among
those, OPA HTTP handling took up to 653 ms, while policy evaluation took up to 42 ms.
The cluster was outside the export and replay windows. The shared cause of the stall
remains unknown. There were no OPA exchange failures in the three reporting phases.

The replay processed the full six million lines. All three trial balances reconciled
to the expected gross controls and validated against the digests in the recovered index.
The reporting cgroup's measured block I/O delta was zero writes and 42,409,984 bytes of
reads from the shared VM device. The journal and results stayed on tmpfs. Peak reporting
memory was 709 MiB, with zero swap, OOM events or CPU throttle periods. This confirms
separation of CPU affinity and reporting artifact writes inside the VM. Shared block
reads and physical host resources remain. The 64 missed schedules during recovery and
the latency tail leave the customer-path capacity requirement open.
