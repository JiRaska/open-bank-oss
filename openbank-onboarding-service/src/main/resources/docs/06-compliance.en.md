# Compliance

`openbank-onboarding-service` is a **read-model projection** of the onboarding funnel. It holds no money and is not a system of record, but it is **KYC-decision-adjacent**: it materialises party + KYC + SCA state and is the operational surface for the onboarding cockpit. Per ADR-0068 it therefore inherits **money-path review rigour** (2 approvals + threat model) even though it is not in `rules.yaml: money_path_services`.

## Regulatory framework

| Regulation | Relation to this service | Implementation / status |
|---|---|---|
| **AMLD / CNB AML-KYC** | Surfaces the onboarding/KYC funnel — "stuck on documents", "failed sanctions/PEP screening", "approved but no passkey" — that compliance must act on | Projects `kyc.events`; the sanctions/PEP **override** control (four-eyes, COMPLIANCE+SUPERVISOR) lives in the owning service per ADR-0068 §5, observed here. 7-year retention (`governance.yaml`) |
| **GDPR** | `legal_name` and `email` are PII held in the projection | Confidential data classification; role-based `PiiMask` is the ADR-0068 §6 target (TBD here); erasure is a four-eyed, step-up-gated action in the owning service |
| **PSD2** | Not applicable at the cockpit layer | Login/SCA are ADR-0021/0066; this service only observes when SCA device enrollment completes (`DEVICE_ENROLLED`) |
| **DORA** | Operational resilience of the read surface | health probes, OTel tracing, Prometheus metrics, rebuildable-from-event-log projection, runbooks (see [05 — Operations](./05-operations.md)) |
| **NIS2** | Network & info security | OIDC auth, security response headers (CSP-adjacent: `X-Content-Type-Options`, `X-Frame-Options: DENY`, HSTS), in-cluster mTLS at the platform layer |
| **PCI DSS** | Not applicable | no cardholder data in the onboarding read-model (ADR-0068 compliance impact) |

## GDPR mapping

### Lawful basis (Art. 6)

- **Legal obligation** (Art. 6(1)(c)) — primary: the projection exists to operate and supervise a regulated KYC/AML onboarding process.
- **Contract** (Art. 6(1)(b)) — secondary: the onboarding journey leads to a customer contract.

### Personal data held

| Field | Source event | Classification |
|---|---|---|
| `legal_name` | `PARTY_CREATED` | PII — direct identifier |
| `email` | `PARTY_CREATED` | PII — direct identifier |
| `party_id`, `kyc_case_id` | party / kyc events | pseudonymized references |

### Data subject rights

| Right | Application |
|---|---|
| Access (Art. 15) | `GET /api/v1/onboarding/records/{partyId}` returns the subject's projected record |
| Rectification (Art. 16) | not corrected here — corrections flow from the owning service's events and re-project |
| Erasure (Art. 17) | the projection row is removed/rebuilt as a consequence of an erasure performed in the owning service; ADR-0068 makes erasure an **irreversible, four-eyed, operator-step-up-gated** action. Constrained by AML 7-year retention where a regulated record exists |
| Restriction (Art. 18) | reflected via `party_status=SUSPENDED` ⇒ `funnel_stage=BLOCKED` |
| Portability (Art. 20) | N/A — not the system of record |
| Object (Art. 21) | N/A — no marketing processing |

### PII masking by role

ADR-0068 §6 prescribes `PiiMask` by role: `COMPLIANCE` sees unmasked `legal_name`/`email`; `OPERATOR`/`VIEWER` see masked values. **Status:** this is a documented target. The current REST layer carries no role gating yet (see [03 — API](./03-api.md)) — closing this is a known follow-up before production.

### Data flows

- **In:** `openbank.party.events`, `openbank.kyc.events`, `openbank.sca.events` (intra-OpenBank, same controller).
- **Out:** the read API to the admin-UI cockpit only (operator/compliance, via Keycloak token through the BFF). No external recipients; no data leaves the platform.

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 9 | Identification | `BuildInfo` (gitCommit, buildTime, version) in `/api/v1/info` |
| Art. 10 | Detection | Micrometer/Prometheus metrics, OTel traces, consumer-lag visibility |
| Art. 11 | Response & recovery | runbooks in [05 — Operations](./05-operations.md); **projection rebuild** from the source event log is the recovery primitive (RPO bounded by Kafka retention) |
| Art. 17 | Incident management & reconstruction | every onboarding **action** (in the owning service) is reconstructable from the hash-chained audit trail; the projection itself is rebuildable from events (ADR-0068 compliance impact) |
| Art. 28 | Third-party risk | no third-party SaaS — all self-hosted |

## AML — sanctions / PEP override control

The single most compliance-sensitive onboarding action — overriding a **failed** `SANCTIONS` or `PEP` screening from `FAILED → PASSED` — is, per ADR-0068 §5, a distinct audited operation (`kyc.check.override`) requiring a `COMPLIANCE` proposer **and** a `SUPERVISOR` confirmer (four-eyes) plus a mandatory free-text justification. **An applicant who failed screening can never be advanced by a single click.** This control is enforced in the **owning** service (kyc-service) via the `openbank-libs` four-eyes primitive; onboarding-service only **observes** the resulting state and (in the target design) renders the approval queue. It does not itself mutate KYC state.

## Audit trail

This service performs no state mutations on party/kyc/sca and therefore emits no domain events. Auditable onboarding **actions** are emitted by the owning services to the audit pipeline (hash-chained, ADR-0029), with `before`/`after`, a mandatory `reason`, and `traceId`; AI-agent actors are attributed with `actorType=AI_AGENT` + `model_id` + `human_approver` (ADR-0031). The cockpit timeline (admin-UI) renders that trail; this service contributes the per-applicant read context.

## Security controls

- ✅ AuthN: Keycloak OIDC, Bearer JWT (realm `openbank`); disabled only in `%dev`/`%test`
- ✅ Security headers: `X-Content-Type-Options=nosniff`, `X-Frame-Options=DENY`, `X-XSS-Protection`, `Referrer-Policy`, HSTS
- ✅ Rate limiting: `openbank.rate-limit.enabled=true`, max 100 concurrent requests
- ✅ Read-only API surface — no mutation endpoints to abuse
- ✅ Resilient ingestion — poison-pill events are acked + logged, never wedge the consumer
- ✅ Secrets: dev placeholders (`CHANGE_ME_LOCAL_DEV_ONLY`) must be replaced via Vault in non-dev (ADR-0017)
- ⚠️ AuthZ: per-role `@Authorize`/OPA **enforce** (ADR-0068 §7) and role-based `PiiMask` (§6) are **not yet wired** — the top pre-production gap
- ⚠️ Operator step-up (Keycloak re-auth for irreversible actions, ADR-0068 §8) is an owning-service/admin-UI concern, not present here
