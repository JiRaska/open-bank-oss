---
date: 2026-08-04
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ci, testing, observability]
summary: "Add an advisory k6 HTTP performance gate to CI: boot a service via quarkusDev, run a short k6 smoke test with thresholds, and fail the build on breach. Pilot on product-catalog, then scale to money-path services with stricter targets."
---

# ADR-0243 — k6 performance gate as CI advisory check

## Context

The platform has robust correctness testing (unit, integration, contract, E2E)
but almost no continuous performance signal. Latency problems are discovered
late: the ledger posting p95 doubled for two weeks before anyone noticed it on
the Grafana dashboard, and a native-image cold-start regression in
product-catalog shipped because CI only checked functional tests. The existing
observability stack provides latency data after deploy (`analysis-template.yaml`
canary gates check p95 < 500 ms during rollout, and Pyrra tracks 99.5 %
availability), but there is no **pre-merge gate** that exercises a service under
load.

`k6` is already installed in-cluster via the k6-operator
(`openbank-infra/gitops/apps/k6-operator.yaml`) and a synthetic test runs
periodically against the public sandbox
(`openbank-infra/gitops/components/observability/k6-synthetic.yaml`).
<!-- Correction (2026-08-09, ADR-0252 phase 2): "periodically" was not true when this was
     written. That TestRun ran `iterations: 1` once on ArgoCD sync, as its own header said.
     It is now a CronJob — components/observability/cronjob-journey-public-edge.yaml — and
     k6-synthetic.yaml is deleted. Nothing about this ADR's decision changes; the sentence
     is corrected in place rather than removed so the next reader learns it was wrong. -->
That test proves the public edge works but does not gate individual service changes and
cannot reproduce regressions introduced in a PR. The operational-maturity
assessment (#3343) scored the platform at level 1 for performance validation:
tests exist, but they are not in the build path.

This ADR fixes the gate, not the load-test infrastructure. It keeps the gate
**advisory at first** (per ADR-0144) with a declared enforcement target date,
so the pilot can be tuned before it blocks merges.

## Decision

1. **Tooling: k6, run as a standalone CI workflow.** k6 is already the
   platform's chosen load-testing tool (k6-operator, synthetic test). We reuse
   it for CI instead of adding JMeter, Gatling, or a custom harness. The CI
   workflow downloads the official k6 binary for the runner OS and runs
   `k6 run --out experimental-prometheus-rw=<prometheus-pushgw>` when running on
   `openbank-batch` with a Prometheus remote-write endpoint configured; in
   other environments it relies on threshold pass/fail.

2. **Advisory gate, advisory-first per ADR-0144.** The workflow is named
   `perf-gate.yml`, runs on a weekly schedule and `workflow_dispatch`, and is
   **not** a required PR status for the pilot phase. A `target_enforce_date`
   of **2026-11-01** is recorded in `rules.yaml` under `perf_gate`. After the
   date, the workflow is promoted to a required check or folded into
   `services-ci.yml`. Until then, a breach creates a GitHub issue but does not
   block merge.

3. **Pilot service: `openbank-product-catalog`.** It is read-only, stateless,
   has no Kafka dependency, and already has a KEDA ScaledObject target of p95
   ≤ 300 ms for native-image cold-start. This makes it the safest place to
   tune the harness before adding money-path services.

4. **Test structure: one `*.js` per service under
   `<service>/src/test/k6/<name>-smoke.js`.** Keeping the script next to the
   service keeps thresholds and endpoint contracts co-located with the code
   that owns them. A shared helper module lives at
   `openbank-infra/k6/k6-lib.js` for common imports (base URL resolution,
   health wait, metrics tags). Shared helpers only; threshold values are per
   service.

5. **Thresholds for the pilot.**
   - `http_req_failed: ['rate<0.01']` — mirrors the existing canary analysis
     error-rate gate.
   - `http_req_duration: ['p(95)<2000']` — matches the existing public sandbox
     synthetic test and Gatus uptime monitor. This is deliberately loose for
     the CI runner environment; it will be tightened once baseline data exists.
   - `checks: ['rate==1.0']` — every `check()` assertion must pass (status 200,
     content-type, JSON shape).

6. **Money-path targets for later services** (rollout order after pilot:
   ledger, transaction, account, balance): `p(95)<500 ms` and `rate<0.01`,
   matching the money-path canary analysis gate. These services may need their
   own `Testcontainers` stack (Postgres, Kafka, Temporal) booted, so the
   harness must be proven on product-catalog first.

7. **Base URL via environment variable.** Every script reads
   `__ENV.BASE_URL` with a default of `http://localhost:<port>`, where the port
   is derived from the service's `application.yaml`. This lets the same script
   run in CI, locally, and against the sandbox.

8. **Report artifacts.** The workflow uploads k6's end-of-test summary JSON as
   a build artifact (`--summary-export summary.json`) and, when Prometheus
   remote-write is available, pushes metrics tagged with `service=<name>` and
   `branch=${{ github.ref_name }}` so trends can be tracked.

## Alternatives considered

- **Make k6 a Gradle task inside each service.** Rejected: the Gradle k6 plugin
  is immature and couples load testing to the JVM build lifecycle. A standalone
  workflow is simpler and runs on `openbank-batch` without monopolising
  `openbank-build`.
- **Run k6 in-cluster via the operator for every PR.** Rejected: spinning up a
  Kubernetes job per PR couples the gate to cluster availability and adds
  minutes of pod scheduling. CI boots the service locally and runs k6 on the
  runner.
- **Start with a money-path service.** Rejected: product-catalog is safe
  (read-only, no Kafka/Temporal), so the harness can be debugged without
  risking money-path CI instability.
- **Promote to required check immediately.** Rejected: we have no baseline data
  for CI runner load, so thresholds would be guesses and the gate would either
  be toothless or noisy. Advisory phase lets the fleet establish baselines.

## Consequences

**Positive**
- First pre-merge performance signal; latency regressions are caught before
  they reach a rollout.
- Reuses existing k6 investment (operator, Prometheus, synthetic test) and
  existing SLO language (p95, error rate).
- Co-locating scripts in `<service>/src/test/k6/` makes ownership obvious.

**Negative**
- Adds CI time (~2–5 minutes per service once dependencies boot).
- CI runner variability means thresholds must be tuned or use relative
  comparisons; an absolute p95 can flap.
- Pilot phase is advisory, so it does not immediately prevent regressions.

**Neutral**
- The gate is HTTP-only. It does not replace soak tests, DB load tests, or
  Kafka throughput tests; those remain future work.
- The public sandbox synthetic test remains in place and unchanged.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data surface.
- DORA: operational-resilience / performance testing in plain words — testing
  is structured and evidenced; no specific clause cited.
- GDPR: scripts only hit synthetic or public health endpoints; no PII fields
  are probed.
- PSD2: not applicable directly.
- CNB: not a reporting change.

## References

- Issue #3343 (operational maturity tracker), #3348 (this item)
- ADR-0011 (testing pyramid), ADR-0144 (gate graduation — advisory rules with
  enforcement deadline)
- `openbank-infra/gitops/apps/k6-operator.yaml` and
  `openbank-infra/gitops/components/observability/cronjob-journey-public-edge.yaml`
  (was `k6-synthetic.yaml` — see the correction note above)
- `openbank-infra/gitops/components/argo-rollouts/analysis-template.yaml`
- `.github/workflows/api-fuzz.yml` — the analogous non-PR heavy test workflow
