# CI metrics gateway

The OTLP/HTTP gateway (`apps/ci-metrics-gateway.yaml`) receives `ci.*` gauges from openbank-app CI.
It forwards them by Prometheus remote write to `kube-prometheus-stack-prometheus`. Threat model:
[`docs/threat-models/ci-metrics-ingest.md`](../threat-models/ci-metrics-ingest.md).

## CiMetricsGatewayExportFailing

**Meaning.** The gateway accepted pushes (the OTLP receiver answered 200 to CI), but its
`prometheusremotewrite` exporter failed to deliver them, so the data was dropped. CI looks green and
the dashboards stay empty. `CiMetricsAbsent` would only fire a day later, and it would not say which
hop broke.

**Check, in order.**
1. Gateway logs: `kubectl -n observability logs deploy/ci-metrics-gateway | grep 'Exporting failed'`.
   - `context deadline exceeded` means network: go to 2.
   - An HTTP 4xx from Prometheus means a rejected payload: go to 4.
2. Egress NetworkPolicy `networkpolicy-ci-metrics-gateway.yaml`. The Prometheus rule must carry a
   `namespaceSelector`. With a bare `podSelector`, the AWS VPC CNI policy agent admits only the pod
   IP and never the Service ClusterIP. Every write to the Service name then times out (measured
   2026-11-15).
   - Look in `kubectl -n observability get policyendpoints` for the gateway's entry. It must list the
     ClusterIP of `kube-prometheus-stack-prometheus` on 9090, not only the pod IP.
3. Prove the path from the gateway's own network namespace, without changing anything:
   `kubectl -n observability debug <gateway-pod> --image=curlimages/curl:8.11.1 --profile=restricted -- curl -m 6 http://kube-prometheus-stack-prometheus.observability.svc:9090/-/ready`.
   A timeout here while the pod IP answers is case 2.
4. Prometheus side: the remote-write receiver must be enabled (`enableRemoteWriteReceiver`). Check
   `prometheus_tsdb_out_of_bound_samples_total` for samples rejected as too old.

**Done when** `otelcol_exporter_sent_metric_points{job="ci-metrics-gateway"}` grows after a push, and
a `ci_*` series appears in Prometheus.
