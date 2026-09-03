<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-tpp-registry-service

- **Date:** 2026-06-19
- **Status:** Lightweight STRIDE (ADR-0030 D2). **Security-critical** (PSD2 TPP authorisation gate).
- **Purpose:** Registry of authorised PSD2 Third-Party Providers (TPPs) — AISP/PISP role enforcement.

## 1. Scope & purpose

The TPP registry is the authorisation gate that `openbank-psd2-service` calls to confirm whether an
incoming TPP (identified by QWAC `SSL-CLIENT-S-DN` + `X-TPP-ID`) holds a valid AISP or PISP licence.
It maintains the list of authorised TPPs (sourced from EBA Register + local onboarding), their roles,
and their suspension/revocation status. A **fail-closed** design: if the registry is unreachable, the
PSD2 service returns 503 rather than fail-open.

## 2. Data flow (DFD)

```
[openbank-psd2-service] --authorise(tppId, role)--> [openbank-tpp-registry-service]
[Admin-UI / Operator]   --manage TPPs------------>  [openbank-tpp-registry-service]
```

- **External entities:** PSD2 service (internal, mTLS + OIDC); admin-UI (ROLE_OPERATOR/ADMIN).
- **Trust boundaries:** internal cluster → registry (mTLS + OIDC + OPA); no direct internet exposure.
- **Assets:** TPP authorisation records (licence IDs, roles, revocation status), EBA sync credentials.

## 3. Authn/Authz

- All REST endpoints: `@RolesAllowed` (verified by `TppRegistrySecurityTest`).
- The authorisation lookup endpoint is restricted to `ROLE_SERVICE` (PSD2 service client credential).
- TPP onboarding/revocation endpoints are restricted to `ROLE_OPERATOR`, `ROLE_ADMIN`.
- The registry is **not** directly accessible from the internet — behind the cluster network boundary.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Impersonate a revoked or unlicensed TPP | `X-TPP-ID` validated against registry record per request; EBA register sync keeps revocations current; fail-closed on circuit-open |
| **T**ampering | Alter TPP role (AISP→PISP) in the registry without authorisation | `@RolesAllowed(ROLE_OPERATOR, ROLE_ADMIN)` on write paths; AuditEvent per change; immutable audit trail via outbox |
| **R**epudiation | Deny authorising a TPP for PISP role | AuditEvent per role grant/revocation with operator identity; change log in the entity |
| **I**nfo disclosure | Expose list of authorised TPPs to unauthenticated callers | No Ingress resource (internal only); `@RolesAllowed(ROLE_SERVICE, ROLE_OPERATOR, ROLE_ADMIN)` on all endpoints |
| **D**oS | Flood authorisation lookup to starve PSD2 service | PSD2 service uses circuit breaker (Fault Tolerance, ADR-0035); registry endpoints are read-heavy — no response cache exists today (§5, #4011), so a future one is the natural mitigation; NetworkPolicy restricts callers |
| **E**oP | Escalate from AISP to PISP by registry manipulation | Role check is per-path in PSD2 service (`/payments` ⇒ PISP required); registry only stores the declared role — dual check |

## 5. Residual risks / assumptions

- **EBA Register sync lag:** revocations appear in EBA Register before the sync job runs (currently hourly). A revoked TPP can still call within the sync window — accepted risk, same as any real-time registry. Mitigation: emergency revocation via admin-UI propagates immediately.
- **~~In-memory role cache TTL~~ — this residual does not exist.** This entry described PSD2
  service caching registry responses for a short, configurable TTL
  (`openbank.tpp.cache-ttl-seconds`, default 60) and named the resulting window as a residual risk.
  Neither half is real: the property occurs nowhere in the repository except this document, and
  psd2-service has no registry-response cache at all — `TppRegistryClient` is a plain REST client
  with no `@CacheResult` (#4011), no TTL and no store, so every authorisation check is a live call. The
  only TTL in psd2's configuration is `idempotency-ttl-seconds`, which belongs to the idempotency
  store and is unrelated. Kept rather than deleted because the correction runs the *safe* way — the
  documented risk window is narrower than stated, not wider — and because a future cache would
  reintroduce exactly this residual, so the reasoning is worth preserving. See the 2026-09-03
  change-log entry.

## 6. Change log

- **2026-09-03** — Doc correction, no behavior change: §5 listed an "In-memory role cache TTL"
  residual risk, asserting that PSD2 service caches registry responses and that the window is tuned
  by `openbank.tpp.cache-ttl-seconds` (default 60). **Neither the knob nor the cache exists.**
  `git grep -l -F openbank.tpp.cache-ttl-seconds` returns only this document, and
  `openbank-psd2-service/src/main/kotlin/com/openbank/psd2/infrastructure/client/TppRegistryClient.kt`
  contains no caching of any kind — no `@CacheResult` (#4011), no TTL, no store — so each authorisation
  check is a live call to the registry. The only cache-shaped thing in psd2 is the idempotency
  store (`idempotency-ttl-seconds`), which serves replay protection on consent/payment creation and
  never holds a TPP role.

  Note which direction this correction runs: the real exposure is **smaller** than the document
  claimed, not larger, so this is a stale-risk entry rather than a missing control. The EBA Register
  sync-lag residual immediately above it is unaffected and remains the real revocation-window risk.

- **2026-06-19** — Initial lightweight threat model (ADR-0030 D2).
