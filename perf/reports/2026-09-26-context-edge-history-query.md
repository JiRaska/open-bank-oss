# Context relationship history: isolated PostgreSQL probe

This probe found and corrected two unbounded database plans in the relationship-history reader. It does not establish authenticated API capacity, latency percentiles, annual-volume headroom or payment-path isolation.

## Fixture and method

PostgreSQL 16.3-alpine ran in a disposable local container capped at 2 CPUs and 2 GiB RAM, without a published port. All identities and evidence were synthetic. The fixture used V1/V20, then V21, with 20 observations per logical relationship. A single root had 5,000 relationships at 100,000 observations, then 50,000 relationships at 1,000,000 observations. The larger fixture also contained 50,000 current edges and their 50,001 node references.

Each observation had a distinct revision/evidence identity, with effective times one second apart. The query cutoff followed all observations and returned 201 complete eligible edge rows. The full reader SQL was extracted from `GraphEdgeHistoryReader` with one transaction-service/booking-prefix/BOOKING_REQUESTED rule. Full-query probes used a non-owner role, transaction-local bank scope and forced history RLS. Current edges retained the query's explicit bank/generation predicates.

`EXPLAIN (ANALYZE, BUFFERS)` measured individual database executions. Cache states differed, and some local verification work overlapped the probes. These samples explain plan behavior; they are not a comparative throughput benchmark.

| Dataset / query stage | One observed execution |
| --- | ---: |
| 100k observations, original ascending historical branch | 422.007 ms |
| 100k observations, descending historical branch only | 1.519 ms |
| 1M observations, descending historical branch only | 3.969 ms |
| 1M observations, full union without per-root bounds, empty current fixture | 858.037 ms |
| 1M observations, per-root lateral reads, 50k current edges, history lookup for every baseline | 3836.193 ms |
| 1M observations + 50k current edges, final lateral/coverage query | 30.659 ms |
| Same final query, repeated | 10.242 ms |

The original 100k historical branch inspected 95,201 observations and touched 477,398 shared buffers before returning 201 rows. The full 1M union plan read the entire history and spilled sorting/hash work to temporary storage. Lateral reads let each selected root use its endpoint/time index and stop after its local result budget. Derived coverage metadata and partial indexes remove covered current rows without 50,000 correlated history lookups. In the final repeated plan, the current-baseline branch returned zero rows from its coverage indexes; the whole query touched 648 shared buffers.

## Behavior and remaining acceptance

Each partition selects complete eligible observations before its local limit. The final merge selects the newest evidence within the global budget, then the application presents that bounded set chronologically. Existing source/prefix/relation allowlists remain inside SQL. Coverage records the first retained observation time; it does not replace source validity or evidence. Legacy rows remain eligible before history begins, and reverse delivery extends coverage backward.

Integration tests cover newest-overflow membership, latest provenance, baseline compatibility, reverse delivery, many revisions beside a second relationship, large input-key sets, bank/generation isolation and immutable history. The separate authenticated 1x/10x gate must still include OIDC, live assignments, OPA policy, disclosure audit/outbox work and concurrent payment regression. Multi-root/high-cardinality distributions, sparse allowlists and historical cutoffs also require capacity measurements before claiming readiness.
