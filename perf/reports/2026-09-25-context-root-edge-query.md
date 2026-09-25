# Context root-edge query: isolated PostgreSQL probe

The original neighborhood query applies `LIMIT 201` after an `OR` across both edge directions and a sort by `recorded_at`. This probe tested a high-degree root before changing that query. It is a database probe, not the required authenticated 1×/10× end-to-end capacity gate.

## Setup

- PostgreSQL 16.3-alpine in a disposable local container, limited to 2 CPUs and 2 GiB RAM.
- The repository's `V1__context_graph.sql` schema and indexes, with only synthetic node keys, evidence references and UUIDs.
- 100,000 then 1,000,000 edges in one bank scope and namespace; 10% connected to the queried root. All rows were valid at the query time. `EXPLAIN (ANALYZE, BUFFERS)` read 201 full edge rows.
- Candidate layout adds `(bank_scope, projection_generation, namespace, from_key/to_key, recorded_at DESC, edge_id)` indexes, then removes the redundant original direction indexes. Each direction is sorted and limited independently before a final bounded merge.

| Dataset | Query plan | One observed execution time |
| --- | --- | ---: |
| 100k edges, 10k root degree | Original bitmap scan and top-N sort | 4.229 ms |
| 1M edges, 100k root degree | Original bitmap scan and top-N sort | 240.621 ms |
| 1M edges, 100k root degree | Original query after adding new indexes | 137.358 ms |
| 1M edges, 100k root degree | Split bounded query with both old and new indexes | 0.329 ms |
| 1M edges, 100k root degree | Split bounded query after removing old indexes, repeated | 1.412 ms |

The original plan scanned 100,000 matching edges and read 24,494 shared buffers at 1M edges. The split plan scanned at most 201 rows in each direction and used 63 shared buffers after the old indexes were removed. The numbers are individual local samples with different cache states; they are evidence of the query-plan change, not latency percentiles or a throughput claim.

The PostgreSQL integration test exercises the final native query and Flyway migration with incoming, outgoing, self-loop and expired edges. The remaining release gate is a separate isolated, authenticated 1×/10× run of `perf/k6/context-graph-read-baseline.js` with populated source histories, policy and audit, plus the concurrent payment-regression check in `perf/scenarios.yaml`.
