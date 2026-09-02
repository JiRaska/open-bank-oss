# API & kontrakty

## Báze cesty

- **Veřejná báze:** `https://customer.open-bank.tech/customer/v1` (sandbox)
- **In-cluster app port:** 8128
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8128/q/openapi) — zdroj: `src/main/resources/openapi.yaml` (`info.version: 1.6.0`)

Kontrakt **je** formalizován v `openapi.yaml`. URL prefix je `/customer/v1` (pozn.: jde o vlastní zákaznickou verzní osu edge, odlišnou od `/api/v1` jednotlivých upstreamů).

## Autentizace

### Příchozí (volající → edge)

**Keycloak Bearer token** z realmu `openbank-customers`. Issuer je připnutý na veřejnou URL realmu (`QUARKUS_OIDC_TOKEN_ISSUER`, default `https://kc.open-bank.tech/realms/openbank-customers`); role se čtou z `realm_access/roles`.

| Cesta | Auth |
|---|---|
| `POST /onboarding/start` | **anonymní** (`@PermitAll`, v `OnboardingResource`) |
| vše ostatní | vyžadována `ROLE_CUSTOMER` (`@RolesAllowed`, třídní na `CustomerEdgeResource`) |

Lazy autentizace (`quarkus.http.auth.proactive=false`) drží veřejnou cestu skutečně anonymní, zatímco všechny ostatní cesty stále 401 bez platného zákaznického tokenu.

## Idempotence

Edge idempotenční klíče **neukládá**. U cest, jejichž upstream klíč vyžaduje, se hlavička `Idempotency-Key` od volajícího přeposílá:

- **`POST /domestic-payments`, `POST /sepa-payments`** — `Idempotency-Key` je kontraktem **vyžadován** a přeposílá se, aby retry aplikace přehrál a neduplikoval.
- **`POST /sca/challenges`** — `Idempotency-Key` je volitelný a přeposílá se, je-li přítomen.
- **`POST /onboarding/start`** — edge generuje čerstvý klíč na každé volání (každý pokus o onboarding je odlišná party); stabilní klíč od klienta je budoucí vylepšení.
- Prázdný/chybějící klíč na přeposílaném POSTu spadne na edge-generované UUID, aby byl upstream kontrakt vždy splněn.

## Přehled endpointů

Všechny cesty jsou pod `/customer/v1`. Scopy jsou OAuth scopy deklarované v `openapi.yaml`.

| Metoda & cesta | Scope | Poznámky |
|---|---|---|
| `GET /accounts` | `accounts:read` | vypsat účty volajícího |
| `GET /accounts/{accountId}` | `accounts:read` | 403 pokud není vlastník |
| `GET /balances/{accountId}` | `accounts:read` | 403 pokud není vlastník |
| `GET /transactions?accountId=&limit=&cursor=` | `accounts:read` | vlastnictví vynuceno; `cursor` URL-enkódovaný |
| `GET /statements/{accountId}` | `accounts:read` | seznam období uzávěrek |
| `GET /statements/{accountId}/{currency}/{legalSequence}?format=` | `accounts:read` | render camt.053 / MT940 / PDF; format & currency v allow-listu |
| `GET /notifications?limit=` | `accounts:read` | feed omezený na party |
| `GET /profile` | `accounts:read` | vlastní profil party volajícího |
| `POST /domestic-payments` | `payments:initiate` | obohaceno; `Idempotency-Key` vyžadován |
| `POST /sepa-payments` | `payments:initiate` | obohaceno; `Idempotency-Key` vyžadován |
| `POST /sca/parties/{partyId}/devices` | `sca:enroll-device` | 403 pokud partyId ≠ party z JWT |
| `POST /sca/challenges` | `sca:decide` | partyId injektován z JWT |
| `GET /sca/challenges/{id}` | `sca:decide` | stav challenge |
| `POST /sca/challenges/{id}/decision` | `sca:decide` | zaznamenat rozhodnutí zařízení |
| `POST /devices` | `accounts:read` | registrovat push token; partyId z JWT |
| `GET /devices` | `accounts:read` | vypsat zařízení (tokeny se nikdy nevrací) |
| `POST /onboarding/start` | *(anonymní)* | vytvořit party `PENDING_ACTIVATION` |
| `POST /onboarding/account` | `accounts:read` | otevřít první účet po KYC bráně |
| `GET /products/term-deposits` | `accounts:read` | zobrazit aktivní veřejné nabídky termínovaných vkladů |
| `GET /products/term-deposits/{productId}` | `accounts:read` | detail nabídky a podmínek |
| `POST /term-deposits` | `accounts:read` | založit účet termínovaného vkladu po KYC |

