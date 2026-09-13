# Compliance

> **Money-path status:** `openbank-tpp-registry-service` is **NOT** in `rules.yaml: money_path_services`. It belongs to the **Open Banking (PSD2)** capability (`rules.yaml` capability `open-banking-psd2`, alongside psd2-service, consent-service, sca-service, pid-service; `regulatory_ref: PSD2 AISP/PISP`). It does not move money; it gates who is allowed to. Standard 1-approval review applies, but it is a compliance-sensitive control point.

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **PSD2** (Reg. (EU) 2015/2366) | TPP authorisation register — who may exercise AISP/PISP/PIISP | `TppEntry` mirrors EBA/NCA register; `GET /check` authorises each role per request |
| **PSD2 RTS** (Reg. (EU) 2018/389) — secure communication & eIDAS | QWAC/QSeal certificate identity & expiry | `qwac_subject_dn`, `qseal_subject_dn`, `qwac_expires_at`; check rejects expired QWAC |
| **GDPR** | Registry holds legal-entity (TPP) data, not customer PII | `dataClassification: internal`; no party-id/IBAN/natural-person data stored |
| **DORA** (Reg. (EU) 2022/2554) | Operational resilience of a control-plane service | health probes, fault tolerance, OTel, runbooks, BuildInfo |
| **NIS2** | Network & info security | mTLS in-cluster, security headers, OPA authz, audit via outbox (pending) |
| **AMLD** (indirect) | TPP onboarding screening / blacklist as a control | blacklist API (`ACTIVE→BLACKLISTED`); `checkAuthorization` denies any status other than ACTIVE |
| **CNB authorisation register** | National competent authority view | `nca` + `tpp_id` keyed to the CNB/EBA identifier |

## PSD2 — the authorisation gate

This service is the **trust anchor** of the OpenBank PSD2 stack. Flow:

```
TPP → psd2-service (Open Banking façade)
    → tpp-registry-service  GET /check?tppId=…&role=AISP|PISP|PIISP
        → ACTIVE? + holds role? + QWAC not expired? → authorized
    → consent-service (validate customer consent)
    → sca-service (strong customer authentication, ADR-0021)
    → core banking (account/balance/payment)
```

`GET /check` is the single chokepoint: a `403` here stops the TPP before any consent or banking operation is attempted. Blacklisting is the operational kill-switch for a compromised or de-licensed provider.

## eIDAS / certificate handling

- The registry stores certificate **identity** (QWAC and QSeal Subject DN) and **expiry dates**, not the certificate bytes or private keys.
- Authorization rejects a TPP whose `qwac_expires_at` is before today.
- Live TLS-layer certificate-chain validation and QWAC pinning are an **edge/gateway** concern, not performed here (documented as out-of-scope in [01-overview](./01-overview.md)).

## GDPR mapping

The registry concerns **legal entities (TPPs)**, so GDPR exposure is minimal.

### Lawful basis (Art. 6)
- **Legal obligation** (Art. 6(1)(c)) — maintaining a register of authorised TPPs supports PSD2 compliance and CNB/EBA obligations.

### Data subject rights
Generally **not applicable** to corporate registry data. The only field that could inadvertently carry personal data is `blacklist_reason` — operators must keep incident references free of natural-person PII.

### Data flows out
- → **psd2-service** (synchronous API): authorization decision (`tppId`, `authorized`, `roles`, `reason`). No customer PII.
- → **Kafka** `openbank.tpp.registry.event` (intra-OpenBank, e.g. audit): registration/blacklist events **once wired** — currently the outbox transport exists but no events are emitted.

No data leaves the EU/EEA region.

### Retention (Art. 5(1)(e))
`governance.yaml: retentionPolicy: 5 years`. Registration and blacklist records retained 5 years; de-authorisation is a status transition (`BLACKLISTED`), not a hard delete, preserving the authorisation audit trail. `REVOKED` and `SUSPENDED` exist in the enum but nothing writes them today (#6489), so a withdrawn authorisation is currently recorded as a blacklisting.

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 5 | ICT risk management | control-plane service in the central operations register |
| Art. 6 | Risk-management framework | dependency = openbank-libs (centralized) |
| Art. 9 | Identification | `BuildInfo` (gitCommit, buildTime, version) in `/api/v1/info` |
| Art. 10 | Detection | OTel metrics + alerting on `/check` error rate / latency |
| Art. 11 | Response & recovery | runbooks in [05-operations](./05-operations.md); RTO 15 min, RPO 5 min |
| Art. 16 | Incident management | blacklist kill-switch; events to audit (pending wiring) |
| Art. 28 | Third-party risk | the EBA register sync is the planned third-party data feed (currently stub, fault-tolerant wrapper in place) |

## Authorization & security controls

- ✅ **AuthN:** Keycloak OIDC (RS256 JWT). Disabled only in `%dev`/`%test`.
- ✅ **AuthZ:** OPA sidecar (ADR-0034) via `@Authorize` on blacklist; **advisory** mode by default (`authz.enforce=false`) — flip to enforce with `AUTHZ_ENFORCE=true`.
- ✅ **Idempotency:** `Idempotency-Key` on all mutations (Redis-backed).
- ✅ **Rate limiting:** 50 max concurrent requests (`openbank.rate-limit`).
- ✅ **Security headers:** CSP `default-src 'self'`, HSTS, X-Frame-Options DENY, X-Content-Type-Options nosniff, Referrer-Policy, Permissions-Policy.
- ✅ **TLS:** mTLS in-cluster (Istio), TLS termination at gateway.
- ✅ **Secrets:** dev placeholders (`CHANGE_ME_LOCAL_DEV_ONLY`) must be replaced in prod (ADR-0017 Vault).
- ✅ **Resilience:** MicroProfile Fault Tolerance on EBA sync (`@Timeout`/`@Retry`/`@CircuitBreaker`) and the outbox publisher (`@Bulkhead`/`@CircuitBreaker`/`@Retry`/`@Timeout`).
- ⚠️ **Audit events:** domain-event emission to the outbox is **not yet wired** — registration/blacklist actions are not yet on the Kafka audit trail. Tracked as the primary follow-up.
- ⚠️ **OPA coverage:** only the blacklist mutation carries `@Authorize`; register and EBA-sync mutations rely on OIDC alone today.
