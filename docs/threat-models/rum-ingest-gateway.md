# Threat Model — Mobile RUM OTLP Ingest Gateway

**Component:** hardened public OTLP/HTTP ingest gateway for mobile Real-User Monitoring
**ADRs:** ADR-0088 D4b (mandates this threat model *before* build), ADR-0030 (threat-model discipline),
ADR-0069 (`party_id` pseudonymous subject), ADR-0070 (redaction allowlist), ADR-0075 (mobile crash redaction pattern), ADR-0077/0082 (observability substrate)
**Status:** Draft — gates the D4b build (go-live checklist item: "Mobile OTLP ingest gateway threat-modelled; PII redaction + auth + rate-limit verified; consent wired")
**Review cadence:** before GA of mobile RUM, and on any change to the gateway's receiver/auth/redaction config

> This is **not** a money-path service, so the `check-threat-models.py` CI gate does not require it.
> It is authored because the gateway is a **new public, customer-data-adjacent attack surface** — the
> single highest-risk piece of ADR-0088 — and the ADR makes the threat model a hard prerequisite.

---

## 1. Scope

**In scope.** The internet-facing path that accepts OpenTelemetry RUM signals from the KMP customer app
and lands them in the existing observability backend:

- The public **nginx Ingress** at `rum.open-bank.tech` (TLS termination, rate-limit, body-size cap).
- A **dedicated hardened OTel Collector** (`otel/opentelemetry-collector-contrib`) — separate from the
  internal `otel-collector` — terminating OTLP/HTTP from devices: OIDC auth, schema/attribute
  validation, PII redaction, sampling.
- The in-VPC hop from the gateway collector to the existing Tempo/Prometheus backend.

**Out of scope.**

- The mobile SDK itself (`opentelemetry-android` / `opentelemetry-swift`, ADR-0088 D4a/D4c — `openbank-app` repo)
  beyond the contract it must honour (token, consent flag, client-side redaction).
- Backend service instrumentation (internal, VPC-only — covered by ADR-0082).
- Crash reporting (GlitchTip / Sentry-KMP — ADR-0075). RUM is the *performance* layer; crash is separate.
- Keycloak realm security (covered by the IAM/customer-edge threat models); we consume its JWTs, we do not issue them.

---

## 2. Data Flow Diagram (DFD Level 1)

```
                         ┌────────────────────────── TB-1: public internet ──────────────────────────┐
   Customer device                                                                                    │
   KMP app (otel-android / otel-swift)                                                                 │
   • holds openbank-customers access token (party_id claim, 5-min TTL, PKCE)                           │
   • client-side redaction (ADR-0070 allowlist) + consent flag (off by default)                        │
        │  OTLP/HTTP  POST https://rum.open-bank.tech/v1/traces                                         │
        │  Authorization: Bearer <customer JWT>                                                         │
        ▼                                                                                               │
   ┌─────────────────────────────────────────────────────────────────────────────────────────────────┘
   │  nginx Ingress (rum.open-bank.tech)            ── TB-2: controlled boundary (DMZ) ──
   │  • cert-manager letsencrypt-prod TLS           cert private-key-rotation Never (TLS-pin friendly)
   │  • limit-rps + burst, proxy-body-size cap, security headers, OTLP path allowlist (/v1/traces,/v1/metrics)
   │       │
   │       ▼
   │  RUM gateway collector (contrib, dedicated, public-facing)
   │  • oidc auth extension → verify RS256 against openbank-customers JWKS, require aud=openbank-app, reject anonymous
   │  • memory_limiter (shed load), batch
   │  • filter/transform: drop spans with unknown/over-budget attributes; cap attribute count & value length
   │  • redaction processor: allow-list keys only (party_id, X-Correlation-ID, app.version, os, device.model…);
   │    block amounts/IBAN/PII/URL-params; blocked_key_patterns for token/secret-shaped keys
   │  • probabilistic/tail sampling (battery/data budget)
   └───────┼─────────────────────────────────────────────────────────────────────────────────────────
           │  OTLP/gRPC (in-VPC)                    ── TB-3: in-VPC observability ──
           ▼
   otel-collector (internal) → Tempo (spans) + Prometheus (span-metrics) → Grafana
```

Trust boundaries: **TB-1** device↔internet (fully untrusted client), **TB-2** internet↔gateway (the
hardened boundary; everything before storage happens here), **TB-3** gateway↔in-VPC backend (trusted).

---

## 3. STRIDE Analysis

