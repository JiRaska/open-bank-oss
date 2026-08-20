# Overview

## Co služba dělá

`openbank-audit-service` je **platformový neměnný auditní záznam (audit trail)**. Je to čistý event **consumer**: odebírá doménové události emitované napříč fleetem OpenBank a každou z nich zapisuje do append-only ledgeru, který si auditoři, compliance pracovníci a administrátoři mohou dotazovat per agregát.

Pro každou událost zaznamenává:

- **AuditEntry** — `entryId` (UUID), `eventType`, `aggregateType` (ACCOUNT / PARTY / TRANSACTION / CONSENT / KYC_CASE / UNKNOWN), `aggregateId`, `actorId` / `actorType` (kdo to spustil), celý původní `payload` (JSON, doslovně), `sourceService`, `correlationId`, `occurredAt` (business čas) a `recordedAt` (čas ingestu).
- **Compliance obohacení** (DB sloupce, viz [04](./04-data.md)) — `session_id`, `user_agent`, `ip_address`, `data_sensitivity`, `is_security_event`, `risk_score` a DB-vynucené `retention_until` (occurred_at + 10 let).

Id a typ agregátu se odvozují z příchozího payloadu (`accountId` → ACCOUNT, `partyId` → PARTY, `transactionId` → TRANSACTION, `consentId` → CONSENT, `kycCaseId` → KYC_CASE), takže služba dokáže absorbovat heterogenní tvary událostí bez vazby na konkrétního producenta.

## Co služba **NEDĚLÁ**

- ❌ Nevlastní žádný business agregát — nemá vlastní účty, zůstatky, klienty.
- ❌ Nerozhoduje o autorizaci — to dělá `openbank-libs/authz` + OPA (ADR-0034). Audit pouze *zaznamenává*.
- ❌ Neprovádí SIEM korelaci / alerting — označuje `is_security_event` pro externí SIEM, sám nealertuje.
- ❌ Nevystavuje zápisové API — jediná ingest cesta je Kafka; žádný `POST` endpoint neexistuje.
- ❌ Nemění ani nemaže záznamy — UPDATE/DELETE jsou odmítnuty na úrovni databáze (immutability rules).

## Pozice v doméně

```
  ┌──────────────────┐   account.created     ┌─────────────────────────────┐
  │ account-service  │ ───────────────────►  │                             │
  ├──────────────────┤   transaction.initiated│                             │
  │ transaction-svc  │ ───────────────────►  │     openbank-audit-service  │
  ├──────────────────┤   balance.events       │  (Kafka consumer)           │
  │ balance-service  │ ───────────────────►  │                             │
  ├──────────────────┤   party.events         │   ┌──────────────────────┐  │
  │ party-service    │ ───────────────────►  │   │ audit_entries (append │  │
  ├──────────────────┤   kyc.events           │   │  -only, immutable)    │  │
  │ kyc-service      │ ───────────────────►  │   └──────────────────────┘  │
  ├──────────────────┤   consent.events       │                             │
  │ consent-service  │ ───────────────────►  └──────────────┬──────────────┘
  └──────────────────┘                                       │ GET /api/v1/audit/entries/{id}
                                                              ▼
                                                   admin UI / auditor / compliance
```

## Klíčové use-casy

| Use-case | API / kanál | Směr |
|---|---|---|
| Zaznamenat lifecycle událost účtu | Kafka `openbank.accounts.account.created` | consume |
| Zaznamenat iniciaci transakce | Kafka `openbank.transactions.transaction.initiated` | consume |
| Zaznamenat změnu zůstatku | Kafka `openbank.balance.events` | consume |
| Zaznamenat party / KYC / consent událost | Kafka `openbank.party.events`, `openbank.kyc.events`, `openbank.consent.events` | consume |
| Získat auditní stopu pro agregát | `GET /api/v1/audit/entries/{aggregateId}` | serve |

## Volající

- **Producenti (plní ji):** account-service, transaction-service, balance-service, party-service, kyc-service, consent-service — přes Kafku, bez synchronní vazby.
- **Čtenáři (dotazují ji):** admin-ui (operátoři, auditoři, compliance) přes Keycloak token s rolí `ROLE_AUDITOR`, `ROLE_ADMIN` nebo `ROLE_COMPLIANCE`.

## Závislosti

- **PostgreSQL** (databáze `openbank_audit`, tabulka `audit_entries` ve schématu `public`)
- **Kafka** (consumer group `audit-service`, kanál `audit-events-in`)
- **Keycloak** — OIDC autentizace pro read API
- **openbank-libs** — sdílená runtime plumbing (BuildInfo, ServiceInfoResource, DocsResource, security)

## Business hodnota

- **Jediný neměnný zdroj pravdy** o tom, "co se stalo", napříč platformou — jedno místo, kam se auditoři a regulátoři dívají, místo rekonstrukce historie z logů jednotlivých služeb.
- **Odolnost proti úpravám z konstrukce** — databáze fyzicky odmítá UPDATE/DELETE na auditních řádcích, takže integrita stopy nezávisí na disciplíně aplikační vrstvy.
- **Regulatorní retence** — každý záznam je orazítkován 10letým `retention_until` (EBA ICT / CNB / AMLD), vynucováno při insertu DB triggerem.
- **Oddělený ingest** — producenti fire-and-forget přes Kafku; výpadek auditu nikdy neblokuje business transakci a replay od `earliest` doplní jakoukoli mezeru.
