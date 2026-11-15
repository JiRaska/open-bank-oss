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
