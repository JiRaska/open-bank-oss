# Runbook 0029 — Evidence-of-effect quarterly sweep (ADR-0253)

The quarterly live-data sweep mandated by ADR-0253: three passes of ~20 minutes each,
run against the live database and Prometheus. It answers one question per control —
*did it carry work?* — that no code review, test suite, or health probe can answer,
because every one of the ADR's five founding defects was green on every conventional
signal while doing nothing.

Related: [ADR-0253](../adr/0253-evidence-of-effect-a-control-must-be-able-to-show-it-carried-work.md)
(the mandate), [ADR-0288](../adr/0288-reality-checks-a-scheduled-lane-that-compares-the-repository-s-claims-with-the-running-system.md)
(the scheduled repo-vs-cluster lane consumes this same path — anything this sweep encodes
as SQL/PromQL is a candidate to be mechanised there).

**Cadence:** quarterly, and once after any incident where "it was green the whole time"
featured in the timeline.

**Prerequisites:** read access to the Postgres primaries (or a replica current within
the sweep window), the Prometheus/Grafana datasource, and `kubectl` port-forward to the
observability Prometheus if Grafana is not enough.

**Ground rule:** every finding is an issue (routing below). A sweep that finds nothing
and files nothing is a pass; a sweep that finds something and files nothing is the
exact defect ADR-0253 was written about.

---

## Pass A — Outbox tables (~20 min)

Every service with an outbox entity accumulates `<service>_outbox` rows whose `status`
tells you whether the transactional-outbox promise — *the event leaves with the
transaction* — is actually holding. Example tables (schema verified against migrations;
substitute the service you are checking — `grep -rl "CREATE TABLE .*_outbox"
openbank-*/src/main/resources/db/migration`):

- `ledger_outbox` (openbank-ledger-service, `V2__create_ledger_outbox.sql`)
- `lending_outbox` (openbank-lending-service, `V1__init_lending.sql`)
- `notification_outbox` (openbank-notification-service, `V2__create_notification_outbox.sql`)
- `transaction_outbox` (openbank-transaction-service)

All four carry the same shape: `id BIGSERIAL`, `event_id UUID UNIQUE`, `aggregate_id
UUID`, `event_type VARCHAR(128)`, `payload TEXT`, `status VARCHAR(16)`,
`attempt_count INTEGER DEFAULT 0`, `sent_at TIMESTAMPTZ`, `last_error TEXT`,
`created_at`/`updated_at TIMESTAMPTZ DEFAULT NOW()`.

### A1. The headline GROUP BY

```sql
-- Run per outbox table. Example: ledger_outbox.
SELECT status, count(*), min(created_at) AS oldest, max(updated_at) AS newest
FROM ledger_outbox
GROUP BY status
ORDER BY count(*) DESC;
```

Read it like an accountant, not like an SRE:

- **`PENDING` with an old `oldest`:** rows were written and never dispatched. If
  `attempt_count = 0` across all of them, the dispatcher is not running at all — the
  outbox is a write-only table and every "event published" claim upstream of it is
  fiction. (`openbank.outbox.dispatch-enabled` defaults to `false`; a service that
  never set it `true` in `application.yaml` looks exactly like this.)
- **`FAILED` with growing `attempt_count`:** retrying and losing — read `last_error`,
  it names the defect.
