# Threat Model — CI Metrics OTLP Ingest Gateway

**Component:** authenticated public OTLP/HTTP ingest (`ci-metrics.open-bank.tech`) that lets GitHub Actions
in `JiRaska/openbank-app` push CI quality gauges into the sandbox Prometheus.
**ADRs:** ADR-0077 (observability three-pillar strategy), ADR-0324 (shared Envoy Gateway edge), ADR-0234 (ungated machine callers declaration), ADR-0030 (threat-model discipline);
pattern: [`rum-ingest-gateway.md`](rum-ingest-gateway.md).
**Status:** Draft — controls are built in gitops, pending live verification on first deploy.
**Review cadence:** on any change to the receiver, auth, filter/transform or NetworkPolicy config.

> Not a money-path service, so `check-threat-models.py` does not require this file and no ADR/2-approval
> rule applies. It is written because the gateway is a new internet-facing write path into Prometheus.

## 1. Scope

In scope: the shared Envoy Gateway listener, HTTPRoute and BackendTrafficPolicy for the host (ADR-0324), the dedicated `ci-metrics-gateway` collector (`apps/ci-metrics-gateway.yaml`),
its NetworkPolicy, the `ci-metrics-ingest` ExternalSecret (Vault -> Secret) and the remote-write hop to
Prometheus. Out of scope: the exporter (the CI quality exporter script, in the app repo), the customer RUM
gateway (separate collector, separate credential), GitHub's secret store.

## 2. Data flow and trust boundaries

```
GitHub Actions runner  --TLS, POST /v1/metrics, Authorization: Bearer $CI_METRICS_TOKEN-->  TB-1 (internet)
  Envoy Gateway `edge` (per-client 5 rps, body 64Ki, path Exact /v1/metrics, TLS)         TB-2 (DMZ)
  ci-metrics-gateway collector: bearertokenauth -> memory_limiter -> filter/ci -> transform/ci -> batch
  --remote-write (in-cluster, NetworkPolicy)-->  Prometheus (kube-prometheus-stack)         TB-3 (in-cluster)
```

TB-1 sender is semi-trusted (holds a secret, but a leaked/abused token is assumed). TB-2 is the only place
validation happens. Fork PRs do not receive the GitHub secret, so untrusted fork code cannot push. Same-repo branch PR workflows DO receive it: any collaborator with push access can run a workflow that pushes arbitrary (allow-listed) metrics.

## 3. STRIDE

