# Přehled

## Co služba dělá

`openbank-interest-service` je **úrokový engine** platformy OpenBank. Drží:

- **InterestRateConfig** — konfiguraci sazby na produkt: roční sazbu, typ sazby (FIXED / VARIABLE / TIERED), konvenci počítání dní (ACT_365 / ACT_360 / ACT_ACT / 30_360), pásma zůstatku (`minBalance` / `maxBalance`) a okno platnosti (`effectiveFrom` / `effectiveTo`).
- **InterestAccrual** — jeden denní záznam naběhlého úroku na `(účet, accrualDate, produkt)`: zůstatek, denní sazbu a naběhlou částku. Stav `ACCRUING → CAPITALIZED` (případně `REVERSED` / `SUSPENDED`).
- **InterestCapitalization** — periodické připsání naběhlého úroku, nesoucí rozpad brutto / daň / netto (ADR-0033): zákazníkovi se připisuje **netto** částka.
- **WithholdingTax** — párovou daňovou povinnost srážkové daně na každou kapitalizaci (treatment, daňový základ, sazba, částka daně, stav `RECORDED → REMITTED → RECONCILED` / `REVERSED`).
- **WithholdingRemittance** — měsíční dávku odvodu (*Vyúčtování daně vybírané srážkou*, ADR-0038) agregující veškerou CZK daň sraženou v daném zdaňovacím měsíci, splatnou finančnímu úřadu do data splatnosti.

## Co služba **NEDĚLÁ**

- ❌ Nedrží ani nepočítá zůstatky účtů — autoritativní zůstatek je `balance-service`; úročení používá zůstatek předaný v accrual požadavku.
- ❌ Neúčtuje podvojné zápisy — to dělá `ledger-service`; `capitalized.ledgerEntryId` je referenční odkaz (ve v1 nullable).
- ❌ Nepřesouvá hotovost zákazníkovi ani finančnímu úřadu — kapitalizace zaznamenává připsání; peněžní část daně (odvod) je delegována dále přes událost `interest.withholding.remitted.v1` (ADR-0030 off-gate).
- ❌ Zatím neřeší daňové atributy strany (party) příjemce — v1 dodává fail-safe výchozího providera (CZ rezident fyzická osoba); resoluce účet→party je dokumentovaný fast-follow.
- ❌ Neprovádí KYC/AML ani sanction screening.

## Pozice v doméně

```
   ┌────────────┐  POST /rates, /accrue,        ┌──────────────────┐
   │  admin UI  │  /capitalize, /remittances    │  scheduler (cron)│
   │ / operátor │ ───────────────────────────►  │ accrual 01:00    │
   └─────┬──────┘                               │ capitalize 02:00 │
         │                                       └────────┬─────────┘
         ▼                                                ▼
   ┌──────────────────┐  outbox → Kafka   ┌────────────────────────────┐
   │ interest-service │ ────────────────► │ daňový/reporting konzument │
   └────┬─────────────┘                   │ (platí finančnímu úřadu)   │
        │                                 │ audit-service              │
        ▼                                 │ ledger-service (kredit GL) │
    PostgreSQL                            └────────────────────────────┘
   (db: openbank_interest)
```

## Klíčové use-casy

| Use-case | API | Událost |
|---|---|---|
| Vytvořit konfiguraci úrokové sazby | `POST /api/v1/interest/rates` | — |
| Naběhnout úrok pro jeden účet | `POST /api/v1/interest/accrue` | — |
| Naběhnout úrok pro všechny účty | `POST /api/v1/interest/accrue/all` | — |
| Kapitalizovat naběhlý úrok (srazit daň, připsat netto) | `POST /api/v1/interest/capitalize/{accountId}` | `interest.withholding.recorded.v1` |
| Vypsat accrualy / souhrn pro účet | `GET /api/v1/interest/accruals/{accountId}[/summary]` | — |
| Vypsat historii kapitalizací | `GET /api/v1/interest/capitalizations/{accountId}` | — |
| Sestavit měsíční odvod srážkové daně | `POST /api/v1/interest/withholding/remittances?year=&month=` | `interest.withholding.remitted.v1` |
| Získat / vypsat dávky odvodu | `GET /api/v1/interest/withholding/remittances[/{year}/{month}]` | — |

## Kdo službu volá

- **admin-ui** (přes Keycloak token) — operátoři konfigurují sazby, spouští accrual/kapitalizaci a sestavují odvody.
- **scheduler** (interní Quarkus `@Scheduled`) — denní accrual cron `0 0 1 * * ?` a měsíční kapitalizační cron `0 0 2 1 * ?`.
- **servisní volající** (`ROLE_API`) — dávkové / orchestrační spouštěče.

## Závislosti

- **PostgreSQL** (`openbank-postgres`, databáze `openbank_interest`) — accrualy, kapitalizace, srážková daň, odvod, outbox.
- **Kafka** (`openbank-kafka`, topic `openbank.interest.accrual.event`) — odchozí události srážkové daně.
- **Redis (Valkey)** — zapojený jako závislost; resources ve v1 nepoužívají idempotency-key tok.
- **Keycloak** — OIDC autentizace.
- **openbank-libs** — sdílená runtime infrastruktura (BuildInfo, ServiceInfoResource, DocsResource, security).
- **TaxProfilePort** — resolvuje daňový profil příjemce; výchozí provider v1 vrací fail-safe profil CZ rezidenta fyzické osoby.

## Byznysová hodnota

- **Správný úrok zákazníka** — jeden testovaný engine sazby/počítání dní, takže logika accrualu nemůže driftovat mezi místy volání.
- **Zákonná daň u zdroje** — česká srážková daň (§36/§38d ZDP) je aplikována při kapitalizaci v jedné testované policy; zákazníkovi se připisuje netto a povinnost se zaznamenává pro audit (ADR-0033).
- **Regulatorní odvod** — měsíční *Vyúčtování daně vybírané srážkou* se sestavuje deterministicky a idempotentně na zdaňovací období, s peněžní částí delegovanou dále (ADR-0038).
- **Auditovatelnost** — každá kapitalizace zaznamenává rozhodnutí brutto/daň/netto (i nulové zdanění) a emituje verzovanou doménovou událost.
