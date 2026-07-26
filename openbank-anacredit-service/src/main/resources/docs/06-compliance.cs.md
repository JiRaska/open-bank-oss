# Compliance

## Regulatorní rámec

| Regulace | Vztah k této službě | Implementace |
|---|---|---|
| **AnaCredit — Reg. (EU) 2016/867** (ECB; národně sbírá ČNB) | Důvod existence služby: sestavuje granulární úvěrový datový soubor pro úvěrové expozice právnických osob | `AnaCreditReturnBuilder` + `AnaCreditMapper` vykreslují řádky úvěrového/finančního datového souboru; `AnaCreditEligibilityPolicy` aplikuje rozsah + práh €25 000 |
| **Statistická vykazovací povinnost ČNB** | OpenBank musí AnaCredit vykazovat ČNB | v1 **pouze vykresluje** — žádný SDMX submisní transport (zdokumentovaný non-goal v1, ADR-0037) |
| **GDPR** | Datový soubor je z principu jen pro právnické osoby; fyzické osoby jsou mimo rozsah | vyloučení `HOUSEHOLD_OUT_OF_SCOPE` drží dlužníky fyzické osoby mimo výkaz; klasifikace dat `restricted` |
| **DORA — Reg. (EU) 2022/2554** | Provozní odolnost výkaznické služby | health probes, `BuildInfo` v `/api/v1/info`, auditně přívětivá stopa vyloučení, FinOps tier T1 |
| **NIS2** | Síťová a informační bezpečnost | zpevněné response hlavičky (CSP, HSTS, X-Frame-Options DENY), Keycloak OIDC, TLS v clusteru |
| **CRR / kapitálové výkaznictví (sousední)** | Data AnaCredit živí dohledovou úvěrovou analýzu | derive-only; tato služba produkuje granulární feed, nikoliv výkazy COREP/FINREP |

Tato služba **není** na money-path (`rules.yaml: money_path_services`); nehýbe penězi a neemituje události, takže brána ADR-0030 (threat model + 2 schválení) se **neuplatní**.

## GDPR mapping

### Rozsah osobních údajů

Reportovatelný datový soubor AnaCredit pokrývá **pouze právnické osoby**. Dlužníci fyzické osoby (domácnost / spotřebitel) jsou z vykresleného výkazu záměrně vyloučeni s důvodem `HOUSEHOLD_OUT_OF_SCOPE`, takže výstup z principu **neobsahuje žádné osobní údaje fyzických osob**. Expozice fyzické osoby může existovat v (nyní trvanlivém, PostgreSQL) storu expozic, pokud je vložena, ale nikdy se nedostane do výkazu.

### Právní základ (čl. 6)

- **Právní povinnost** (čl. 6 odst. 1 písm. c)) — výkaznictví AnaCredit dle Reg. (EU) 2016/867 a sběrný mandát ČNB je primárním základem pro zpracování dat o úvěrových expozicích.

### Práva subjektů údajů

| Právo | Aplikace |
|---|---|
| Přístup (čl. 15) | u reportovatelných řádků je subjektem právnická osoba (ne subjekt údajů dle GDPR); jakákoliv nahodilá expozice fyzické osoby je z výstupu vyloučena |
| Oprava (čl. 16) | re-POST expozice (`upsert` dle `instrumentId`) s opravenými hodnotami |
| Výmaz (čl. 17) | **omezeno** — regulatorní uchovávání záznamů (`retentionPolicy: 10 let`, nyní vynucováno nad trvanlivým řádkem `credit_exposures`, nikoliv volatilní in-memory mapou) má přednost před výmazem u reportovatelných dat v rozsahu |
| Omezení (čl. 18) | mechanismus vyloučení (drop z výkazu) poskytuje přirozenou plochu pro omezení |
| Přenositelnost (čl. 20) | N/A — regulatorní výkaznictví, nikoliv služba spotřebitelských dat |

## Datové toky

### Dovnitř

- ← **operátor / upstream feed** (REST `POST /exposures`): atributy úvěrového nástroje vč. `debtorId`, nativní a EUR částky. `committedAmountEur` dodává volající (zdroj `openbank-fx-service`).

### Interně

- Výkaz se počítá in-process čistým doménovým kódem; během vykreslování se žádná data neposílají do jiné služby.

### Ven

- → **volající API** (REST odpověď): vykreslený `AnaCreditReturn` (záznamy právnických osob + stopa vyloučení). Žádná odchozí Kafka, žádné volání navazující služby.
- → **ČNB**: **ne ve v1** — neexistuje submisní transport; operátor vykreslený výkaz extrahuje manuálně, dokud nebude postaven SDMX kanál.

Žádná data neopouštějí region EU/EHP.

## DORA mapping (Reg. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| čl. 9 | Identifikace | `BuildInfo` (gitCommit, buildTime, version) v `/api/v1/info` |
| čl. 10 | Detekce | metriky + health probes (`/q/health`) |
| čl. 11 | Reakce & obnova | trvanlivý Postgres store (ADR-0037 v2) ⇒ obnova je standardní DB restore, nikoliv plné znovu-naplnění (runbook v [05 — Provoz](./05-operations.md)); v sázce není žádný peněžní stav |
| čl. 28 | Riziko třetích stran | žádný third-party SaaS — self-hosted; za běhu žádná externí závislost |

## Auditní stopa

Výkaz AnaCredit je **sebe-auditující**: každý nástroj se objeví buď jako `CreditRecord`, nebo jako `ExclusionNote` se stabilním důvodovým kódem (`HOUSEHOLD_OUT_OF_SCOPE` / `BELOW_THRESHOLD` / `NO_EXPOSURE`). `reportableCount` + `excludedCount` se odsouhlasí proti počtu vstupních expozic, což dává úplné, vysvětlitelné odvození pro regulátora nebo auditora. Služba ve v1 **neemituje** žádné doménové události do `audit-service` (derive-only); auditovatelnost zajišťuje deterministické, reprodukovatelné vykreslení nad vstupní množinou.

## Bezpečnostní kontroly

- ✅ AuthN: Keycloak OIDC bearer token (realm `openbank`)
- ✅ AuthZ: Quarkus `@RolesAllowed` (`ROLE_OPERATOR / ROLE_ADMIN / ROLE_AUDITOR / ROLE_COMPLIANCE / ROLE_API`)
- ✅ Zpevněné HTTP hlavičky: CSP `default-src 'self'`, HSTS, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, Referrer-Policy, Permissions-Policy
- ✅ CORS: omezeno na originy admin-UI
- ✅ Validace vstupu: typové enumy (`CounterpartyType`, `InstrumentType`), `BigDecimal` částky, parsování ISO data
- ✅ Bezpečnost výstupu: Jackson serializace; výstup jen pro právnické osoby drží PII fyzických osob mimo z konstrukce
- ✅ Trvanlivost: tabulka `credit_exposures` na PostgreSQL (ADR-0037 v2) — přežije restart podu; in-memory store z v1 byl odstraněn
- ⚠️ Odeslání: žádný automatizovaný ČNB transport — manuální extrakce, sledováno jako non-goal ADR-0037
- N/A Idempotency klíče / outbox: nepotřebné — registrace `upsert`-dle-id a čistá čtení, žádné události
