# Compliance

## Regulatorní rámec

| Regulace | Vztah | Implementace |
|---|---|---|
| **ČNB Vyhláška 163/2014** | vedení zůstatků, povolený/nepovolený debet | `arranged_overdraft_limit` field; rozlišení v `available` formule |
| **AnaCredit** (Reg. EU 2016/867) | reporting povolených/nepovolených overdraftů | per-account `arranged_overdraft_limit`, separátní field; výstup do balance-reporting servisu |
| **PSD2** | balance read přes TPP (AISP) | `consent-service` → `psd2-service` → balance-service GET (role `ROLE_SERVICE_PSD2`) |
| **GDPR** | account_id je nepřímý identifikátor | žádný IBAN/name/email; join na party přes řízený přístup |
| **DORA** | operational resilience | health probes, SLO, runbooks, audit events. `BootstrapVerifier` byl uveden zde a neexistuje (#8426) — secrets drží injektáž přes ESO/OpenBao `secretKeyRef` (ADR-0007) |

## ČNB / AnaCredit overdraft

Klíčové rozlišení (ČNB):
- **Povolený debet** (arranged overdraft) = smluvně sjednaný limit → součást available
- **Nepovolený debet** (unarranged overdraft) = překročení limitu → 422 `insufficient-funds`

V kódu (`Balance.available()`):
```kotlin
fun available(): BigDecimal =
    booked - reserved - pendingDebit + arrangedOverdraftLimit
```

Transakce je akceptována jen pokud `txAmount ≤ balance.available()`.

## GDPR

### Lawful basis
- **Contract** (Art. 6(1)(b)) — primary: vedení zůstatku je nedělitelnou součástí účetní smlouvy
- **Legal obligation** (Art. 6(1)(c)) — secondary: ČNB regulatory reporting

### Data subject rights
| Právo | Aplikace |
|---|---|
| Access (Art. 15) | `GET /api/v1/balances/{accountId}` — subject smí vidět svůj balance |
| Erasure (Art. 17) | **N/A** — AMLD 6 retence 10 let po uzavření účtu |
| Portability (Art. 20) | bulk export přes `/api/v1/balances/{accountId}/export` |
| Rectification (Art. 16) | jen přes audit-trailed manuální adjustment, 2 compliance officeři |

## DORA

| Článek | Implementace |
|---|---|
| Art. 5 | central register operations |
| Art. 9 | identifikace přes `BuildInfo` (gitCommit, buildTime, version) |
| Art. 10 | detection: Prometheus alerty na error rate + lag + reconciliation divergence |
| Art. 11 | response/recovery: runbook v `05-operations.md`, RTO 5min, RPO 1min |
| Art. 17 | reporting: balance events → audit-service |

## Audit trail

Každá změna zůstatku produkuje `balance.updated.v1` event. `audit-service` ho persistuje s tamper-evident hash chain. 10-letá retence.

Recon job (denní 02:00 UTC) ověří `sum(balances.booked) per account == sum(ledger.journal_lines per account)`. Divergence → `balance.reconciliation.diverged.v1` event + PagerDuty alert + automated freeze accountu (volá `account-service POST /freeze`).

## Bezpečnostní kontroly

- ✅ Input validation (Bean Validation, custom `nonNegative` constraint na overdraft)
- ✅ Optimistic locking → safe paralelní authorize (`version` column)
- ✅ Idempotency-Key required na všech mutacích
- ✅ AuthN: Keycloak OIDC, RS256 JWT
- ✅ AuthZ: `@RolesAllowed` + per-account ownership check via `account-service`
- ✅ Rate limiting: `libs.web.RateLimitFilter`
- ✅ TLS: mTLS in-cluster (Istio)
- ⬜ Secrets: **`BootstrapVerifier` neexistuje** — nic nekontroluje dev placeholder při startu. Credentials přicházejí přes `secretKeyRef` z ESO/OpenBao (ADR-0007). Konfigurace, ne kontrola (#8426)
- ✅ Audit: every state change → audit-service
- ⚠️ Encryption-at-rest balance amounts: not implemented (low risk — amounts jsou samy o sobě non-PII)
