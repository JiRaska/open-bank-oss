# SEPA workflow observation history: isolated PostgreSQL probe

This probe checks the database access path added for source-owned incident evidence. It does **not** measure the SEPA HTTP write path, Context authorization/audit, incident correlation, or concurrent payment traffic. Those remain activation gates.

## Setup

- Disposable, port-unpublished `postgres:16.3-alpine` container on Docker 29.4.0.
- The branch's exact `V10__payment_workflow_observations.sql` migration, after a minimal `sepa_payments(payment_id UUID PRIMARY KEY)` parent table.
- One synthetic payment UUID. First 100,000 contiguous observation revisions, then 1,000,000; these are deliberately extreme degrees for one payment, not a claimed production distribution.
- `ANALYZE` after each load. One `EXPLAIN (ANALYZE, BUFFERS)` sample at each size, plus one warm-cache repeat at 1,000,000. The query selects the seven fields returned by `SepaWorkflowObservationSource`, applies `payment_id` and upper revision filters, orders by revision descending and limits to 101 rows.
- No network port was published. The container and synthetic data were removed after the probe.

| Rows | Observation | PostgreSQL execution time | Buffers |
| ---: | --- | ---: | --- |
| 100,000 | first sample | 0.058 ms | 9 shared hits |
| 1,000,000 | first sample | 0.328 ms | 3 shared hits, 3 reads, 3 writes |
| 1,000,000 | warm repeat | 0.129 ms | 9 shared hits |

All three plans used `Index Scan Backward using uq_sepa_workflow_observation_revision` and returned 101 rows. The unique `(payment_id, payment_revision)` index therefore serves this bounded history read without a second write-amplifying index. The 0.058/0.328/0.129 ms figures are **individual database samples**, not percentiles or an HTTP SLO. Seed insertion took 1.940 s for the first 100,000 rows and 14.393 s for the next 900,000; those bulk inserts are not representative of payment transaction latency.

The query shape was:

```sql
SELECT event_id, payment_revision, event_type, payment_status,
       content_digest, observed_at, synthetic
FROM sepa_payment_workflow_observations
WHERE payment_id = :paymentId AND payment_revision <= :sourceRevision
ORDER BY payment_revision DESC
LIMIT 101;
```

Before enabling incident case drill-down, run the authenticated 1×/10× Context profile from `perf/k6/context-graph-read-baseline.js` on source-backed synthetic fixtures and compare a concurrent payment control workload. Separately measure the extra observation insert in the SEPA write transaction under realistic concurrency; this database probe cannot establish its p95 impact. Neither source observation nor time overlap alone proves that an incident caused a payment outcome.
