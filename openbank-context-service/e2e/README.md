# Context-service workload proof

Run the canonical `perf/k6/context-graph-read-baseline.js` profile against an isolated performance
environment with synthetic source-backed evidence and an active case assignment. Set the
`CONTEXT_PERF_*` variables described in that script from the test environment; never commit token
values or result data containing real identifiers. The former local `context-graph-load.js` script
was removed because a 200 with an empty projection could satisfy its thresholds.
The workload emits one `context_http_response_status_<bucket>` counter for each observed bucket: `0`, `200`,
`204`, `400`, `401`, `403`, `404`, `429`, `500`, `503` or `other`. Separate metric names keep status counts
visible in k6 summary exports; `0` identifies requests with no HTTP response. The counters record status only
and never capture response bodies.

Set `CONTEXT_PERF_PROFILE=capacity` for the 100 authorized reads/s, five-minute profile with 50
preallocated virtual users. It requires every sample to return valid, bounded, nonempty source
evidence and fails at p95 300 ms, p99 1 s or any dropped iteration. Run the bank's normal payment control workload
at the same time and reject activation if payment p95 regresses by more than 2%. Archive the k6 JSON,
PostgreSQL saturation, OPA latency, projection lag and payment control result with the release
evidence. The script is a repeatable gate definition; this repository change does not claim a
production-sized benchmark has already run.

For either Lending capacity lens, supply `CONTEXT_PERF_DENIED_TOKEN` for a valid synthetic user
without the graph role. Setup requires a 403 with no guarantee evidence before measuring the
authorized path. Use distinct tokens and keep both outside the repository and benchmark output.
