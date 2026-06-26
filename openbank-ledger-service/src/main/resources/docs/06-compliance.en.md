# Compliance

`openbank-ledger-service` is a **money-path / T0** service ([rules.yaml](../../../../openbank-libs/governance/rules.yaml) `money_path_services`). Changes require 2 approvals + a maintained threat model ([docs/threat-models/openbank-ledger-service.md](../../../../docs/threat-models/openbank-ledger-service.md), ADR-0030). As the bank's book of record it is in scope for accounting, AML and operational-resilience regulation.

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **CZ Zákon o účetnictví 563/1991 Sb.** | The double-entry general ledger is the statutory accounting record | immutable POSTED entries, reversal-only correction (`reversal_of`), 10-year partition retention, append-only `partition_lifecycle_audit` |
| **Vyhláška 501/2002 Sb. + ČÚS 108–110** | Bank accounting layout, per-customer analytical evidence (analytická evidence), exchange-rate differences | `sub_account_id` sub-ledger ties out GL deposit-control accounts (ADR-0039 Phase B); 5900 Exchange Rate Differences booked by daily revaluation |
| **AMLD 6** | Transaction/accounting record-keeping | 10-year retention (Art. 40); journal references (`transaction_id`, `sub_account_id`) support investigation |
| **DORA** (Reg. (EU) 2022/2554) | Operational resilience of a critical ICT function | health probes, single-writer regulatory-grade outbox (ADR-0050), trial-balance integrity check, SLO, runbooks, audit events |
| **GDPR** | Pseudonymous financial data, no direct identifiers | only UUID references (no name/IBAN/national ID); 10-year statutory retention overrides erasure |
| **PSD2** (Reg. (EU) 2015/2366) | Indirect — accounting backbone behind payment settlement | ledger is internal-only; no direct TPP access |
| **NIS2** | Network & info security | mTLS in-cluster, security response headers, no unauthenticated endpoint (ADR-0018), audit log |
| **ČNB FX fixing** | Statutory daily valuation of foreign positions | mark-to-ČNB daily revaluation (ADR-0046) sourcing the official fixing from fx-service |

## GDPR mapping

### Nature of the data

The ledger stores **accounting and financial data, not direct personal data**. There is no name, IBAN, email, address or national ID in any table. Privacy-relevant fields are pseudonymous references: `transaction_id`, `sub_account_id` (customer-account reference), and staff/system actor UUIDs (`created_by`). Re-identification requires joining through `account-service` / `transaction-service`, which are separate controllers within OpenBank.

### Lawful basis (Art. 6)

- **Legal obligation** (Art. 6(1)(c)) — primary: keeping a statutory double-entry ledger (zákon 563/1991 Sb.) and AML records.
- **Contract** (Art. 6(1)(b)) — secondary: settlement of the customer's transactions is booked here.

### Data subject rights

| Right | Application |
|---|---|
| Access (Art. 15) | a subject's postings reachable via `sub_account_id` (`GET /journals/sub-ledger-balances?subAccountId=…`), resolved through account-service |
| Rectification (Art. 16) | **reversal-only** — a posted entry is never edited; a correcting balanced reversal is booked (audit preserved) |
| Erasure (Art. 17) | **Not applicable** — statutory accounting + AML retention (10 years) overrides |
| Restriction (Art. 18) | upstream (account freeze) — the ledger itself is append-only |
| Portability (Art. 20) | N/A — accounting record, not customer-supplied data |
| Object (Art. 21) | N/A — no marketing/profiling processing here |

### Data flows out

- → **balance-service** / **audit-service** (Kafka `openbank.ledger.journal.posted`): journal-posted events (aggregate id, transaction id, entry number, line count) — same controller, intra-OpenBank.
- → **balance-service** (REST reconciliation reads): trial-balance and sub-ledger aggregates — same controller.
- → **fx-service** (REST, outbound): currency code + date only, to fetch the ČNB rate — no customer data leaves.
- Topic `openbank.ledger.fx.revalued`: revaluation summary (no customer dimension).

No data leaves the EU/EEA region (Czech Republic primary, Ireland DR).

### Retention (Art. 5(1)(e))

| Data | Retention |
|---|---|
| Journal entries / lines | 10 years (zákon 563/1991 Sb. + AMLD 6 Art. 40) via year-partition lifecycle |
| `partition_lifecycle_audit` | 10 years (evidence of detach/drop) |
| `ledger_outbox` (sent) | short-lived operational; trimmed after successful dispatch |

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 5 | ICT risk management | money-path/T0 service in the central register |
| Art. 6 | ICT risk framework | centralized `openbank-libs` dependency |
| Art. 9 | Protection & prevention | per-currency balancing invariant; immutable journal; single-writer outbox |
| Art. 9 | Identification | `BuildInfo` (gitCommit, buildTime, version) in `/api/v1/info` |
| Art. 10 | Detection | trial-balance integrity check, Prometheus metrics + alerting on outbox lag / error rate |
| Art. 11 | Response & recovery | runbooks in [05-operations](./05-operations.md); reversal-only correction; RTO/RPO per T0 |
| Art. 16/17 | Incident management & reporting | JournalPosted events to audit-service; non-zero trial balance is a P1 |
| Art. 28 | Third-party risk | no third-party SaaS — all self-hosted; ČNB rate sourced via internal fx-service |

## Security controls

- ✅ AuthN: Keycloak OIDC, RS256 JWT.
- ✅ AuthZ: Quarkus `@RolesAllowed` from `libs.security.Roles`; **no unauthenticated endpoint** (ADR-0018); reads gated to service/auditor/viewer/operator/admin; posting/reversing/FX-revaluation operator-only. Locked by `LedgerSecurityContractTest`.
- ✅ Accounting integrity: per-currency double-entry balancing enforced in the aggregate (`JournalEntry.validateBalance()`); immutable, reversal-only correction.
- ✅ Idempotency: `ledger_idempotency` table dedups at-least-once upstream retries (money-path safety net).
- ✅ Regulatory-grade delivery: single-writer transactional outbox, isolated/bounded failures → DEAD (ADR-0050).
- ✅ Input validation (Bean Validation + domain invariants), output encoding (Jackson).
- ✅ Rate limiting: `openbank.rate-limit` (100 concurrent).
- ✅ Security headers: CSP, HSTS, X-Frame-Options DENY, X-Content-Type-Options nosniff, Referrer-Policy, Permissions-Policy.
- ✅ TLS: mTLS in-cluster; secrets via Vault (dev placeholders fail-fast in prod).
- ✅ Audit: every posting → audit-service via event; partition lifecycle → immutable audit table.
- ✅ Threat model maintained (ADR-0030, money-path requirement).
