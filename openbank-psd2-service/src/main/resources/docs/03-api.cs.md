# API a kontrakty

## Základní cesta

- **Port aplikace:** `8107` (v clusteru `http://openbank-psd2-service:8107`), management port `8085` (root-path `/q`).
- **Open Banking základní cesta:** `/open-banking/v2`
- **Sandbox základní cesta:** `/open-banking/sandbox/v2`
- **OpenAPI specifikace:** [`src/main/resources/openapi.yaml`](../openapi.yaml) (`info.version: 2.0.0`)
- **Swagger UI:** `/open-banking/docs`

> **Drift kontraktu k sladění (zatím neformalizováno):** zaverzovaný `openapi.yaml` popisuje názvy hlaviček `X-Consent-Id` / `X-Idempotency-Key` a lokální server na portu `8122`, zatímco JAX-RS zdroje ve skutečnosti čtou `Consent-ID` / `Idempotency-Key` a aplikace běží na `8107`. Pro runtime chování je autoritativní kód zdrojů; OpenAPI dokument a zdroje je třeba sladit (sledováno jako follow-up). Popisy níže odrážejí **kód**.

## Autentizace a autorizace

PSD2 endpointy **nejsou** hlídány Keycloakem. Identitu a roli TPP vynucuje `EidasMtlsFilter`:

| Krok | Mechanismus |
|---|---|
| Identita TPP | subject DN eIDAS QWAC klientského certifikátu předaný jako `SSL-CLIENT-S-DN` (terminovaný na bráně), **nebo** hlavička `X-TPP-ID` |
| Kontrola role | `tpp-registry-service` `GET /api/v1/tpp-registry/check?tppId&role`; role = `PISP` pro `/payments`, jinak `AISP` |
| Přístup ke zdroji | `consent-service` `validateConsent(consentId, tppId, scope, iban)` u každého AIS čtení a PIS iniciace |

Výsledky z filtru:

| HTTP | `tppMessages.code` | Kdy |
|---|---|---|
| 401 | `CERTIFICATE_MISSING` | žádný QWAC ani `X-TPP-ID` |
| 401 | `CERTIFICATE_INVALID` | TPP neautorizován pro požadovanou roli |
| 503 | `SERVICE_UNAVAILABLE` | tpp-registry nedostupný / circuit open |

Sandbox cesty filtr zcela přeskakují.

## Idempotence

| Povrch | Zdroj klíče | Cache klíč | Úložiště |
|---|---|---|---|
| PIS — iniciace platby | hlavička `Idempotency-Key` (povinná, nesmí být prázdná) | `psd2:payment:{tppId}:{product}:{key}` | Redis, TTL 24 h |
| Vytvoření souhlasu | hlavička `X-Request-ID` (povinná) | `psd2:consent:{tppId}:{requestId}` | Redis, TTL 24 h |

Při zásahu cache se původní status kód a tělo přehrají s hlavičkou odpovědi `X-Idempotency-Replayed: true`. TTL nastavuje `openbank.psd2.idempotency-ttl-seconds` (výchozí `86400`).

## Account Information Service (AIS)

```http
GET /open-banking/v2/accounts
Consent-ID: <consentId>
# Identita TPP přes QWAC (SSL-CLIENT-S-DN) nebo hlavičku X-TPP-ID
```

```http
GET /open-banking/v2/accounts/{accountId}/balances
Consent-ID: <consentId>
```

```http
GET /open-banking/v2/accounts/{accountId}/transactions
  ?dateFrom=2026-01-01&dateTo=2026-06-30
  &bookingStatus=BOOKED|PENDING|BOTH
  &limit=50&afterCursor=<cursor>
Consent-ID: <consentId>
```

Transakce jsou stránkované; pokud existují další výsledky, odpověď nese `_links.next.href = "?afterCursor=…"`. Booking status má výchozí hodnotu `BOOKED` a `limit` výchozí `50`. Každé AIS volání nejprve ověří souhlas pro odpovídající scope (`ACCOUNTS_READ` / `BALANCES_READ` / `TRANSACTIONS_READ`); selhání ⇒ `ConsentUnauthorizedException` → `401 CONSENT_INVALID`.

## Životní cyklus souhlasu

```http
POST /open-banking/v2/consents
X-Request-ID: <uuid>            # povinné
TPP-Redirect-URI: <uri>         # volitelné
TPP-Name: <name>                # volitelné, výchozí = tppId

{ "access": { "accounts": [...], "balances": [...], "transactions": [...] },
  "recurringIndicator": true, "validUntil": "2026-09-01",
  "frequencyPerDay": 4, "combinedServiceIndicator": false }
```

```http
201 Created
Location: /open-banking/v2/consents/{consentId}
{ "consentId": "...", "consentStatus": "RECEIVED", "access": {...},
  "links": { "self": "...", "status": ".../status",
             "scaRedirect": ".../authorisations" } }
```

