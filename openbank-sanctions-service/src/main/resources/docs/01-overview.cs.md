# Přehled

## Co služba dělá

`openbank-sanctions-service` je **compliance gate pro prověřování sankcí** v platformě OpenBank. Zajišťuje:

- **Prověřování entit** — fyzických osob, organizací, plavidel a letadel — oproti až 6 mezinárodním a domácím sankcím před zpracováním plateb nebo otevřením účtů.
- **Správu sankcičních listin** — uchovává konfiguraci (zdrojová URL, rozvrh obnovy, příznak enabled) pro každou listinu a spouští periodické obnovy na základě konfigurovatelného cronu.
- **Záznamy každého výsledku prověření** jako `SanctionsCheck` s fuzzy skóre shody (0,0–1,0), nalezenými záznamy v listinách a typem shody (EXACT / FUZZY / PHONETIC / ALIAS).
- **Podporu workflow pro ruční přezkum** — compliance důstojníci mohou posílat `ReviewCommand` a přesunout `POTENTIAL_HIT` do stavu `CLEAR`, `HIT`, `WHITELISTED` nebo `ESCALATED`.
- **Publikování screening eventů** do Kafky přes transakční outbox, aby downstream služby (AML, audit, notification) mohly reagovat bez pollingu.

## Co služba NEDĚLÁ

- Neprovádí KYC verifikaci identity — to zajišťuje `openbank-kyc-service`.
- Nespouští AML monitoring transakcí — to zajišťuje `openbank-aml-service`.
- Přímo neblokuje platby — emituje eventy; platební služby samy hlídají výsledek.
- Neukládá kopie externích dat listin — persistují se pouze metadata shody a skóre.
- Neprovádí autonomní rozhodnutí o zmrazení — zmrazení iniciuje `account-service` až po potvrzení `HIT` lidským přezkumem.

## Pozice v doméně

```
   ┌─────────────────┐  ScreenEntityCommand  ┌──────────────────────┐
   │  sepa-payment   │ ─────────────────────► │  sanctions-service   │
   │  domestic-pay   │                        │  (tato služba)       │
   │  account-svc    │                        └────────┬─────────────┘
   │  fx-service     │                                 │  outbox → Kafka
   └─────────────────┘                                 ▼
                                             openbank.sanctions.screening.event
                                                        │
                                          ┌─────────────┼─────────────┐
                                          ▼             ▼             ▼
                                     aml-service   audit-service  notification
```

Při zahájení převodu volá platební služba `POST /api/v1/sanctions/screen`. Odpověď obsahuje `SanctionsCheckStatus` (`CLEAR` / `HIT` / `POTENTIAL_HIT` / `WHITELISTED` / `ESCALATED`). Výsledek `CLEAR` nebo `WHITELISTED` umožňuje pokračovat v platbě; `HIT` nebo `ESCALATED` ji blokuje.

## Klíčové use-case

| Use case | API | Event |
|---|---|---|
| Prověřit stranu před platbou | `POST /api/v1/sanctions/screen` | `SanctionChecked` |
| Přezkoumat potenciální hit | `POST /api/v1/sanctions/review` | `SanctionReviewed` |
| Zobrazit potvrzené hity | `GET /api/v1/sanctions/hits` | — |
| Zobrazit hity čekající na přezkum | `GET /api/v1/sanctions/pending` | — |
| Povolit / nakonfigurovat listinu | `PUT /api/v1/sanctions/lists/{id}` | — |
| Manuálně obnovit listinu | `POST /api/v1/sanctions/lists/{listType}/refresh` | — |
| Obnovit všechny povolené listiny | `POST /api/v1/sanctions/lists/refresh-all` | — |

## Volající

- **sepa-payment-service**, **domestic-payment-service**, **sepa-instant-service**, **fx-service** — prověření protistrany před provedením převodu (ADR-0032 screening gate)
- **account-service** — prověření vlastníka účtu při otevírání účtu
- **admin-ui** — compliance operátoři přezkumují čekající hity a spravují konfiguraci listin
- **kyc-service** — prověření fyzické osoby při onboardingu

## Závislosti

- **PostgreSQL** (`openbank-postgres`, schema `openbank_sanctions`)
- **Kafka** (`openbank-kafka`, topic `openbank.sanctions.screening.event`)
- **Redis (Valkey)** — cache pro idempotentní deduplikaci
- **Keycloak** — OIDC autentizace
- **openbank-libs** ≥ 0.1.0 — IdempotencyStore, outbox base, BuildInfo, DocsResource

## Business hodnota

- **Regulatorní soulad** — povinné pre-payment prověřování OFAC/EU/UN dle EU Regulation 2580/2001, Council Regulation (EU) 269/2014 a US OFAC pravidel.
- **Jediný enforcement bod** — všechny platební služby delegují na jednu službu; žádná duplicitní logika listin napříč fleetetem.
- **Audit trail** — každé prověření je persistováno a publikováno do `audit-service` s tamper-evident event chainingem.
- **Human-in-the-loop** — fuzzy/fonetické shody spouštějí frontu pro ruční přezkum místo automatického blokování, čímž se snižuje počet falešně pozitivních výsledků.
- **Konfigurovatelné listiny** — compliance tým může zakázat listiny, změnit zdrojové URL a upravit rozvrhy obnovy bez změny kódu.
