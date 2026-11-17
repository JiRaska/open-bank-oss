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
