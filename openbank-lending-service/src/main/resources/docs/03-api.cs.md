# API

REST kontrakt je formalizován v [`openapi.yaml`](../openapi.yaml) (OpenAPI 3.1.0, `info.version 0.1.0`). Swagger UI je dostupné na `/api/docs`. Všechny cesty jsou verzované pod `/api/v1` (ADR-0048: OpenAPI `major` == `openbank.api.version` == URL `/api/v{N}`).

Základní cesta: `/api/v1/lending`. Všechny endpointy vyžadují Keycloak bearer JWT (`bearerAuth`).

## Autorizace

Třída resource je role-gated; **jednající principal je vždy ověřený JWT subjekt** (`SecurityIdentity.principal.name`), nikdy pole z requestu. Role na úrovni třídy: `ROLE_LENDING_OFFICER`, `ROLE_CREDIT_RISK`, `ROLE_COMPLIANCE`, `ROLE_ADMIN`. Per-endpoint override toto zužuje:

| Endpoint | Metoda | Role | Poznámky |
|---|---|---|---|
| `/applications` | `POST` | (role třídy) | Podání žádosti (maker). 201 / 400 |
| `/applications` | `GET` | (role třídy) | Seznam žádostí dle `partyId` (povinný query) |
| `/applications/{id}` | `GET` | (role třídy) | 200 / 404 |
| `/applications/{id}/decision` | `POST` | `ROLE_CREDIT_RISK`, `ROLE_ADMIN` | Schválit/zamítnout (checker). Checker se musí lišit od makera. 200 / 409 |
| `/applications/{id}/disburse` | `POST` | `ROLE_LENDING_OFFICER`, `ROLE_ADMIN` | Čerpání schváleného úvěru. Disburser se musí lišit od checkera. 201 / 409 |
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
maker (POST /applications)           → žádost PROPOSED, proposed_by = JWT subjekt
checker (POST .../decision)          → APPROVED/REJECTED, decided_by = JWT subjekt
                                        409 pokud decided_by == proposed_by  (porušení čtyřoč)
disburser (POST .../disburse)        → DISBURSED + úvěr zaúčtován
                                        409 pokud disburser == decided_by    (segregace odpovědností)
```

Rozhodnutí je přijato pouze nad žádostí ve stavu `PROPOSED`; čerpání pouze nad `APPROVED`; jinak `409`.

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

Chyby vrací `application/json` ve tvaru `{ "error": "<zpráva>" }` (`ApiError`). Mapování stavů dle `LendingResource`:

- `400 Bad Request` — selhání validace při vytvoření (`applyForLoan`, `registerCollateral`).
- `404 Not Found` — neznámá žádost / úvěr (a při selhání lookup v `provisioning`).
- `409 Conflict` — nelegální přechod stavu nebo porušení čtyřoč / segregace odpovědností (decision, disburse, repay, writeoff).
- `201 Created` — žádost přijata, úvěr načerpán, zajištění evidováno.
- `200 OK` — čtení, rozhodnutí aplikováno, splátka zaznamenána, odpis, snímek opravných položek.

## Verzování

URL cesta `/api/v1/...`; hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info` poskytuje `openbank-libs`. Verze OpenAPI kontraktu (`info.version`) je osa API kontraktu (ADR-0048), nezávislá na release verzi `version.txt`.
