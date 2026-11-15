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
