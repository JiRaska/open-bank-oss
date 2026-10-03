# SEPA workflow observation write probe — inconclusive

This is an isolated diagnostic probe for #10868, **not** an activation decision or a
production latency claim. The observation write remains disabled by default.

## Workload and boundary

`SepaWorkflowObservationBenchmarkIT` drives the real SEPA payment creation HTTP route with
synthetic payment data, a Quarkus test identity (`ROLE_PAYMENTS`), a Testcontainers PostgreSQL
database and an in-memory outgoing Kafka channel. It offers 5 requests/s (1×) and 50 requests/s
(10×) for 10 seconds each, following ten warm-up requests. Each run sends 50 and 500 measured
requests, respectively. All measured requests returned HTTP 201. The application uses advisory
OPA in this test; the PDP is unavailable. This **does not** establish production OIDC/OPA,
incident-read, sandbox, or end-to-end performance.

Run each variant in an otherwise idle, isolated host, with the same resources:

```sh
RUN_SEPA_OBSERVATION_BENCHMARK=true \
SEPA_WORKFLOW_OBSERVATIONS_ENABLED=false \
OPENBANK_ENVIRONMENT=test \
SEPA_BENCHMARK_BASE_RPS=5 SEPA_BENCHMARK_SECONDS=10 \
./gradlew --no-daemon :openbank-sepa-payment:test \
  --tests 'com.openbank.sepa.integration.SepaWorkflowObservationBenchmarkIT' --rerun-tasks
```

Repeat with `SEPA_WORKFLOW_OBSERVATIONS_ENABLED=true`, then repeat the disabled control.
The environment variable is required only for the enabled write and fixes the source scope;
the earlier A–B–A probe ran before this additive V11 scope migration.
The test prints `SEPA_OBSERVATION_BENCH` lines to its JUnit XML `system-out`.

## First A–B–A sequence

| Variant | Offered rate | Requests | Failures | p50 | p95 | p99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A1: disabled | 5/s | 50 | 0 | 25 ms | 152 ms | 284 ms |
| A1: disabled | 50/s | 500 | 0 | 18 ms | 77 ms | 155 ms |
| B: enabled | 5/s | 50 | 0 | 28 ms | 121 ms | 391 ms |
| B: enabled | 50/s | 500 | 0 | 32 ms | 234 ms | 316 ms |
| A2: disabled | 5/s | 50 | 0 | 35 ms | 94 ms | 118 ms |
| A2: disabled | 50/s | 500 | 0 | 651 ms | 1,557 ms | 1,950 ms |

The disabled control varied by more than 20× at the 10× offered rate. These numbers cannot
isolate the extra insert's latency. This first run predates the probe's achieved-throughput,
client-queue and scheduled-to-completion measurements; those values cannot be reconstructed from
the table. The updated probe prints them alongside service latency in future runs. It still does
not record resource utilization or an incident-read workload. Next: run repeated randomized
on/off pairs on a stable, dedicated host; capture achieved throughput, CPU, DB waits and pool
pressure; include real OIDC/OPA and authenticated incident-read traffic. Keep the flag off until
the 1×/10× payment-control comparison is stable and reviewed.

## Instrumentation smoke check (2026-09-26)

The updated probe completed an A–B–A sequence at 2 and 20 offered requests/s for five
seconds per rate. Every request returned 201. Each variant ran in the same local host but
restarted the test application; this is a smoke check of the measurements, not a go-live
performance qualification.

| Variant | Offered | Completed | Achieved | Service p95 | Client queue p95 | End-to-end p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A1: disabled | 2/s | 10 | 2.2/s | 44 ms | 5 ms | 49 ms |
| A1: disabled | 20/s | 100 | 20.1/s | 50 ms | 5 ms | 53 ms |
| B: enabled | 2/s | 10 | 2.2/s | 65 ms | 5 ms | 69 ms |
| B: enabled | 20/s | 100 | 20.1/s | 105 ms | 6 ms | 108 ms |
| A2: disabled | 2/s | 10 | 2.2/s | 126 ms | 16 ms | 133 ms |
| A2: disabled | 20/s | 100 | 20.0/s | 38 ms | 5 ms | 40 ms |

The 20/s enabled p95 was higher than both controls, but the 2/s control varied more than
the enabled result. Neither short sample establishes a reliable regression or headroom.
The default-off flag remains unchanged.
