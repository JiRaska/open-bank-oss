# Runtime experiments

These tools produce evidence for promotion decisions. They do not deploy changes.
Use synthetic data and isolated local dependencies, and keep raw measurements and
runtime configuration outside the repository.

## Leyden AOT startup comparison

`aot_startup.py` compares three variants: the shipping fast-jar layout, the AOT
layout with its cache disabled, and the same AOT layout with its cache enabled.
This separates a layout change from a cache benefit. Each round rotates the order.
The script requires the cache to load successfully; silent fallback is a failure.

Prepare fast-jar and aot-jar artifacts from the **same commit**, with the same
Quarkus build configuration, in separate disposable checkouts/output directories.
Keep the production Docker recipe and its fast-jar packaging unchanged. For the
experimental artifact only, Quarkus 3.40 supports:

```sh
./gradlew :openbank-customer-edge:quarkusBuild \
  -Dquarkus.package.jar.type=aot-jar \
  -Dquarkus.package.jar.aot.enabled=true \
  -Dquarkus.package.jar.aot.phase=build
```

Build-time training covers framework initialization. A promotion experiment needs
representative workload training as described in the
[Quarkus AOT guide](https://quarkus.io/guides/aot/), including the endpoints whose
first-request latency matters. Build, train and run with the exact same JDK 25
distribution/version, OS, architecture and relevant JVM options. An incompatible
cache cannot support a performance claim.

Run the service against dedicated local PostgreSQL/Kafka/Redis dependencies as
applicable, with the same configuration for all three variants. Readiness must be
the actual readiness endpoint; do not replace it with liveness or disable failing
checks. Supply only loopback endpoints. For example, with absolute artifact paths:

```sh
python3 scripts/benchmarks/aot_startup.py \
  --java "$JAVA_HOME/bin/java" \
  --fast-jar /tmp/openbank-aot/fast/quarkus-app \
  --aot-jar /tmp/openbank-aot/trained/quarkus-app \
  --ready-url http://127.0.0.1:8085/q/health/ready \
  --first-request-url http://127.0.0.1:8128/q/openapi \
  --runs 10 --jvm-arg=-Xmx512m \
  --output /tmp/openbank-aot/results.json
```

The first-request URL above exercises schema retrieval, not a banking journey;
choose a representative permitted read endpoint for a product performance claim.
The report records time to readiness, first-response-header latency, sampled RSS,
runtime identity and artifact hashes. Filesystem caches are **not** flushed: these
are fresh-JVM starts, not host cold boots. RSS is an observation after the request,
not peak memory. Reports omit environment, URLs, JVM arguments and application logs.

Before promotion compare the actual shipping fast-jar baseline against cache-on
under equal CPU/memory limits, require the workload's startup/latency budgets, and
verify normal throughput, memory and correctness are not worse. No performance
improvement is asserted merely because cache generation succeeds.

Harness checks:

```sh
python3 scripts/benchmarks/test_aot_startup.py
```

## Tail sampling with complete-input metrics

```sh
python3 scripts/benchmarks/tail-sampling/verify.py
```

This runs a digest-pinned Collector 0.161.0 in an isolated Docker container and
publishes only ephemeral loopback ports. It validates the config, sends synthetic
healthy/error/slow client-server traces, and checks the actual Prometheus output
and sampled JSON export. One server span arrives in a second batch. The default
healthy-trace sampling percentage is zero so the expected result is deterministic:
three requests counted, one error, two requests within one second, three service
graph requests, but only error and slow traces exported. Cleanup targets only the
container created by this invocation.

`collector.yaml` forks **before** tail sampling: `span_metrics` and `service_graph`
receive complete input; a separate branch selects stored traces. Its metric-name
adapter matches the current Tempo-derived RED metric names and seconds buckets.
The file exporter is a local acceptance sink, not a production trace backend.

Promotion requires a reviewed environment change: route participating services
through this collector, prove their effective head sampler retains all input, use
the OTLP exporter to an isolated Tempo backend, and disable that backend's own
metrics generator to prevent sampled/duplicate series. Start with one collector
replica; multiple replicas require trace-ID routing. Do not change production
sampling while services still bypass the collector or existing SLOs consume the
old generator. Compare dashboards and alerts against complete-input metrics first.

The five-second decision wait and trace/cardinality/memory limits are deliberately
small pilot budgets. Tune them from measured trace arrival delay and volume.
Late spans, overflow and collector restarts can lose a trace or an error discovered
after the decision; this configuration does not guarantee retaining every error.
Monitor refused spans, buffer pressure, early eviction and late-span behavior.
Increase the healthy baseline from zero (for example to 10%) only after the
deterministic checks pass and storage/diagnostic budgets are agreed.