- Požadované `validUntil` je omezeno na **90 dní** od teď.
- `access` se překládá na interní scopes (`ACCOUNTS_READ`, `BALANCES_READ`, `TRANSACTIONS_READ`, plus ČOBS rozšíření `STANDING_ORDERS_READ`, `DIRECT_DEBITS_READ`).
- `GET /open-banking/v2/consents/{id}` a `GET .../{id}/status` vracejí souhlas / jeho stav; `DELETE .../{id}` jej ruší (`204 No Content`).
- Stav souhlasu je mapován z interních stavů: `ACTIVE→VALID`, `PENDING_SCA→RECEIVED`, `REVOKED→REVOKED_BY_PSU`, `EXPIRED→EXPIRED`, `REJECTED→REJECTED`.

## Payment Initiation Service (PIS)

```http
POST /open-banking/v2/payments/sepa-credit-transfers
POST /open-banking/v2/payments/instant-sepa-credit-transfers
POST /open-banking/v2/payments/domestic-cz
POST /open-banking/v2/payments/sipo
Consent-ID: <consentId>
Idempotency-Key: <uuid>         # povinné
```

Tělo požadavku se liší dle produktu:

| Produkt | Model těla | Pozoruhodná pole |
|---|---|---|
| SEPA / instant SEPA | `PaymentInitiation` | `debtorAccount.iban`, `creditorAccount.iban`, `creditorName`, `instructedAmount {currency, amount}`, `endToEndIdentification`, `remittanceInformationUnstructured` |
| Tuzemská CZ | `DomesticCzPayment` | přidává `variableSymbol` / `specificSymbol` / `constantSymbol` (spojeny jako remittance) |
| SIPO | `SipoPayment` | `sipoNumber`, `variableSymbol`; věřitel pevně na sběrný účet SIPO, částka řešena downstream |

```http
201 Created
{ "paymentId": "...", "transactionStatus": "RCVD", "scaStatus": "received",
  "links": { "self": "...", "status": ".../status" } }
```

Každá iniciace ověří souhlas (scope `PAYMENTS_INITIATE`, nebo `DOMESTIC_PAYMENT_INITIATE` / `SIPO_PAYMENT_INITIATE`) a poté přepošle do `transaction-service`. Chybějící IBAN plátce/příjemce ⇒ `InvalidPaymentProductException` → `400 PRODUCT_INVALID`.

```http
GET /open-banking/v2/payments/{product}/{paymentId}/status
# product ∈ sepa-credit-transfers | instant-sepa-credit-transfers | domestic-cz | sipo
→ 200 { "transactionStatus": "RCVD|PDNG|ACTC|ACSC|RJCT|CANC" }
```

Neznámá hodnota `product` ⇒ `404`.

Hodnoty `transactionStatus` (ISO 20022): `RCVD` (přijato), `PDNG` (čeká), `ACTC` (přijata technická validace), `ACSC` (přijato, zúčtování dokončeno), `RJCT` (zamítnuto), `CANC` (zrušeno).

## Sandbox

`/open-banking/sandbox/v2/{accounts,…/balances,…/transactions,consents,payments/{product}}` a `…/health` vracejí deterministické fixtures (např. účet `CZ6508000000192000145399`, zůstatek `50000.00 CZK`) a obcházejí autentizaci TPP. Používáno vývojáři TPP během onboardingu.

## Chybový model

Chyby používají Open Banking / Berlin-Group obálku `tppMessages` (nikoli generický `ApiError` z `openbank-libs`):

```json
{ "tppMessages": [ { "category": "ERROR", "code": "CONSENT_INVALID", "text": "..." } ] }
```

| HTTP | code | Kdy (výjimka) |
|---|---|---|
| 400 | `FORMAT_ERROR` | `IllegalArgumentException` (např. prázdná povinná hlavička) |
| 400 | `PRODUCT_INVALID` | `InvalidPaymentProductException` |
| 401 | `CERTIFICATE_MISSING` | žádná identita TPP (filtr) |
| 401 | `CERTIFICATE_INVALID` | `TppNotAuthorizedException` / role odmítnuta |
| 401 | `CONSENT_INVALID` | `ConsentUnauthorizedException` |
| 404 | `CONSENT_UNKNOWN` | `ConsentNotFoundException` |
| 404 | — | neznámý platební produkt při dotazu na stav |
| 503 | `SERVICE_UNAVAILABLE` | tpp-registry circuit open / nedostupný |

## Verzování

- **Verze API v cestě** — `/open-banking/v2/...`. OpenAPI `info.version` je `2.0.0`.
- **Verze události v topicu** — `openbank.psd2.events` (aditivní evoluce; breaking změna ⇒ nový topic).
- Obě verzovací osy (release `version.txt` vs API `openapi.yaml:info.version`) jsou nezávislé (ADR-0048).
