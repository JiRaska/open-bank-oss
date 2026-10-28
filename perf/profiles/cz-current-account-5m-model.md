# Five-million-client model on fixed sandbox resources

Status: **planning arithmetic; capacity conclusion inconclusive**. This accompanies
[the proposed workload profile](cz-current-account-5m-v1.yaml) and
[ADR-0356](../../docs/adr/0356-five-million-client-capacity-qualification.md).
It records no measured maximum, production sizing or compliance approval. The interim
campaign uses existing sandbox resources; it does not increase capacity or change deployments.

## Demand to explain

| Dimension | Proposed target | Derivation |
| --- | ---: | --- |
| Clients / accounts | 5M / 10M | 2 accounts per client |
| Unique originated instructions | 10M/day | 2 per client per day; retries excluded |
| Mean instruction arrival | 115.74/s | 10M / 86,400 |
| Planning peak | 2,314.81/s | 20 times the mean |
| Burst | 5,000/s for 1 hour | 18M instructions, above the ordinary daily forecast |
| Concurrent reads | 50,000/s | 30,000 balance + 15,000 history + 5,000 account/search |
| One-year history | 3.65B instructions | 10M times 365, before related records |

These are proposed business assumptions, not observations. Client count alone cannot
determine throughput. The read-channel assumption and active/dormant account mix remain open.

## CPU and client-count sensitivity

For arithmetic sensitivity only, assume these **incremental CPU costs** per operation; they
are CPU time, not wall latency, and none is measured. Multiplying rate by CPU-seconds gives
raw core-equivalents before baseline load, headroom, I/O waits, redundancy or HA:

| Proposed rate | Assumed incremental CPU / operation | Raw core-equivalents |
| ---: | ---: | ---: |
| 50,000 reads/s | 0.1 ms | 5 |
| 50,000 reads/s | 0.5 ms | 25 |
| 50,000 reads/s | 2 ms | 100 |
| 2,314.8148 instructions/s | 1 ms | 2.315 |
| 2,314.8148 instructions/s | 5 ms | 11.574 |
| 2,314.8148 instructions/s | 20 ms | 46.296 |

These are not sizing recommendations, resource changes or measured coefficients. The
back-calculation is also conditional. If `mu_instr` is a measured sustained peak rate of
unique, valid, complete, financially reconciled instructions/s that meets the profile's
end-to-end SLOs, then `N_peak = mu_instr * 86,400 / (2 * 20) = 2,160 * mu_instr` clients.
If `mu_read` is a measured sustained reads/s under the profile's endpoint mix, authorization
and SLOs, then `N_read = mu_read / (50,000 / 5,000,000) = 100 * mu_read` clients. For an
outbox service `i`, use `2,160 * mu_i / f_i` only when `mu_i` is its measured eligible event
rows/s at the same valid workload and `f_i` is that service's measured event rows per
instruction; an outbox row rate cannot be applied directly as an instruction rate.

The minimum of these client estimates is meaningful only where every input is calibrated
against complete, SLO-valid and reconciled work under the same workload assumptions. It
does not imply a current maximum, and idle CPU readings cannot establish any of these rates.
History placement/size, account-state fractions, interactive workload and approved SLOs
remain unresolved inputs, so an uncalibrated or incomplete minimum stays inconclusive.

## Code bounds that already affect the plan

The shared defaults are a batch of 25, a five-second poll and 16 concurrent sends for v2.
V1 processes one batch per tick; v2 can drain additional batches within 80% of the poll
period. See [dispatch](../../openbank-libs-domain/src/main/kotlin/com/openbank/libs/persistence/outbox/OutboxDispatch.kt)
and [scheduled dispatcher](../../openbank-libs-runtime/src/main/kotlin/com/openbank/libs/persistence/outbox/AbstractOutboxDispatcher.kt).
Verify effective configuration and the service implementation before applying these bounds.

Under those defaults, one v1 dispatcher has a nominal ceiling of **5 claimed rows/s**,
even with instantaneous publishing. If one outbox received one row per instruction,
the proposed mean would exceed that ceiling by 23.15 times. The actual service fanout
is unknown; this conditional comparison is not a measured system limit.

V2 removes that one-batch-per-tick bound. Its concurrency is not a throughput guarantee.
For sensitivity only, assume a full batch of distinct eligible aggregates, equal send
duration `L`, two waves of sends for 25 rows, and combined claim/completion time `D`.
An idealized screening rate is `0.8 * 25 / (2*L + D)` rows/s. This assumes sustained full
batches on a single dispatcher stream and ignores retry, contention and downstream limits.
It is a continuous approximation only when a batch takes much less than the four-second
drain budget; every table example satisfies that condition. The loop checks its deadline
after a batch, so the budget does not cancel an in-flight batch. Longer batches and skipped
scheduled ticks need a different model. This is not a latency model or a conservative
capacity bound; cross-replica behavior must be measured separately.

