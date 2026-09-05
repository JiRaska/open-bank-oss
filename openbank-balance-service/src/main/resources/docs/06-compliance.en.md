# Compliance

## Regulatory framework

| Regulation | Relation | Implementation |
|---|---|---|
| **CNB Decree 163/2014** | balance keeping, arranged vs unarranged overdraft | `arranged_overdraft_limit` field; distinction in the `available` formula |
| **AnaCredit** (Reg. EU 2016/867) | reporting of arranged/unarranged overdrafts | per-account `arranged_overdraft_limit`, separate field; feed into the balance-reporting service |
| **PSD2** | balance read via TPP (AISP) | `consent-service` → `psd2-service` → balance-service GET (role `ROLE_SERVICE_PSD2`) |
| **GDPR** | account_id is an indirect identifier | no IBAN/name/email; join to party via controlled access |
| **DORA** | operational resilience | health probes, SLO, runbooks, audit events. `BootstrapVerifier` was listed here and does not exist (#8426) — secrets are held by ESO/OpenBao `secretKeyRef` injection (ADR-0007) |

## CNB / AnaCredit overdraft

Key distinction (CNB):
- **Arranged overdraft** = contractually agreed limit → part of available
- **Unarranged overdraft** = exceeding the limit → 422 `insufficient-funds`

In code (`Balance.available()`):
```kotlin
fun available(): BigDecimal =
    booked - reserved - pendingDebit + arrangedOverdraftLimit
```

A transaction is accepted only when `txAmount ≤ balance.available()`.

## GDPR

### Lawful basis
- **Contract** (Art. 6(1)(b)) — primary: maintaining a balance is inseparable from the account contract
- **Legal obligation** (Art. 6(1)(c)) — secondary: CNB regulatory reporting

### Data subject rights
| Right | Application |
|---|---|
| Access (Art. 15) | `GET /api/v1/balances/{accountId}` — the subject can see their balance |
| Erasure (Art. 17) | **N/A** — AMLD 6 retention 10 years after account closure |
| Portability (Art. 20) | bulk export via `/api/v1/balances/{accountId}/export` |
| Rectification (Art. 16) | only via audit-trailed manual adjustment, sign-off by 2 compliance officers |

## DORA

| Article | Implementation |
|---|---|
| Art. 5 | central register operations |
| Art. 9 | identification via `BuildInfo` (gitCommit, buildTime, version) |
| Art. 10 | detection: Prometheus alerts on error rate + lag + reconciliation divergence |
| Art. 11 | response/recovery: runbook in `05-operations.md`, RTO 5 min, RPO 1 min |
| Art. 17 | reporting: balance events → audit-service |

## Audit trail

Every balance change produces a `balance.updated.v1` event. `audit-service` persists it with a tamper-evident hash chain. 10-year retention.

A daily recon job (02:00 UTC) verifies `sum(balances.booked) per account == sum(ledger.journal_lines per account)`. Divergence → `balance.reconciliation.diverged.v1` event + PagerDuty alert + automated account freeze (calls `account-service POST /freeze`).

## Security controls

- ✅ Input validation (Bean Validation, custom `nonNegative` constraint on overdraft)
- ✅ Optimistic locking → safe concurrent authorise (`version` column)
- ✅ Idempotency-Key required on all mutations
- ✅ AuthN: Keycloak OIDC, RS256 JWT
- ✅ AuthZ: `@RolesAllowed` + per-account ownership check via `account-service`
- ✅ Rate limiting: `libs.web.RateLimitFilter`
- ✅ TLS: mTLS in-cluster (Istio)
- ⬜ Secrets: **`BootstrapVerifier` does not exist** — nothing checks for a dev placeholder at startup. Credentials arrive through `secretKeyRef` from ESO/OpenBao (ADR-0007). Configuration, not a control (#8426)
- ✅ Audit: every state change → audit-service
- ⚠️ Encryption-at-rest for balance amounts: not implemented (low risk — amounts are themselves non-PII)
