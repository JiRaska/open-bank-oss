# API

REST kontrakt je formalizován v [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version 1.35.0`). Swagger UI je dostupné na `/api/docs`. Všechny cesty jsou verzované pod `/api/v1` (ADR-0048: OpenAPI `major` == `openbank.api.version` == URL `/api/v{N}`).

Základní cesta: `/api/v1/lending`. Všechny endpointy vyžadují Keycloak bearer JWT (`bearerAuth`).

V produkci používá `/api/*` na běžném HTTP i na mTLS portu 8443 autentizační mechanismus bearer.
Port 8443 nadále vyžaduje důvěryhodný klientský certifikát při TLS handshake, ale samotný
certifikát volajícího k API nepřihlásí: požadavek s certifikátem a bez bearer tokenu vrací 401.
Platný bearer token poskytuje identitu pro API. Kontrola rolí na trasách zůstává povinná;
zamítavé rozhodnutí OPA blokuje požadavek jen při `AUTHZ_ENFORCE=true` (`authz.enforce` má
výchozí hodnotu `false`). Autentizace přenosu tak zůstává oddělená od identity v API, aniž by
se změnilo stávající nastavení autorizace.

## Autorizace

Třída resource je role-gated; **jednající principal je vždy ověřený JWT subjekt** (`SecurityIdentity.principal.name`), nikdy pole z requestu. Role na úrovni třídy: `ROLE_LENDING_OFFICER`, `ROLE_CREDIT_RISK`, `ROLE_COMPLIANCE`, `ROLE_ADMIN`. Per-endpoint override toto zužuje:

| Endpoint | Metoda | Role | Poznámky |
|---|---|---|---|
| `/applications` | `POST` | (role třídy) | Podání žádosti (maker). 201 / 400 |
| `/applications` | `GET` | (role třídy) | Seznam žádostí dle `partyId` (povinný query) |
| `/applications/{id}` | `GET` | (role třídy) | 200 / 404 |
| `/applications/{id}/advance` | `POST` | (role třídy) | Posun o jeden krok; nelze opustit `FOUR_EYES` ani pokračovat po rozhodnutí bez zaznamenaného schvalovatele. 200 / 409 / 422 |
| `/applications/{id}/decision` | `POST` | `ROLE_CREDIT_RISK`, `ROLE_ADMIN` | Rozhodnutí ve `FOUR_EYES` (checker). Musí se lišit od navrhovatele. 200 / 409 |
| `/applications/{id}/disburse` | `POST` | `ROLE_LENDING_OFFICER`, `ROLE_ADMIN` | Čerpání ve `READY_TO_DISBURSE` se zaznamenaným rozhodnutím. Čerpající se musí lišit od navrhovatele i schvalovatele. 201 / 409 |
| `/loans` | `GET` | (role třídy) | Seznam úvěrů dle `partyId` (povinný query) |
| `/loans/{id}` | `GET` | (role třídy) | 200 / 404 |
| `/loans/{id}/schedule` | `GET` | (role třídy) | Splátkový kalendář |
| `/loans/{id}/installments/{installmentId}/repay` | `POST` | (role třídy) | Zaznamenat splátku. 200 / 409 |
| `/loans/{id}/writeoff` | `POST` | `ROLE_CREDIT_RISK`, `ROLE_COMPLIANCE`, `ROLE_ADMIN` | Odepsat zbývající expozici. 200 / 409 |
| `/loans/{id}/collateral` | `POST` | (role třídy) | Evidovat zajištění. 201 / 400 |
| `/loans/{id}/collateral` | `GET` | (role třídy) | Seznam zajištění |
| `/loans/{id}/provisioning` | `GET` | `ROLE_CREDIT_RISK`, `ROLE_COMPLIANCE`, `ROLE_ADMIN` | IFRS 9 stage + ECL. Volitelný `asOf` (datum). 200 / 404 |

Naplánovaný měsíční cyklus IFRS 9 provisioningu (ADR-0028 Fáze 3, `ProvisioningCycleScheduler`) **není** v tomto přírůstku spouštěn přes REST — běží pouze podle `lending.provisioning.cycle.every`. `GET /loans/{id}/provisioning` zůstává on-demand, nepersistovaným čtením; persistovaná historie po období, kterou zatím nevystavuje, žije v `loan_provisioning` (zatím bez read endpointu — přirozený malý follow-up).

## Čtyřoč princip / segregace odpovědností

Vznik úvěru je řetězec maker-checker-disburser vynucený na serveru (ADR-0028 D5, EBA/GL/2020/06):

```
maker (POST /applications)           → žádost SUBMITTED, proposed_by = JWT subjekt
advance                              → FOUR_EYES, potom čeká na výslovné rozhodnutí
checker (POST .../decision)          → OFFERED nebo DECLINED, decided_by = JWT subjekt
                                        409 pokud decided_by == proposed_by
advance po rozhodnutí                → READY_TO_DISBURSE jen se zaznamenaným schvalovatelem
disburser (POST .../disburse)        → DISBURSED + úvěr zaúčtován
                                        409 pokud disburser == proposed_by nebo decided_by
```

Rozhodnutí lze přijmout pouze ve stavu `FOUR_EYES`; čerpání pouze ve stavu
`READY_TO_DISBURSE`. Obecný `advance` ve `FOUR_EYES` vrací
`409 FOUR_EYES_DECISION_REQUIRED`. Žádost za tímto bodem bez zaznamenaného schvalovatele
vrací při posunu nebo čerpání `409 FOUR_EYES_DECISION_MISSING`. Zamítnutý příkaz nemění stav a
zapisuje samostatnou outbox událost `credit.application.transition.refused`, aby jej čtenář
historie nepovažoval za provedený přechod.

## Request schémata (vybrané)

- **LoanApplicationRequest** — `partyId` (uuid), `requestedAmount` (Money), `nominalAnnualRate` (number), `termPeriods` (int), `periodsPerYear` (int, výchozí 12), `method` (`ANNUITY`|`EQUAL_PRINCIPAL`|`BULLET`, výchozí ANNUITY), `firstDueDate` (date). **Žádné `proposedBy`** — maker je JWT subjekt.
- **DecisionRequest** — `approve` (bool, povinné), `reason` (string, nullable). **Žádné `decidedBy`** — checker je JWT subjekt.
- **CollateralRequest** — `type` (string), `description` (nullable), `marketValue` (Money), `haircut` (number, výchozí 0, validováno na `[0,1]`).
- **WriteOffRequest** — `reason` (string, nullable). Jednající principal je JWT subjekt.
- **Money** — `{ amount: number, currency: ISO-4217 }`.

Validace (aplikační služba): požadovaná částka musí být kladná, term ≥ 1 období, nominální sazba ≥ 0, identita navrhovatele neprázdná, haircut v `[0,1]`.

## Idempotence

CORS povoluje hlavičku `Idempotency-Key` a služba má nakonfigurovaný Redis klient pro idempotenční plumbing (přes libs). Na **hranici ledgeru** je idempotence vnitřní: reference ekonomické události každého zápisu (např. `loan:<id>:disbursement`, `loan:<id>:inst:<n>:accrual`) se použije jako `idempotencyKey` ledgeru, takže opakování kolabuje do jediného zápisu. Akruální průchod je idempotentní přes řádkový příznak `interest_accrued`.

## Chybový model

Starší chyby vrací `application/json` ve tvaru `{ "error": "<zpráva>" }`.
Kódovaná zamítnutí čtyřoč vrací `{ "error": "<kód>", "message": "<popis>" }`;
tělo neobsahuje identitu navrhovatele, schvalovatele ani čerpajícího. Mapování stavů v `LendingResource`:

- `400 Bad Request` — selhání validace při vytvoření (`applyForLoan`, `registerCollateral`).
- `404 Not Found` — neznámá žádost / úvěr (a při selhání lookup v `provisioning`).
- `409 Conflict` — nelegální přechod stavu nebo porušení čtyřoč / segregace odpovědností.
  Kódovaná zamítnutí používají `FOUR_EYES_DECISION_REQUIRED`, `FOUR_EYES_DECISION_MISSING`
  nebo `SEGREGATION_OF_DUTIES` (advance, decision, disburse).
- `201 Created` — žádost přijata, úvěr načerpán, zajištění evidováno.
- `200 OK` — čtení, rozhodnutí aplikováno, splátka zaznamenána, odpis, snímek opravných položek.

## Verzování

URL cesta `/api/v1/...`; hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info` poskytuje `openbank-libs`. Verze OpenAPI kontraktu (`info.version`) je osa API kontraktu (ADR-0048), nezávislá na release verzi `version.txt`.
