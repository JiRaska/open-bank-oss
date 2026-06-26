# Compliance

`openbank-sca-service` is a **money-path service** (`rules.yaml: money_path_services`). Changes need 2 approvals + an up-to-date threat model (`docs/threat-models/openbank-sca-service.md`, ADR-0030). It is the regulatory control point for Strong Customer Authentication.

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **PSD2** (Dir. (EU) 2015/2366) + **RTS** (Reg. (EU) 2018/389) | This *is* the SCA engine — two-factor step-up + dynamic linking are mandatory for electronic payments and account access | challenge lifecycle; **dynamic linking** payload binds signature to amount + payee (RTS Art. 5); push/biometric **never auto-approve** (ADR-0021) |
| **AMLD** (Anti-Money Laundering Directive) | Authentication evidence supports transaction monitoring & SAR investigations | 5-year retention of challenges/decisions; `evidenceExported: true` |
| **GDPR** | `party_id`, creditor IBAN/name are PII | pseudonymous party id; secrets (OTP) Redis-only & transient; PII never logged in clear |
| **DORA** (Reg. (EU) 2022/2554) | Operational resilience of a critical authentication function | health probes, outbox fault tolerance (bulkhead/CB/retry/timeout), metrics, runbooks, T0 (no scale-to-zero) |
| **NIS2** | Network & info security | mTLS in-cluster (Istio), security headers (CSP/HSTS/X-Frame-Options), OIDC, OPA authz |

## ADR-0021 — decoupled device approval (the core control)

Critical audit finding **K2**: push/biometric `verify` used to return `true` unconditionally — a full SCA bypass and a direct PSD2 RTS breach. The decision:

1. **Fail closed.** Decoupled methods never auto-approve; with no decision the challenge stays `PENDING`. An unusable factor is strictly safer than a bypassable one.
2. **Explicit out-of-band approval channel.** The enrolled device signs the challenge with a hardware-backed key (Secure Enclave / Android Keystore). The server verifies the signature over the dynamic-linking payload and records the decision; `verify` consults it instead of guessing.

Replay resistance: the signed payload is `id | decision | amount | currency | creditorIban | reference`, so a captured signature cannot be replayed for a different amount, a different creditor, or to flip DENIED→APPROVED. Decisions are **write-once**.

## PSD2 RTS dynamic linking (Art. 5)

```
initiate challenge ──► dynamicLinkingData {amount, currency, creditorIban, creditorName, reference}
                       persisted on the challenge

device approves   ──► signs bytes: id | decision | amount | currency | creditorIban | reference
record decision   ──► verify(signature, devicePublicKey, payload)  ── fail-closed
verify (caller)   ──► COMPLETED only if a valid APPROVED decision exists
```

## GDPR mapping

### Lawful basis (Art. 6)
- **Legal obligation** (Art. 6(1)(c)) — PSD2 mandates SCA; processing the authentication context is required to comply.
- **Contract** (Art. 6(1)(b)) — performing the authenticated banking action the customer requested.

### Data subject rights
| Right | Application |
|---|---|
| Access (Art. 15) | challenge/device records resolvable by `party_id` |
| Rectification (Art. 16) | device credentials are re-enrollable; challenges are immutable evidence |
| Erasure (Art. 17) | **restricted** — authentication evidence is kept 5 years for PSD2/AML; erasure does not override the legal-obligation basis |
| Restriction / Object | N/A (no profiling or marketing here) |
| Portability (Art. 20) | N/A (authentication state is not portable customer-provided data) |

### Data flows out
- → **Kafka** `openbank.sca.challenge.event` (`DEVICE_ENROLLED`): `deviceId`, `partyId`, `credentialId`, `algorithm`, `occurredAt` — intra-OpenBank, consumed by the onboarding read-model (ADR-0068). No private keys, no OTPs, no signatures.
- → **audit/evidence pipeline**: authentication-evidence export (`evidenceExported: true`).
- No data leaves the EU/EEA region.

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 9 | Identification | `BuildInfo` (gitCommit, buildTime, version) via `/api/v1/info` |
| Art. 10 | Detection | Prometheus metrics + alerting on error rate / latency |
| Art. 11 | Response & recovery | runbooks in `05-operations.md`; outbox fault tolerance; RTO 15 min / RPO 5 min |
| Art. 16 | Incident management | events to the audit pipeline as evidence |
| Art. 28 | Third-party risk | no third-party SaaS — Postgres/Redis/Kafka/Keycloak all self-hosted |

## Security controls

- ✅ AuthN: Keycloak OIDC, RS256 JWT
- ✅ AuthZ: `@Authorize` → OPA sidecar (ADR-0034) + per-party ownership enforcement on device endpoints
- ✅ Dynamic linking + signature verification (RTS Art. 5), fail-closed verifier
- ✅ Idempotency: `Idempotency-Key` / `X-Request-ID` + command-derived key
- ✅ Rate limiting: `openbank.rate-limit` (100 concurrent)
- ✅ Security headers: CSP, HSTS, X-Frame-Options DENY, X-Content-Type-Options nosniff, Referrer-Policy, Permissions-Policy
- ✅ Secrets transient: OTPs in Redis only, 300 s TTL, invalidated on success
- ✅ TLS: mTLS in-cluster (Istio), TLS termination at gateway
- ⚠️ OPA enforcement is **advisory by default** (`AUTHZ_ENFORCE=false`); ownership checks in code provide defence-in-depth until enforce is flipped (phased rollout, ADR-0034).
- ⚠️ `openapi.yaml` is out of sync with the implemented contract — regenerate as a follow-up (see `03-api.md`).
