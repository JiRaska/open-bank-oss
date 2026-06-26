# Compliance

> This service is **not** on the money path (`rules.yaml: money_path_services`) — it records the dispute workflow and emits events, it does not move funds. It is nonetheless a **compliance-domain** service (`governance.yaml: dataDomain=compliance`) holding confidential records that feed the regulatory evidence set.

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **PSD2** (Reg. (EU) 2015/2366) | Unauthorised-transaction & refund-claim handling; the consumer's right to dispute and the bank's investigation timeline | dispute lifecycle, `disputeType=UNAUTHORIZED`, `resolutionDeadline` (45-day SLA), timeline trail |
| **Payment scheme rules** (chargeback) | Chargeback / representment / arbitration workflow and the filing window | `DisputeResolution` enum, `chargeback-window-days=120`, `chargebackAmount` |
| **GDPR** (Reg. (EU) 2016/679) | Dispute & evidence free-text may contain PII; party/account are pseudonymous UUIDs | data classified confidential, 7-year retention overrides routine erasure |
| **DORA** (Reg. (EU) 2022/2554) | Operational resilience | health probes, outbox resilience (CB/retry/timeout), metrics, runbooks, BuildInfo |
| **AML/CTF** (AMLD) | A dispute may surface suspicious activity referred to AML | events emitted for downstream AML/audit consumption; not adjudicated here |
| **NIS2** | Network & info security | security headers (CSP/HSTS), mTLS in-cluster, OIDC, audit events |
| **Consumer-protection record-keeping** | Retain dispute history | 7-year retention (`governance.yaml`) |

## GDPR mapping

### Lawful basis (Art. 6)
- **Legal obligation** (Art. 6(1)(c)) — handling a payment dispute and keeping records is required under PSD2 / scheme rules.
- **Contract** (Art. 6(1)(b)) — investigating a dispute is part of performing the payment-services contract.

### Data subject rights

| Right | Application |
|---|---|
| Access (Art. 15) | `GET /api/v1/disputes/account/{accountId}` returns the subject's disputes |
| Rectification (Art. 16) | corrections through the admin UI (`PUT`), appended to the timeline |
| Erasure (Art. 17) | **Restricted** — regulatory record-keeping (7 years) overrides routine erasure for closed disputes |
| Restriction (Art. 18) | status can hold a dispute in a non-resolving state |
| Portability (Art. 20) | dispute + evidence + timeline are exportable JSON (`evidenceExported: true`) |
| Object (Art. 21) | N/A — no marketing processing |

### Data flows out
- → **audit-service** (Kafka, `openbank.disputes.dispute.event`): full dispute event payload — same controller, intra-OpenBank.
- → **notification**: status-change notifications to the customer — same controller.
- → **card-issuance-service** (declared downstream, relation `blocks`): a dispute can trigger a card block.
- Evidence **files** are not transmitted — only a `file_reference` pointer.

No data leaves the EU/EEA region.

### Retention (Art. 5(1)(e))

| Record | Retention |
|---|---|
| Open / in-progress dispute | for the life of the dispute |
| Closed / resolved / withdrawn dispute | **7 years** from resolution (`governance.yaml: retentionPolicy`) |
| Dispute linked to an AML case | aligned to the AML case hold |

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 5/6 | ICT risk management framework | dependency on centralized `openbank-libs`; convention plugin |
| Art. 9 | Identification | `BuildInfo` (gitCommit, buildTime, version) via `/api/v1/info` |
| Art. 10 | Detection | Micrometer/Prometheus metrics, OpenTelemetry traces |
| Art. 11 | Response & recovery | outbox circuit-breaker/retry/timeout; runbooks in `05-operations.md` |
| Art. 16/17 | Incident management & reporting | domain events streamed to audit-service for evidence |
| Art. 28 | Third-party risk | no third-party SaaS — all self-hosted |

## Security controls

- ✅ AuthN: Keycloak OIDC, RS256 JWT (realm `openbank`)
- ✅ AuthZ: Quarkus `@RolesAllowed` (read vs mutation roles) + `@Authorize` (OPA, advisory — ADR-0034)
- ✅ Security headers: CSP, HSTS, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, Referrer/Permissions-Policy
- ✅ Rate limiting: `openbank.rate-limit` (max 200 concurrent)
- ✅ Input constraints: positive-amount CHECK constraints (V4); enum-typed status/type/resolution
- ✅ Audit: every mutation appends an immutable `dispute_timeline` event; domain events for audit-service
- ✅ TLS: mTLS in-cluster, TLS termination at the gateway
- ⚠️ Idempotency: not enforced on mutations yet (Redis client present but unwired) — tracked follow-up
- ⚠️ OPA enforcement: advisory only (`authz.enforce=false`) until the fleet flips to enforce
- ⚠️ Outbox emission: domain-event enqueue into `dispute_outbox` is a last-mile gap (see `02-architecture.md`)
- ⚠️ OpenAPI contract drift: server port and `OpenDisputeRequest` schema lag the code (see `03-api.md`)

These maturity gaps are roadmap items, not exploitable specifics in the production scope.
