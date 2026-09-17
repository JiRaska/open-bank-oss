# Context-service workload proof

Run `context-graph-load.js` against an isolated performance environment with a synthetic projected
complaint and an active assignment for the test principal. Supply `CONTEXT_BASE_URL`,
`COMPLAINT_REFERENCE`, `CASE_ID` and `ACCESS_TOKEN` from the test environment; never commit token
values or result data containing real identifiers.

The profile drives 100 authorized reads/s for five minutes with 50 preallocated virtual users. It
fails above 1% request errors, p95 300 ms or p99 1 s. Run the bank's normal payment control workload
at the same time and reject activation if payment p95 regresses by more than 2%. Archive the k6 JSON,
PostgreSQL saturation, OPA latency, projection lag and payment control result with the release
evidence. The script is a repeatable gate definition; this repository change does not claim a
production-sized benchmark has already run.