## Vybrané requesty

### Iniciace tuzemské platby (obohaceno)

Aplikace pošle lehké tělo; edge dohledá debtorův IBAN→BBAN + právní jméno a přepošle plnou instrukci.

```http
POST /customer/v1/domestic-payments
Authorization: Bearer <zákaznický JWT>
Idempotency-Key: 5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b
Content-Type: application/json

{
  "debtorAccountId": "11111111-1111-1111-1111-111111111111",
  "amount": "250.00",
  "currency": "CZK",
  "creditorAccountNumber": "2000145399/0800",
  "creditorName": "Jan Novák",
  "variableSymbol": "12345",
  "reference": "Faktura 2026-1"
}
```

Edge obohatí `debtorAccountNumber`/`debtorBankCode` (z českého IBANu účtu), `debtorName` (party-service), rozdělí kredit `number/bankcode`, namapuje `reference`→`messageForPayee`, nastaví `priority=STANDARD` a přepošle na `domestic-payment-service`. Peníze se nehýbou — iniciace jen vytvoří a proscreenuje; settlement je pod SCA.

### Začátek onboardingu (anonymně)

```http
POST /customer/v1/onboarding/start
Content-Type: application/json

{ "partyType": "INDIVIDUAL", "legalName": "Jana Nováková", "email": "jana@example.com" }
```

```http
201 Created
{ "partyId": "…", "status": "PENDING_ACTIVATION" }
```

### Otevření prvního účtu po KYC

`POST /onboarding/account` vyžaduje `ROLE_CUSTOMER`. Edge načte party z party-service a přepošle na account-service **jen pokud `status == ACTIVE`**, přičemž injektuje `partyId` z JWT.

### Termínované vklady

Aplikace nabídky načte přes `GET /products/term-deposits`; edge z operátorského katalogu propustí
jen produkty `ACTIVE`, veřejné a platné k dnešnímu dni. Nabídka obsahuje sazbu, pevnou délku,
limity vkladu, podmínky předčasného výběru a odkazy na podmínky. Pro založení se volá
`POST /term-deposits` pouze s `{ "productId": "…" }` a hlavičkou `Idempotency-Key`. Edge odvodí
`TERM_DEPOSIT` i měnu z nabídky, nikoli z klientského JSONu, a použije stejnou KYC bránu `ACTIVE`
i autoritativní jméno jako u založení účtu. Založení účtu je oddělené od jeho financování: aplikace
pak použije běžný tok financování účtu.

## Chybový model

Edge vrací malé JSON chybové obálky tvaru `{"error":"…"}`, které sám generuje, jinak **propouští status a tělo upstreamu beze změny**.

| HTTP | Kdy |
|---|---|
| 400 | vadné/neúplné tělo, špatná měna / debtorův IBAN / kredit účet, nepodporovaný formát výpisu |
| 401 | chybějící / neplatný zákaznický token (Quarkus OIDC challenge) |
| 403 | účet/debtor nevlastněný volajícím, nesoulad `partyId`, chybějící claim `party_id`/`sub` |
| 404 | cesta není v allow-listu; party nenalezena u `POST /onboarding/account` |
| 422 | `POST /onboarding/account` — KYC neschváleno (stav party ≠ `ACTIVE`) |
| 502 | selhání transportu upstreamu (`{"error":"upstream unavailable"}`) nebo party-service nevrátil id při onboardingu |
| *passthrough* | jakýkoli jiný status/tělo vrácené doslova z upstream služby |

## Události

**Žádné.** Edge je bezstavová proxy — nevlastní outbox a nepublikuje doménové události. Audit-relevantní události vydávají upstream služby, které volá (account, payment, sca, party).

## Verzování

- **Zákaznická verze API v URL** (`/customer/v1`). OpenAPI `info.version` (`1.6.0`) je osa API kontraktu (ADR-0048), nezávislá na release verzi `version.txt` (`0.9.0`).
- Upstream volání míří na vlastní `/api/v1` každé služby.
- **OpenAPI diff** v CI hlídá breaking změny bez bumpu kontraktu.
