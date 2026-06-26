# API

REST kontrakt je publikován na `/q/openapi` a prohlížitelný přes Swagger UI na `/api/docs`. Commitnutý kontrakt je [`openapi.yaml`](../openapi.yaml) (`info.version 1.1.0`, OpenAPI 3.1.0). Všechny cesty jsou pod `/api/v1` (ADR-0048 — URL major odpovídá `openbank.api.version`).

> **Pozn. ke kontraktu:** commitnutý `openapi.yaml` aktuálně dokumentuje pouze čtyři jádrové FX endpointy. Query parametr `?source=CNB` na dotazu kurzu a dva endpointy `/api/v1/fx/cnb/...` existují v resource třídách (`FxResource`, `CnbResource`), ale **zatím nejsou v `openapi.yaml`** — odstranění tohoto driftu je follow-up. Endpointy níže jsou dokumentovány podle skutečného kódu.

## Endpointy

### FX kurzy & konverze (`FxResource`)

| Metoda | Cesta | Role | Účel |
|---|---|---|---|
| `GET` | `/api/v1/fx/rates` | VIEWER, OPERATOR, ADMIN, PAYMENTS | Výpis všech aktuálních FX kurzů |
| `GET` | `/api/v1/fx/rates/{base}/{quote}` | VIEWER, OPERATOR, ADMIN, PAYMENTS | Poslední SPOT kurz pro pár; `?source=CNB` vrátí fixing ČNB |
| `POST` | `/api/v1/fx/convert` | OPERATOR, ADMIN, PAYMENTS | Provedení prověřené konverze (idempotentní) |
| `GET` | `/api/v1/fx/conversions/{id}` | VIEWER, OPERATOR, ADMIN, PAYMENTS | Načtení konverze podle id |

### ČNB fixing — ops/backfill (`CnbResource`)

| Metoda | Cesta | Role | Účel |
|---|---|---|---|
| `POST` | `/api/v1/fx/cnb/ingest` | OPERATOR, ADMIN | Ingest ČNB fixingu pro `?date=YYYY-MM-DD` (idempotentní; bez data = nejnovější) |
| `GET` | `/api/v1/fx/cnb/rates/{base}` | VIEWER, OPERATOR, ADMIN, PAYMENTS | Poslední ingestnutý ČNB fixing pro `{base}/CZK` |

Role jsou vynuceny přes Quarkus `@RolesAllowed` nad bearer tokeny vydanými Keycloakem.

## Convert request / response

**`POST /api/v1/fx/convert`** — hlavička `Idempotency-Key` je **povinná** (resource odmítne prázdný klíč).

Tělo požadavku (`ConvertRequest`):

```json
{
  "partyId": "uuid",
  "accountId": "uuid|null",
  "partyName": "ACME s.r.o.",
  "fromCurrency": "EUR",
  "toCurrency": "CZK",
  "fromAmountMinorUnits": 100000
}
```

`partyName` je synchronně prověřen proti sankčním seznamům (ADR-0032). Konverze je spočítána z `askRate` posledního platného **SPOT** kurzu; aplikuje se fixní **0,5% poplatek** (`feeMinorUnits`), zaokrouhleno `HALF_UP` na celé minor units.

Odpověď — `201 Created`, `Location: /api/v1/fx/conversions/{id}` (`ConversionResponse`):

```json
{
  "id": "uuid",
  "fromCurrency": "EUR",
  "toCurrency": "CZK",
  "fromAmount": 100000,
  "toAmount": 2515000,
  "appliedRate": 25.15,
  "status": "SETTLED",
  "convertedAt": "2026-06-09T14:41:00Z"
}
```

`status` ∈ `PENDING | SETTLED | FAILED | REVERSED`:

| Status | Kdy | Vedlejší efekt |
|---|---|---|
| `SETTLED` | screening CLEAR | vyslán `FxConversionExecuted` |
| `PENDING` | screening REVIEW (potenciální zásah ≤ 0.85) nebo nedostupný screening | otevřen HIGH / MEDIUM AML případ; drženo k lidskému přezkumu |
| `FAILED` | screening BLOCK (HIT / ESCALATED / potenciální zásah > 0.85) | otevřen CRITICAL AML případ |
| `REVERSED` | rezervováno (zatím bez reversal endpointu) | — |

## Idempotence

`POST /convert` vyžaduje `Idempotency-Key`. Při opakování `FxService` dohledá konverzi podle klíče (`fx_conversions.idempotency_key` je `UNIQUE`) a vrátí uloženou — stejná konverze se nikdy neprovede dvakrát.

## Verzování

- URL major: `/api/v1`. OpenAPI `info.version` je osa API-kontraktu (ADR-0048), nezávislá na release `version.txt`.
- Hlavičky `X-API-Version` / `X-Service-Version` a `/api/v1/info` poskytuje `openbank-libs`.

## Chybový model

Chyby nesou malé JSON tělo. OpenAPI schéma `ApiError` je `{ code, message }`; aktuální kód resource vrací při not-found (`404`) `{ "error": "<message>" }`. Časté případy:

| Status | Příčina |
|---|---|
| `400` | prázdný `Idempotency-Key`; nevalidní tělo požadavku |
| `401` / `403` | chybějící/nevalidní token; role bez oprávnění |
| `404` | kurz pro pár nenalezen; neznámé id konverze; žádný ČNB kurz pro `{base}/CZK` |
| `4xx/5xx` | žádný platný SPOT kurz, nebo kurz expiroval (`require`/`error` v use-casu) |
