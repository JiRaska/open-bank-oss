# Runtime protection and component-test pilots

The Quarkus 3.40 load-shedding extension is included but explicitly disabled in the
default configuration. The `load-shedding-pilot` profile enables adaptive global
admission with a maximum of 200 concurrent requests and an initial limit of 100.
It disables only the inherited fixed global concurrency filter, so two global
controllers do not compete. The independent Redis per-party quota remains active.

The extension is experimental. Run this profile first against a sandbox workload,
using the same CPU/memory limits, upstream delays, request mix and warmup as the
baseline. Record successful-request throughput, p50/p95/p99 latency, overload 503s,
quota 429s, timeout/error rates, CPU and memory. Count rejected requests separately;
a faster latency distribution caused only by rejecting more traffic is not a win.
Set explicit acceptance budgets for the representative workload before promotion.

The pilot listener explicitly disables HTTP/2. A real concurrent HTTP/2 test on
3.40.1.1 failed with `Prohibited header name 'connection'`: the extension's overload
response unconditionally emits `Connection: close`. The HTTP test keeps the Java
client's HTTP/2 preference and verifies negotiation falls back to HTTP/1.1. This
pilot is unsuitable where the upstream connection requires HTTP/2; wait for an
upstream fix and rerun both protocols before lifting that restriction. The default
profile's protocol configuration is unchanged.

`LoadSheddingPilotIT` drives real concurrent HTTP requests with a small test limit,
requires both accepted and rejected work, then checks admission recovery. It proves
extension wiring, not production capacity. The test endpoint exists only in test
sources. Reverting to the default profile disables adaptive shedding and restores
the inherited fixed cap. No production profile is changed automatically.

`RateLimitFilterComponentTest` starts ArC and real config injection without HTTP,
Redis or Kafka. Its non-default quota and explicitly stubbed allowed/rejected paths
catch wiring defects that hand-constructed unit tests cannot. Anonymous bypass and
subject fallback remain covered. HTTP integration tests remain necessary for route
registration, authentication and interactions with response filters.

References: [component tests](https://quarkus.io/guides/testing-components/),
[load shedding](https://quarkus.io/guides/load-shedding-reference/).
