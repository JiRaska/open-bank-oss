# Runbook 0004 — Low-risk infra bumps: Valkey 8, Loki 3.5, Prometheus latest

Status: Ready (no maintenance window needed — none are stateful-money-path)
Owner: Platform
Related: ADR-0079 (infra lifecycle), ADR-0082 (observability)

## Why grouped

These three were flagged on the admin-UI Infrastructure board as trailing, but unlike
Postgres (0003) and Vault (0002) they carry **no on-disk format break and no money-path
data** — so they are routine chart/image bumps, not migrations. They can ship as normal PRs
and ArgoCD rolls them; the only "outage" is a rolling restart of a stateless or
rebuildable-cache pod.

## 1. Valkey / Redis 7.4.9 → 8.x — **trivial, recommend doing now**

- **What:** 9 `redis:7.4.9-alpine` Deployments in `gitops/components/<svc>/redis.yaml`
  (payments, sca, dispute, interest, aml, consent, sanctions, accounts, fx).
- **Why trivial:** every one is an **ephemeral idempotency/rate-limit cache** —
  `--save "" --appendonly no`, `emptyDir`, **no persistence**. A restart drops the cache and
  it repopulates within seconds; idempotency keys regenerate. There is **nothing to migrate**.
- **Note on naming:** prod should move to the **`valkey/valkey:8.x`** image (Redis changed its
  licence; Valkey is the OSS fork the board already labels "Valkey"). `redis:8` also exists but
  Valkey is the licence-clean choice.
- **Steps:** bump the image in each `redis.yaml` (`valkey/valkey:8.x` or `redis:8-alpine`) →
  one PR → merge → ArgoCD rolls all 9. Verify a `createPayment` dedupe still works (idempotency).
- **Rollback:** revert the tag; cache repopulates. Risk ≈ 0.

## 2. Loki 3.3.2 → 3.5.x — minor, schema-compatible

- **What:** `loki` chart `targetRevision: 6.24.0` (Loki 3.3.2) →
  the chart whose `appVersion` is the latest 3.5.x (verify the Grafana chart index at upgrade time).
- **Why low-risk:** stays inside the **3.x** line. The index schema is **TSDB v13**
  (`loki.yaml` schemaConfig, `from: 2024-04-01`) and is **forward-compatible across 3.x** — no
  schema migration, no re-index. SingleBinary, 10Gi gp3 PVC, structured-metadata stays on.
- **Steps:**
  1. Bump `targetRevision` in `gitops/apps/loki.yaml` to the latest 3.5.x chart.
  2. **Diff the chart values** for renamed keys (Grafana occasionally moves a key between minors)
     — our valuesObject is small (singleBinary, schemaConfig, limits, retention, nodeSelector).
  3. PR → merge → ArgoCD sync → loki-0 rolls.
  4. Verify `/ready` 200, a `{service="account-service"}` query returns logs, and the trace_id
     structured-metadata link still works in Grafana.
- **Rollback:** revert `targetRevision`; the PVC + v13 schema are unchanged so it just rolls back.

## 3. Prometheus 3.12.0 → latest 3.x — minor, TSDB-compatible

- **What:** `kube-prometheus-stack` `targetRevision: 86.1.0` (Prometheus 3.12.0) →
  the latest chart (bundles Prometheus 3.2x.x). This also moves Grafana + Alertmanager +
  node-exporter + kube-state-metrics + the operator together.
- **Why low-risk:** Prometheus **TSDB v4** is stable across all 3.x — no block-format break,
  12h retention, 5Gi gp3. Pinned to the on-demand observability NodePool (ADR-0082), so it
  rolls cleanly.
- **Watch-outs (chart is a fleet of subcharts):**
  - The valuesObject is large (alertmanager Slack receiver, prometheusSpec remote-write +
    exemplars, the full Grafana datasource correlation web). **Diff the chart's values schema**
    between 86.x and the target — kube-prometheus-stack does rename keys across majors.
  - CRD upgrades: kube-prometheus-stack ships CRDs; a several-major jump may need
    `kubectl apply` of the new CRDs (the chart's `crds` are not always upgraded in place by Helm).
  - Grafana inside the chart is pinned to `13.0.2` (our override) — keep the override or move it
    to the bundled version.
- **Steps:**
  1. Bump `targetRevision` (consider stepping 86→88→90 rather than one big jump).
  2. Apply the new CRDs first if the chart requires it.
  3. PR → merge → ArgoCD sync; watch Prometheus/Grafana/Alertmanager roll on the obs NodePool.
  4. Verify: 130+ targets up, span-metrics still flowing, the 36 alert rules loaded, exemplars wired.
- **Rollback:** revert `targetRevision` (CRDs are additive/back-compatible within 3.x).

## Suggested order

1. **Valkey** (zero-risk, do anytime).
2. **Loki 3.5** (single small chart, easy diff).
3. **Prometheus / kube-prometheus-stack** (biggest blast radius — do last, step the minors,
   handle CRDs).

## Follow-ups

- Wire a CI check that fails when a pinned chart `targetRevision` is >N minors behind upstream
  (feeds the same admin-UI lifecycle board), so these don't silently drift again.