| ID | Threat | Mitigation | Residual |
|----|--------|-----------|----------|
| S1 | Anonymous / forged push | `bearertokenauth` on the OTLP/HTTP receiver; token from a Vault-backed Secret file, never in git; missing/wrong token -> 401 (verified locally). | A leaked token allows writing bounded `ci_*` gauges; rotate in Vault + GitHub secret. |
| T1 | Poisoning dashboards/alerts with other metrics | `filter/ci` drops resources with `service.name != openbank-app-ci`, metrics not matching `^ci\.[a-z0-9_.]{1,64}$`, and non-gauge types. | Token holder can still forge `ci_*` values (e.g. hide a failure). In particular, pushing `ci_run_wallclock_seconds` with `branch_class=main` keeps the CiMetricsAbsent heartbeat quiet even if the real exporter is broken. CI telemetry is advisory, not an audit source. |
| T2 | Label injection / PII (emails, IBANs, tokens, host names) in attributes | `transform/ci` keeps only the 14 allow-listed keys at resource and datapoint level, clears scope attributes and descriptions, truncates values to 80 chars; `error_mode: propagate` fails closed. | Allowed keys (`branch`, `sha`, ...) are still sender-chosen strings. |
| R1 | Repudiation | Envoy access log + collector telemetry; single shared token so no per-workflow attribution. | Accepted. |
| I1 | Eavesdropping / token theft in transit | TLS via a cert-manager Certificate served by the Gateway listener; HSTS from the route and the Gateway-wide policy. | Standard TLS residual. |
| I2 | Data exposure | Metrics carry repo/workflow/branch/sha/test counts only — no secrets or customer data by construction (allow-list). | Public repo metadata. |
| D1 | Volumetric DoS | `BackendTrafficPolicy`: local rate limit PER CLIENT IP (5 rps, `sourceCIDR Distinct`), 64Ki `requestBuffer` body cap (413), 30s request timeout; collector `max_request_body_size` 64 KiB and `memory_limiter`; dedicated pod so RUM is unaffected. | The Gateway Service runs `externalTrafficPolicy: Local`, so Envoy sees the real client address and the limit is genuinely per client. The bucket is per proxy replica (3 replicas), so the ceiling per address is up to 3x. A distributed flood still cannot pass auth but can starve pushes (CiMetricsAbsent). |
| D2 | Cardinality bomb in Prometheus (shared Prometheus: 30d retention, 150GB size cap) | Allow-list + name regex + length caps bound label *names*, metric names and value size. | Token holder can still mint unbounded label *values* (`sha`, `branch`): detected by the ceiling alerts CiMetricsCardinalityHigh (20000 total) / CiMetricCardinalityHigh (5000 per metric), see O1. |
| E1 | SSRF / pivot via the collector | One fixed exporter target; egress NetworkPolicy allows only DNS and the Prometheus pod :9090; ingress only from the envoy-gateway-system proxies :4318 (and the in-namespace Prometheus on :8888 for the collector's own metrics). | Standard. |
| E2 | Container escape | PSS restricted: non-root, read-only rootfs, caps dropped, seccomp RuntimeDefault. gRPC receiver, default traces/logs pipelines and unused receivers are nulled out. | Standard. |

## 4. Open risks

| Risk | Severity | Follow-up |
|------|----------|-----------|
| O1 — cardinality: label values are sender-chosen, so a token holder can grow series until the alerts fire; the alerts only detect, they do not block (shared Prometheus, 30d retention) | Medium | Ceilings in prometheus-rules-ci-quality.yaml (expected ~5.5k series/12h; total 20000, per metric 5000). Rotate the token on alert; consider a collector-side series cap. |
| O4 — Prometheus remote-write on :9090 is unauthenticated and nothing restricts ingress to the Prometheus pod, so ANY in-cluster pod can write `ci_*` (or any) series, bypassing this gateway's auth and filters | Medium | Out of scope here; needs a NetworkPolicy on Prometheus and/or a remote-write auth proxy. |
| O2 — shared static token, manual rotation | Low | Rotate by updating Vault key `ci-metrics-ingest` and the GitHub secret together; consider per-workflow tokens or GitHub OIDC auth later. |
| O3 — CI samples are pushed once per run, so series are only visible at an instant for 5 minutes; rules use `last_over_time` / `absent_over_time` windows | Low | Keep rule windows >= the push cadence. |

## 5. Controls summary

All controls are built (gitops) and unverified on the live cluster until first deploy.

| Control | Status |
|---------|--------|
| TLS via a cert-manager Certificate; Gateway listener `https-ci-metrics`; new host published by external-dns from the HTTPRoute | built — verify cert, record, and listener Programmed |
| Bearer token from Vault ExternalSecret; 401 on missing/wrong token | built; checked locally by running the rendered collector config (image 0.153.0, debug exporter, curl): missing/wrong token -> 401, empty token file -> collector refuses to start |
| Per-client rate limit, 64Ki body cap, `/v1/metrics` Exact path allow-list, http->https redirect on the Gateway's http listener | built |
| Service/name/type filter and attribute allow-list + length cap | built; checked locally with the same method (foreign service, non-`ci.` names, sums, PII attrs dropped; 70k body -> 400). Not tested on the cluster |
| NetworkPolicy ingress/egress lockdown (DNS only to kube-dns pods) | built — verify Prometheus pod label match |
| PSS-restricted pod, only OTLP/HTTP listener | built |