- **`FAILED` with `attempt_count` pinned at the retry cap:** dead-lettered in place.
  These rows are the event-log equivalent of a stranded payment (#4182): the aggregate
  says it happened, the bus never saw it.

### A2. Stuck-row query

```sql
-- Rows written more than an hour ago that never dispatched. Example table.
SELECT id, event_type, aggregate_id, attempt_count, last_error, created_at
FROM ledger_outbox
WHERE status <> 'SENT'
  AND created_at < now() - interval '1 hour'
ORDER BY created_at
LIMIT 50;
```

Anything here is already late; the limit keeps the finding list triageable. Zero rows
is the expected answer in a healthy estate.

### A3. The pg_sequences gap recipe (attempted-and-lost inserts)

"The log says it happened and there is no row" is two different defects — an INSERT
never attempted (an upstream branch returned early) or attempted and lost (rolled
back) — with opposite fixes, and no error line distinguishes them. Every outbox table
carries a `<table>_seq` (Hibernate pooled sequence, `increment_by 50`): the JVM draws
a block only when allocating an id for a `persist`, so the gaps in surviving ids are a
free, retrospective record of how many inserts were attempted. Worked example (#4512):
ids either side of the missing window were 1351 and 1601 with exactly four blocks
burned between them (1401/1451/1501/1551) — one per fan-out — converting "no row"
into "four inserts attempted and lost" with two queries, before any SQL logging was
enabled.

```sql
-- 1. The sequence's current allocation point.
SELECT last_value, is_called FROM ledger_outbox_seq;

-- 2. The surviving id range around the suspicious window (fill in from logs/metrics).
SELECT min(id), max(id), count(*) FROM ledger_outbox
WHERE created_at BETWEEN '<window_start>' AND '<window_end>';

-- 3. Count the 50-wide blocks consumed but absent:
--    (blocks consumed) = (last_value - 1) / 50  when is_called
--    (blocks represented) = (max(id) - 1) / 50 + 1 for contiguous ids
--    burned-but-missing blocks = consumed - represented
```

Read the gaps *before* enabling `log_statement` — the sequence state survives long
after logs and metrics age out; `log_statement` only ever answers about the future.

### A4. The orphan join (outbox row whose aggregate does not exist)

A terminal transition that found no row to write itself onto (#4512: four fan-outs,
four `notification_outbox` rows, zero `notifications` rows, zero error lines). The
fingerprint is an outbox row whose `aggregate_id` has no row in the entity table:

```sql
-- Example pair: notification_outbox -> notifications (substitute your service's
-- outbox and its aggregate table; join on the aggregate's id column).
SELECT o.id, o.event_type, o.aggregate_id, o.status, o.created_at
FROM notification_outbox o
LEFT JOIN notifications n ON n.id = o.aggregate_id
WHERE n.id IS NULL
  AND o.created_at > now() - interval '90 days'
ORDER BY o.created_at DESC
LIMIT 50;
```

Non-zero is a finding, always: it means an event was published for an aggregate the
database cannot show — an evidence gap under DORA Art. 17–19, not a counting quirk.

---

## Pass B — Delivery-outcome counters (~20 min)

The notification channels now carry outcome-tagged counters with alert readers
(`prometheus-rules-push.yaml`, `prometheus-rules-email.yaml`, ADR-0252 phase 0/#4737),
and fraud scoring carries `openbank_fraud_scores_total` (`prometheus-rules-fraud.yaml`,
ADR-0253). This pass reviews the counters the alerts already trust — an alert reader is
a tripwire, not a review.

### B1. Outcome mix per channel (last 30 days)

```promql
# Push: what happened to fan-outs, by outcome?
sum by (outcome, devices_bucket) (increase(openbank_notification_push_fanouts_total[30d]))

# Push sends by outcome — SKIPPED here means the adapter is a successful no-op.
sum by (outcome) (increase(openbank_notification_push_sends_total[30d]))

# E-mail: MOCKED means the mailer is a mock — rows are honest, nothing leaves.
sum by (outcome) (increase(openbank_notification_email_sends_total[30d]))
```

Read against the environment's expected-zero declarations: a sandbox that declares
"no real push provider" SHOULD show SKIPPED; `ACCEPTED > 0` there contradicts the
declaration. An environment that declares live delivery showing `SKIPPED` or `MOCKED`
is the silent-outage shape these counters exist to expose.

### B2. Fraud scoring carried traffic

```promql
# Verdict mix by rail, last 30 days. Phase 1 inert engine: expect ALLOW-dominant,
# but non-zero — the scorer is carrying decisions.
sum by (verdict, rail) (increase(openbank_fraud_scores_total[30d]))

# The silence check itself (the FraudScoringSilent alert expr) — run it by hand to
# confirm the reader is armed, then check whether it has any history of firing.
sum(openbank_fraud_scores_total)
  unless
sum(increase(openbank_fraud_scores_total[6h])) > 0
```

An empty result is expected in a never-scoring sandbox (that is the declared
expected-zero). An empty result in an environment that declares scoring live is
itself the #4221 finding: declare it, do not average it away.

### B3. Alerts that have never fired

From the Alert datasource, list rules whose state has been `inactive` for the entire
retention window and cross-check against the `check-alert-metric-emitted` /
`check-alert-absent-blind` gates: a rule that cannot fire (no series, dead metric
name) is worse than no rule, because it reads as a healthy quiet. Any rule naming a
metric with no series anywhere in the repo is a finding on its own — that is the
18-dead-rules audit of 2026-08-19 repeating.

---

## Pass C — Per-status row counts on key domain tables (~20 min)

Outbox rows tell you events moved; they do not tell you the aggregates themselves
progressed. This pass GROUPs BY the business-status column of the domain tables money
and cases rest on. Example tables (verify column names against the service's
migrations before running; treat these as worked examples, not a frozen list):

- `journal_entries` / `journal_lines` (ledger) — posting state
- the payments/saga tables of the money-path services touched this quarter
- case/dispute status tables where a terminal state should dominate

### C1. The terminal-state dominance check

```sql
-- Pattern: any business table with a status column. Example shape.
SELECT status, count(*), min(created_at) AS oldest, max(updated_at) AS newest
FROM <domain_table>
GROUP BY status
ORDER BY count(*) DESC;
```

- Any status whose `oldest` is older than the business's worst legitimate residence
  time is a sweeper gap (#4182 shape: `COMPLETED` on a non-terminal status with no
  timer and no sweeper).
- A status that has only ever grown quarter over quarter, never drained, is a sink —
  the table version of a counter that never increments.
- Cross-check the two most common non-terminal statuses against the outbox data from
  Pass A: an aggregate stuck `PENDING` whose outbox says the saga completed is #4238
  exactly (the terminal write lived outside the durable workflow).

### C2. Quarterly delta

Keep last quarter's output next to this quarter's. The sweep's real signal is the
DELTA: a status bucket that tripled, a `FAILED` count that stopped moving (fixed?) or
moved only upward (retrying-and-losing). First run establishes the baseline; every
run after compares.

---

## Findings → issues (ADR-0052 routing)

File an issue per finding class, not per row — one issue with ten stuck rows beats ten
issues with one. Route by shape:

| Finding | Issue type | Labels to prefer |
|---|---|---|
| Outbox `PENDING` with `attempt_count = 0` (dispatcher never runs) | Bug | service scope + `bug` |
| Outbox `FAILED` retrying-and-losing, or capped at dead-letter | Bug | service scope + `bug`; money-path service → also threat-model check per ADR-0030 |
| Sequence gaps / orphan joins (attempted-and-lost inserts) | Bug | service scope + `bug` + `data-integrity` |
| Alert reader missing for a control that ships a counter | Enhancement | `observability` + reference ADR-0253 |
| Alert reader exists but cannot fire (dead metric / lazy-registration trap) | Bug | `observability`; reference the 2026-08-19 audit |
| Expected-zero declaration missing or contradicted by data | Enhancement | `governance` + reference ADR-0253 |
| A sweep recipe that should be mechanised into the scheduled lane | Enhancement | reference ADR-0288 |

Every issue links this runbook and the ADR, states which pass produced the finding,
and includes the raw query output as evidence — a finding without its query result is
a claim, not a finding.

## Recording the sweep

Append a short entry to the bottom of this file (date, operator, passes run,
findings filed with issue numbers, one line of estate-level observation). The entry is
the sweep's own countable artifact: a quarter with no entry is a quarter the control
did not run — which, under ADR-0253's own rule, is the thing this runbook exists to
make visible.
