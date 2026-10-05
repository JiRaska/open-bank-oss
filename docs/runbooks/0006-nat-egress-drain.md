# Runbook 0006 — Egress anomaly diagnosis

Linked alerts: `FinOpsNatEgressSpikeAbsolute`, `FinOpsNatEgressSpikeRelative`,
and `FinOpsFleetEgressHigh`. Their counters are traffic proxies, not billed NAT
or cross-AZ bytes. The namespace alerts use
`container_network_transmit_bytes_total`; the fleet alert uses
`node_network_transmit_bytes_total{device=~"ens[0-9]+"}`. Do not infer an AWS
charge or exfiltration from an alert alone.

1. Confirm the alert window and scale. The absolute namespace rule compares a
   24-hour rate with 50 GiB/day; the relative rule compares a five-minute rate
   with a seven-day baseline and also requires over 1 MiB/s. The fleet rule
   requires over 800 GB/day equivalent for an hour. If seven days of Prometheus
   samples are unavailable, the relative comparison cannot establish a baseline.
2. In Prometheus, compare affected namespaces and nodes with the same metric
   and window used by the alert. Exclude `hostNetwork` pod series from namespace
   attribution: cAdvisor can report the entire node's traffic on each such pod.

   ```promql
   topk(10, sum by (namespace) (rate(container_network_transmit_bytes_total[30m])))
   topk(10, sum by (instance) (rate(node_network_transmit_bytes_total{device=~"ens[0-9]+"}[30m])))
   ```

3. Correlate the step change with a deployment, new node, or retrying exporter.
   Uniform growth across nodes points to a fleet agent or rejected sink; one
   namespace or node points to a narrower workload. Check the destination's
   error and retry metrics before naming a cause. Compare any cost claim with
   the billing source, which measures a different boundary from these counters.

Capture the metric window and responsible workload for the platform/FinOps
owner. Investigation here makes no network or deployment changes.