| ID | Threat | Mitigation | Residual risk |
|----|--------|-----------|---------------|
| **S1** | Anonymous / forged OTLP — attacker posts spans without a valid session | OTLP receiver gated by collector `oidc` auth extension: RS256 verified against the `openbank-customers` JWKS, `aud=openbank-app` and `exp` enforced, anonymous rejected (401). No unauthenticated ingest path exists. | Stolen-but-valid token within its 5-min TTL can ingest as that party — bounded by TTL + rate-limit; spans are non-authoritative telemetry, not a money path. |
| **S2** | Token replay from a different device | Tokens are short-lived (5-min TTL) and the gateway re-verifies signature, `aud` and `exp` on every request; the gateway treats RUM as advisory and never grants access from it. (PKCE binds the token at *issuance* in Keycloak — it is not re-checked at use, so it is not relied on here.) | Low — replay within the 5-min window yields only the ability to write the victim's own pseudonymous telemetry. |
| **T1** | Attribute / span injection — device sends crafted attributes to poison dashboards or inject into downstream | `filter` + `transform` reject unknown attribute keys (allow-list), cap attribute count and value length, drop non-conforming spans before storage. `redaction` runs `allow_all_keys=false`. | Allowed-key values are still device-controlled strings — addressed by O2 (cardinality) and display-layer escaping in Grafana. |
| **T2** | Forged `party_id` to attribute spans to another customer | `party_id` is **not** trusted from span attributes. The `oidc` extension authenticates the session but does not propagate its claim onto the span, so — rather than store a spoofable client value — the allow-list **drops** `party_id` entirely (gateway redaction). A trusted JWT-claim binding to re-introduce it is a follow-up (O6). | None: no `party_id` is stored, so it cannot be forged. Trade-off: per-customer attribution waits for O6; the distributed trace still links via `trace_id`. |
| **R1** | Repudiation — no record of who/what ingested | Gateway access logged at nginx (method, status, rate-limit decisions) + collector telemetry; spans carry the JWT-derived `party_id` only (pseudonymous, ADR-0069), so logs are PII-minimal yet attributable. | Acceptable — RUM is not an audit source of truth (that is the audit-service). |
| **I1** | **PII leakage into Tempo** — amounts, IBANs, tokens, SCA `DynamicLinkingData`, raw bodies, URL params land in span attributes (the dominant risk) | Defence in depth: (a) client-side redaction reusing the ADR-0070 allowlist (same `beforeSend` pattern as ADR-0075 crash); (b) **server-side** `redaction` processor on the gateway enforcing the allowlist independent of client behaviour — drops amounts/IBAN/PII/secret-shaped keys + strips URL query params **before** the in-VPC hop. Storage only ever sees the allowlist. | A novel sensitive value placed in an *allowed* free-text attribute (e.g. `screen.name`) could slip through — mitigated by value-length caps and a periodic attribute audit (O3). |
| **I2** | Eavesdropping in transit | TLS (letsencrypt-prod) on the public hop; in-VPC hop inside the cluster network (and TLS-pinnable client per ADR-0064, key-rotation `Never`). | Standard TLS residual. |
| **D1** | Volumetric DoS against the public endpoint | nginx `limit-rps` + burst (per-IP, mirroring customer-edge's 20 rps/3× burst), `proxy-body-size` cap, OTLP path allow-list; collector `memory_limiter` sheds load; gateway is a **separate** deployment so its saturation cannot starve the internal collector or backend services. | A large botnet can still exhaust the gateway's own capacity — bounded blast radius (RUM degrades; banking unaffected). HPA/again-rate-limit is a follow-up (O1). |
| **D2** | Cardinality bomb — unique attribute values explode **both** Prometheus span-metrics series **and** Tempo trace/block volume (storage + query cost) | Attribute allow-list + value-length cap + sampling; span-metrics dimensions restricted to bounded keys (service, route, os, app.version); enumerated `route`/`screen` sets cap the trace-id fan-out too. | Needs a cardinality budget + alert covering Prometheus *and* Tempo (O2); device-controlled `route`/`screen` values are the main vector. |
| **D3** | Decompression bomb / oversized payload | `proxy-body-size` cap at nginx + collector message-size limits; reject oversized OTLP. | Low. |
| **E1** | Using the public RUM endpoint to reach internal services / SSRF | The gateway collector has **no** exporter or extension that calls arbitrary URLs; the only egress is the fixed in-VPC OTLP endpoint. NetworkPolicy restricts the gateway pod's egress to the internal collector/Tempo only. | Low — no user-controllable egress target. |
| **E2** | Compromised gateway pivots into the cluster | Runs as non-root, read-only rootfs, dropped caps (PSS restricted, fleet pattern); dedicated namespace/SA with least-privilege; NetworkPolicy default-deny except the OTLP egress. | Standard container-escape residual. |

---

## 4. Open Risks (pre-GA gate)

| Risk | Severity | Owner | Target |
|------|----------|-------|--------|
| **O1** — DoS capacity: per-IP rate-limit only; no global quota / autoscaling on the public gateway | Medium | platform | Add HPA + a global ingest budget alert before GA; document the expected RUM RPS envelope. **Fallback if HPA isn't wired at GA:** a hard global ingest cap with collector-level shed/drop, so there is no uncovered DoS window between launch and autoscaling. |
| **O2** — Metrics/trace **cardinality budget** from device-controlled attributes (cost + Prometheus pressure) | Medium | observability | Define allowed span-metrics dimensions + a cardinality alert; cap `route`/`screen` to enumerated sets |
| **O3** — Free-text allowed attributes could carry novel PII not on the block-list | Medium | security | Periodic attribute-content audit job; value-length caps; quarterly allow-list review |
| **O4** — consent enforcement: the **`TELEMETRY_RUM` consent scope now exists** server-side (consent-service) as the demonstrable GDPR Art. 7 record; remaining gap is **hard gateway-level enforcement** (the OTel collector can't call consent-service per request) | **Medium — pre-GA** (was High) | consent + mobile | Scope **done** in consent-service. For GA, enforce via a Keycloak protocol-mapper that mints a `telemetry:rum` token scope only when an active `TELEMETRY_RUM` consent exists, and require it at the gateway; until then the app gates emission on the consent (off by default) and the consent record is the audit trail. |
| **O5** — iOS RUM lags Android (no-op stub until `opentelemetry-swift` vendored, ADR-0088 D4c) — uneven coverage, not a leak | Low | mobile | Track with the Sentry-Cocoa follow-up; documented gap |
| **O6** — no trusted `party_id` on RUM spans (dropped at the gateway; the oidc extension doesn't propagate the claim) ⇒ no per-customer session attribution | Low | observability | Add a JWT-claim→span binding (custom processor or auth-extension claim propagation) so `party_id` is set server-side from the token, then re-allow-list it |

---

## 5. Security Controls Summary

> 🔨 = implemented in the gateway gitops (`apps/rum-gateway.yaml` +
> `networkpolicy-rum-gateway.yaml`), pending **live** verification on first deploy.
> 🔲 = not yet built. None may be claimed ✅ until verified against the running gateway.

| Control | Status |
|---------|--------|
| TLS on the public ingest (cert-manager letsencrypt-prod) | 🔨 built — verify cert issues |
| OIDC auth on the OTLP receiver (verify openbank-customers JWT, aud=openbank-app, reject anonymous) | 🔨 built — verify JWKS reachable + iss matches |
| Per-IP rate-limit + burst at nginx | 🔨 built |
| Payload body-size cap + OTLP path allow-list | 🔨 built |
| Attribute allow-list + length cap + drop unknown attrs (redaction `allow_all_keys=false` + transform truncate) | 🔨 built |
| Server-side PII redaction processor (allowlist + block amounts/IBAN-shaped values; URL params dropped as non-allowed) | 🔨 built |
| `party_id` **dropped** (not trusted from span attrs); trusted JWT-claim binding | 🔲 follow-up (O6) |
| Sampling (battery/data budget) | 🔨 built (probabilistic 25%) |
| Dedicated, isolated gateway deployment (not the internal collector) | 🔨 built |
| NetworkPolicy: egress restricted to DNS / Tempo / Keycloak-JWKS only | 🔨 built — verify JWKS egress path |
| Pod hardening: non-root, read-only rootfs, dropped caps (PSS restricted) | 🔨 built |
| Consent: RUM off by default; consent scope wired (server-side check) | 🔲 follow-up (O4, pre-GA blocker) |
| Cardinality budget + alert | 🔲 follow-up (O2) |
| Attribute-content audit job | 🔲 follow-up (O3) |

> All controls are 🔲 because this threat model **precedes** the build (ADR-0088 D4b: threat-model first).
> Each must flip to ✅ — with verification — before the mobile RUM go-live gate is met.
