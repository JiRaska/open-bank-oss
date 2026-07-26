# Přehled

## Co služba dělá

`openbank-anacredit-service` sestavuje **úvěrový datový soubor AnaCredit** (Reg. (EU) 2016/867, ECB; národně sbírá Česká národní banka) k měsíčnímu referenčnímu datu. Je to **odvozující (derive-only)** výkaznická projekce ([ADR 0037](../../../../docs/adr/0037-anacredit-credit-exposure-reporting.md)), která:

- **Eviduje úvěrové expozice** — jeden řádek na úvěrový nástroj tak, jak ho vidí feed: `instrumentId`, `debtorId`, `debtorType` (LEGAL_ENTITY / NATURAL_PERSON), `instrumentType` (OVERDRAFT / CREDIT_CARD_CREDIT / REVOLVING_CREDIT / LOAN), nativní `committedAmount` / `drawnAmount`, EUR ekvivalent závazku `committedAmountEur`, `arrearsAmount`, `defaulted`, `originationDate`.
- **Aplikuje bránu způsobilosti AnaCredit** — dvě regulatorní pravidla, čistá doménová logika:
  1. **Rozsah: pouze právnické osoby.** Dlužník fyzická osoba (domácnost / spotřebitel) je mimo rozsah → vyloučen s důvodem `HOUSEHOLD_OUT_OF_SCOPE`.
  2. **Materialita: práh závazku €25 000 na dlužníka.** Vyhodnocuje se na *celkovém* závazku dlužníka napříč všemi jeho nástroji, nikoliv per nástroj → pod prahem `BELOW_THRESHOLD`. Nástroj bez závazku i bez čerpání → `NO_EXPOSURE`.
- **Vykreslí výkaz** — reportovatelné nástroje se mapují na řádky úvěrového/finančního datového souboru (`outstandingNominalAmount = drawn`, `offBalanceSheetAmount = max(committed − drawn, 0)`, `arrearsAmount`, `defaultStatus`) a každý vyřazený nástroj je zaznamenán do **auditní stopy vyloučení** s důvodovým kódem.

## Co služba **NEDĚLÁ**

- ❌ Nehýbe penězi, neúčtuje do ledgeru, nemění žádný zůstatek — pouze čte expozice, které dostala.
- ❌ Neemituje ani nekonzumuje doménové události — ve v1 není žádný outbox ani Kafka binding.
- ❌ Neodesílá nic do statistického kanálu ČNB / ECB — **žádný SDMX transport** ve v1; pouze vykresluje.
- ❌ Nevlastní referenční datový soubor protistran ani neprodukuje čtvrtletní účetní datový soubor (non-goaly v1, ADR-0037).
- ❌ Nedělá FX konverzi — `committedAmountEur` dodává volající (zdroj `openbank-fx-service`); datový soubor reportuje nativní částky.
- ❌ Není zdrojem pravdy o nástrojích — expozice se ve v1 vkládají přes REST; ingest z `balance.overdraft.*` událostí je zdokumentovaný follow-up.

## Pozice v doméně

```
   ┌────────────┐   POST /exposures (feed)    ┌──────────────────────┐
   │  operátor  │ ─────────────────────────►  │                      │
   │ / upstream │   GET /returns/{date}        │  anacredit-service   │
   └────────────┘ ◄─────────────────────────  │  (derive-only)       │
                      vykreslený výkaz         └──────────┬───────────┘
                                                          │ PostgreSQL (ADR-0037 v2)
   ┌────────────┐                                         ▼
   │ fx-service │ ─ committedAmountEur ─►          credit_exposures
   └────────────┘   (dodá volající)           (anacredit_schema)

   navazující odeslání regulátorovi (ČNB) = MIMO ROZSAH
```

## Klíčové případy užití

| Případ užití | API | Událost |
|---|---|---|
| Registrace / náhrada úvěrové expozice | `POST /api/v1/anacredit/exposures` | — (žádná) |
| Výpis všech známých expozic | `GET /api/v1/anacredit/exposures` | — |
| Vykreslení výkazu AnaCredit k referenčnímu datu | `GET /api/v1/anacredit/returns/{referenceDate}` | — |

## Kdo službu volá

- **admin-ui / operátoři** (přes Keycloak token) — compliance a pracovníci regulatorního výkaznictví registrují expozice a stahují měsíční výkaz.
- **upstream feed / servisní účty** (`ROLE_API`) — dávková nebo navazující služba posílající kontokorentní expozice (v1: manuálně/REST; budoucnost: event-driven).
- **auditoři** (`ROLE_AUDITOR`) — čtou výkaz včetně stopy vyloučení jako důkaz.

## Závislosti

- **Keycloak** — OIDC autentizace / vynucení rolí.
- **openbank-libs** — `ServiceInfoResource` (`/api/v1/info`), `DocsResource` (tato dokumentace), `BuildInfo`.
- **PostgreSQL** (`openbank_anacredit`) za běhu pro store `credit_exposures` (ADR-0037 v2). **Žádná** Kafka, **žádný** Redis.
- `openbank-fx-service` — pouze *logická* závislost: volající ji použije k získání `committedAmountEur` před registrací expozice; anacredit-service ji nevolá.

## Obchodní hodnota

- **Regulatorní pokrytí** — produkuje granulární úvěrový datový soubor AnaCredit, který je OpenBank povinen vykazovat (Reg. (EU) 2016/867), počínaje kontokorentními expozicemi.
- **Čistá, auditovatelná pravidla** — rozsah + materialita jsou jediná čistě doménová politika se stabilními, auditně orientovanými důvodovými kódy vyloučení; každý vyřazený nástroj je vysvětlen.
- **Levný provoz** — žádná rezidentní databáze, žádné posluchače událostí; služba je v klidu téměř bez nákladů (FinOps tier T1, viz [05 — Provoz](./05-operations.md)).
- **Rozšiřitelnost** — úvěry a kreditní karty (ADR-0028) zapojí další typy nástrojů do téhož builderu bez zásahu do doménových pravidel.
