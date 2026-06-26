# Threat model — `openbank-lending-service`

> Scope: the lending/credit bounded context (origination, servicing, collateral, IFRS 9 provisioning)
> introduced in [ADR-0028](../adr/0028-lending-bounded-context.md). Money-path service per
> `openbank-libs/governance/rules.yaml: money_path_services`; this document is the ADR-0030 D2
> mandatory threat model. It records the trust boundaries, the controls already in place, and the
> maturity gaps tracked as roadmap — it deliberately does not enumerate exploitable specifics.

## 1. Assets

| Asset | Why it matters |
|---|---|
| Loan book (application, loan, schedule, stage) | Customer financial data (GDPR); the source of AnaCredit / FINREP / IFRS 9 returns. |
| Cash events posted to the ledger | Real money movement (disbursement, repayment split, write-off). Integrity here is the money-path invariant. |
| Credit decisions (maker / checker) | Governed origination (EBA/GL/2020/06); a forged or single-actor approval is a control failure. |
| Collateral register & valuations | Drives LGD / haircut and capital; tampering understates risk. |
| Risk parameters (PD / LGD / bureau data) | Inputs to ECL; manipulation distorts impairment and capital. |

## 2. Trust boundaries

1. **Client → service (REST).** Authenticated callers (officers, risk, compliance, admin) over the
   gateway. All endpoints are role-gated (D5); no `@PermitAll`.
2. **Service → ledger-service (synchronous REST).** The loan book never owns balances; it posts
   double-entry journals to `ledger-service` (`POST /api/v1/journals`, `ROLE_OPERATOR`) authenticated by
   client-credentials, behind a `LedgerCallGuard` resilience boundary. This is the money-path crossing.
3. **Service → event stream (transactional outbox).** Loan state changes flow to the analytics/downstream
   plane through the outbox (ADR-0003) — one local transaction, single extraction path, no second read
   path into the lending DB.
4. **Service → credit bureau / risk-parameter / valuation feeds (ports).** `@Default` no-op today; real
   integrations land later as build-time-gated `@Alternative` adapters — each a future trust boundary.
5. **Service → its own Postgres schema.** Intra-service tables only; no cross-schema reads.

## 3. Controls in place (this slice)

- **Authn/z.** Every endpoint `@RolesAllowed` with the appropriate role; sensitive reads (loan,
  schedule, arrears, impairment) gated to lending/risk/compliance/audit and audit-logged.
- **Server-side acting principal.** The acting principal for every state-changing endpoint
  (apply, decide, disburse, write-off) is the authenticated JWT subject via `actor()` — **never** a
  client-supplied field. (Write-off was hardened to this in PR #158.)
- **Four-eyes (maker-checker).** A credit decision must be made by a principal *different* from the
  application maker; disbursement by a principal different from the approver. Enforced server-side in the
  application state machine, not a UI nicety.
- **Money-path integrity.** Postings are double-entry via the pure, unit-tested `LendingJournalFactory`
  (balanced legs asserted per `PostingKind`); the loan book mutates no balances itself.
- **Idempotent posting.** A deterministic `idempotencyKey` / `transactionId` (`UUID.nameUUIDFromBytes`)
  makes at-least-once delivery safe — a replay posts the same journal id and the ledger dedupes.
- **State-transition guards.** Write-off refuses a non-ACTIVE or fully-repaid loan; disbursement requires
  an APPROVED application. Illegal transitions return 409, not a silent mutation.
- **Offline-buildable defaults.** No-op `@Default` ports mean the service boots with zero external
  dependency; real integrations are explicit build-time opt-ins.
- **Secrets.** No hardcoded credentials; config values are env-var placeholders.

## 4. STRIDE summary

| Category | Posture | Notes / roadmap |
|---|---|---|
| **Spoofing** | Mitigated | JWT-subject identity end-to-end; client-credentials to ledger. |
| **Tampering** | Mitigated (in-flight), maturing (at-rest) | Double-entry + idempotency on the money-path; DB-level integrity & immutable audit of impairment movements tracked as roadmap. |
| **Repudiation** | Mitigated | Maker/checker identities and sensitive reads audit-logged; write-off attribution now server-derived. |
| **Information disclosure** | Mitigated | Role-gated GDPR-class reads; analytics only via the outbox stream. |
| **Denial of service** | Partially mitigated | `LedgerCallGuard` (`@Retry`/`@Timeout`/`@CircuitBreaker`) bounds ledger calls; per-tenant rate limiting is a gateway-layer roadmap item. |
| **Elevation of privilege** | Mitigated | No `@PermitAll`; least-privilege roles per endpoint; four-eyes prevents single-actor origination. |

## 5. Maturity / roadmap (tracked, not yet built)

- **Phase 2 origination cycle** — the explicit maker→checker→disburse REST flow as a first-class state
  machine (ADR-0028 D6). Until then four-eyes is enforced at decision/disburse but the full UI cycle is
  scaffolded.
- **Risk-parameter provenance** — when the no-op PD/LGD/bureau ports are replaced by real adapters, each
  becomes a trust boundary needing its own authn, integrity and audit treatment.
- **Impairment-movement immutability** — append-only / tamper-evident storage for IFRS 9 stage and ECL
  movements feeding FINREP F 12, to strengthen the tampering/repudiation posture at rest.
- **Money-path mutation testing** — pitest on the journal/amortization domain math (rules.yaml
  `money_path_depth`, currently `planned`).

## 6. Out of scope

Infrastructure-layer controls (network egress, secret storage, runtime isolation) are covered by the
platform substrate (ADR-0027) and the unified authz layer (ADR-0034), not duplicated here.