| Assumed send time L | Assumed DB time D | Idealized screening rate |
| ---: | ---: | ---: |
| 5 ms | 5 ms | 1,333 rows/s |
| 20 ms | 5 ms | 444 rows/s |
| 50 ms | 5 ms | 190 rows/s |
| 100 ms | 5 ms | 98 rows/s |

No coefficient in this table was measured. It shows why changing the repository to v2
does not establish 2,315 instructions/s or 50,000 reads/s. Batch underfill, aggregate-head
serialization, broker acknowledgement tails and database contention can lower the rate.
Measure the real batch duration and completed rows directly instead of fitting this table.

[Retention defaults](../../openbank-libs-domain/src/main/kotlin/com/openbank/libs/persistence/outbox/OutboxRetention.kt)
keep SENT rows for seven days and cap each target's nightly run at 200 batches of 5,000:
**1M deletions per run**. For an outbox receiving `e` rows per instruction, target arrivals
are `10M*e` rows/day; once eligible, those rows need comparable removal capacity. A daily
run cannot keep up when `e > 0.1`, assuming all those rows become SENT and no other cleanup.
Increasing dispatch alone would expose a retention/vacuum bottleneck. Measure each table's
fanout, eligible age, cap hits, dead tuples and WAL before choosing cleanup changes.

## Model each resource and bottleneck separately

| Stage | Required coefficient / constraint | Why extrapolation can fail |
| --- | --- | --- |
| Customer reads | CPU-seconds, DB calls/rows/bytes per endpoint; cache states; pool waits | Warm one-account results miss authorization fanout, eviction and deep history |
| Instruction/workflow | CPU, DB commits and workflow tasks per instruction; queue service rates | Acceptance can stay fast while booking and workflow queues fall behind |
| Ledger/database | WAL bytes, rows/indexes, commit/IO/lock time per reconciled instruction | One database writer, shared control rows and vacuum can dominate added app concurrency |
| Outbox/broker/consumer | Service-specific events and bytes per instruction; batch durations, partition skew, lag | One hot aggregate/partition can saturate while total CPU remains low |
| Retention/history | Actual row/index bytes, hot/archive placement, eligible arrivals/deletions | Small data hides plan changes, bloat and maintenance work at billions of records |
| Recovery | Spare drain capacity and failure-domain limits | Spare steady CPU does not prove catch-up or survival of a database/broker failure |

For each resource `r`, estimate incremental work as the sum of endpoint/instruction rates
times its measured per-unit work, then add the observed baseline. Do not divide one idle
CPU reading by the target rate. Report parameter ranges, shared-tenant interference and the
observed calibration range alongside each estimate. Resources cannot be traded arbitrarily:
free application CPU does not compensate for a saturated database writer or hot partition.

For a queue with arrival `lambda` and service rate `mu`, approximate growth is
`max(0, lambda-mu)*time`. A backlog `Q` has idealized recovery time `Q/(mu-lambda)` only
when `mu > lambda`; otherwise it cannot drain under continuing arrivals. Count delayed
retries and parked/nonterminal rows as outstanding work. These equations omit failure and
ordering effects and cannot prove the profile's recovery deadlines.

Storage remains parametric. At 3.65B instructions/year, an **assumed** aggregate persisted
footprint of 1/4/16 KiB per instruction would require 3.74/14.95/59.80 decimal TB/year.
Define exactly which tables and indexes that coefficient includes. Replicas, backups, WAL
retention, temporary operations and free-space/vacuum headroom are additional unless explicitly
included. These sensitivity points are not measured row sizes, storage budgets or prices.

## Calibration without adding resources

1. Capture current image/configuration, replicas and effective resource/pool limits, database,
   broker and workflow topology, metrics coverage, queue state and ambient workload. Retain
   timestamped raw evidence privately. A point-in-time inventory is not a saturation test.
2. Verify the existing least-privilege identity, fixture ownership and synthetic taint before
   a bounded read campaign. Use current authenticated business routes and retain checks of
   response correctness. A 200 status or a 401 latency alone is insufficient.
3. Freeze the per-endpoint request/count/duration envelope and numeric stop limits against the
   existing sandbox budget. Observe repeated stable plateaus with passive intervals; retain
   intended arrivals, omissions, timeouts, endpoint distributions and queue/resource series.
   Stop on any correctness, ownership, telemetry or shared-environment safety failure.
4. Only after those prerequisites, use a separately scoped bounded write pilot with existing
   synthetic fixtures and independent database reconciliation. Keep policy, authorization,
   durability and SCA controls enabled. Do not repoint the local write benchmark at the sandbox.
5. Derive stage coefficients and ranges from completed, reconciled work. Model normal, peak,
   burst, history, hot-key and recovery demands separately. No full 5M population, billion-row
   history, 72-hour soak or destructive fault campaign is executed by this interim method.

**Acceptance of the model:** reproducible arithmetic, explicit parameter provenance and ranges,
identified first limiting resource, and a list of dimensions outside calibration. An estimate
outside calibration or without SLO support remains **inconclusive**, even if its arithmetic
fits inside nominal CPU limits. Such a model supports prioritization and future sizing; it
does not qualify the five-million-client profile or establish that every bank can adopt it.
