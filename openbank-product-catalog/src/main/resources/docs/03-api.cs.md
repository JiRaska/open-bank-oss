# API & kontrakty

## Base path

- **In-cluster base:** `http://openbank-product-catalog:8104/api/v1` a `/api/v2`
- **Lokální dev:** `http://localhost:8104/api/v1` a `/api/v2`
- **OpenAPI spec:** [`/q/openapi`](http://localhost:8085/q/openapi) na management portu (zdroj pravdy: `openapi.yaml`, `info.version` 2.0.0, OpenAPI 3.1.0)
- **Swagger UI:** `http://localhost:8085/api/docs` (nastaveno přes `quarkus.swagger-ui.path`, `always-include: true`)

Major nejnovějšího kontraktu z `openapi.yaml:info.version` se rovná `openbank.api.version` a URL `/api/v2` ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)). Stejný dokument zachovává kompatibilní `/api/v1`; response filtr odvozuje `X-API-Version` ze skutečné cesty. Release verze (`version.txt`) je samostatná osa.

OpenAPI Generator 7.24.0 při kompilaci vytváří coroutine JAX-RS resource rozhraní pro v2 a runtime
resources je přímo implementují. Product Studio před buildem, type-checkem a kontraktačními testy
generuje operation-typed TypeScript klienta ze stejného kontraktu.

## Autentizace

Služba je OIDC resource server. Čtení vyžaduje autentizovaný bearer token. OpenBank deployment může
používat `ROLE_OPERATOR` / `ROLE_ADMIN`; provider-neutral instalace mapuje konfigurovatelné OAuth
scopes (výchozí `catalog:read`, `catalog:author`, `catalog:publish`) na stejné core oprávnění.
Maker != checker vždy vynucuje samotná služba, OPA je defense in depth. Prohlížeč stále používá
autentizovaný BFF (ADR-0056).

CORS omezuje origins na nakonfigurované admin UI, zpřístupňuje `ETag` a povoluje `If-Match` pro optimistické zápisy.

## Idempotence

Mechanismus `Idempotency-Key` není implementován. Create chrání unikátní `code`. Čtení jednoho produktu a mutace vracejí číselný `ETag`; jeho odeslání přes `If-Match` (nebo `revision` v legacy body) odmítne zastaralého zapisujícího pomocí `409 Conflict`. Ve v1 je precondition kvůli kompatibilitě volitelná, takže neversionovaný legacy read-modify-write chráněn není; v2 ji vyžaduje.

## Endpointy

### Produkty

| Metoda | Cesta | Účel | Úspěch | Chyby |
|---|---|---|---|---|
| GET | `/api/v1/products` | Výpis produktů; volitelné `?type=&status=&currency=` | 200 pole | — |
| GET | `/api/v1/products/{id}` | Detail produktu | 200 | 404 |
| POST | `/api/v1/products` | Vytvoření produktu (`ProductRequest`) | 201 + ETag | 400 validace, 409 duplicitní code |
| PUT | `/api/v1/products/{id}` | Úprava produktu (`ProductRequest`, volitelné `If-Match`) | 200 + ETag | 400, 404, 409 stará revize |
| POST | `/api/v1/products/{id}/activate` | Legální přechod do ACTIVE | 200 + ETag | 404, 409, 422 |
| POST | `/api/v1/products/{id}/deactivate` | Legální přechod do INACTIVE | 200 + ETag | 404, 409, 422 |
| GET | `/api/v1/products/{id}/fees` | Poplatky jednoho produktu | 200 pole | 404 |

### Poplatky

| Metoda | Cesta | Účel | Úspěch |
|---|---|---|---|
| GET | `/api/v1/fees` | Celobankovní zploštělý sazebník; volitelné `?type=&currency=&productCode=` | 200 pole |

### Generický katalog v2

V2 odděluje důvěryhodné typy/schémata, kanonické specifikace, tržní nabídky, autorské revize a publikované čtení. Základní povrch tvoří `/api/v2/product-types`, `/api/v2/specifications`, `/api/v2/offerings`, `/api/v2/offerings/{id}/revisions`, `/api/v2/products/{offeringId}` a trvalý kurzor `/api/v2/events`. Kolekce specifikací, nabídek a revizí lze vypsat pro Product Studio. Čtení je záměrně deterministické pro jednu nabídku; výběr podle trhu vyžaduje explicitní pravidla specificity. Oborová data jsou pouze v `attributes` a vždy odkazují přesnou dvojici typu a verze schématu.

Mutace existující revize a publikace vyžadují silný `If-Match`: chybějící podmínka vrací `428 Precondition Required`, zastaralá `412 Precondition Failed`. Publikovat musí jiný člověk než autor draftu; identita autora i schvalovatele pochází z ověřeného JWT, nikdy z request body.

### Příklad — vytvoření produktu

```http
POST /api/v1/products
Content-Type: application/json

{
  "code": "SAVINGS_FLEX",
  "name": "Flexible Savings",
  "type": "SAVINGS",
  "currency": "EUR",
  "status": "DRAFT",
  "baseRate": 0.03,
  "fees": [
    { "name": "Account Maintenance", "type": "MONTHLY", "amount": 0.0,
      "currency": "EUR", "frequency": "MONTHLY", "waivable": false }
  ]
}
```

```http
201 Created
Content-Type: application/json
ETag: "0"

{
  "id": "5e9f8b6a-7c3d-4e1f-9a2b-1c8f7e6d5a4b",
  "code": "SAVINGS_FLEX",
  "name": "Flexible Savings",
  "type": "SAVINGS",
  "currency": "EUR",
  "status": "DRAFT",
  "revision": 0,
  "fees": [ … ],
  "updatedAt": "2026-06-09T10:00:00Z"
}
```

Na `ProductRequest` jsou povinné jen `code`, `name`, `type`, `currency`; bohaté konfigurační bloky (`cardConfig`, `multiCurrencyConfig`, `overdraftConfig`, `termDepositConfig`, `savingsConfig`) jsou volitelné. Při create neuvedený `status` defaultuje na `DRAFT` a `isPublic` na `true`.

### Příklad — položka sazebníku

```http
GET /api/v1/fees?productCode=CURRENT_PERSONAL
```

```json
[
  {
    "id": "<canonical-product-uuid>:<feeId>",
    "code": "CURRENT_PERSONAL_FX_CONVERSION",
    "name": "FX Conversion",
    "type": "TRANSACTION",
    "amount": 1.5,
    "currency": "EUR",
    "frequency": "PERCENTAGE",
    "productId": "<canonical-product-uuid>",
    "productCode": "CURRENT_PERSONAL",
    "productName": "Personal Current Account",
    "status": "ACTIVE",
    "updatedAt": "2024-11-01T09:00:00Z"
  }
]
```

## Chybový model

Legacy 404 odpovědi zachovávají `{ "error": "<zpráva>" }` kvůli existujícímu Paktu. Validační, lifecycle a konfliktní chyby používají fleet obálku `ApiError` s `traceId`, `status`, `code`, `message` a `timestamp`.

## Verzování

- Verzování v URL: kompatibilní `/api/v1`, nejnovější `/api/v2`.
- Verze OpenAPI kontraktu (`openapi.yaml:info.version`) je osa API kontraktu; jakákoli breaking změna ji musí zvednout dle klasifikace `oasdiff` a aktualizovat kontrakt + kontraktní test ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).

## Publikovaná revize původního produktu (ADR-0341)

`GET /api/v2/legacy-products/{productId}/published-revision` určí publikovanou revizi katalogu pro bankovní produkt v1 podle jeho v1 identifikátoru, pro volající, kteří produkt znají jen podle `productId` účtu. Volitelný parametr `effectiveAt` určuje okamžik; bez něj se vrátí revize platná nyní. Volající si připne vrácené `id` revize a pozdější čtení používají připnutí, ne toto vyhledání. Pokud produkt neexistuje nebo žádná jeho revize ještě není publikovaná, vrací 404. Vyžaduje roli pro čtení katalogu. Verze kontraktu API je 2.5.0; změna je aditivní.

## Produktový IR a podepsaná publikace (ADR-0352)

Obsah revize může nést `behaviour`: spustitelné chování produktu jako dokument Product IR v1
(úrok, bonusový predikát nad historií událostí účtu, poplatky s výjimkami, splatnost termínovaného
vkladu a sankce za předčasný výběr). IR validuje při každém zápisu draftu sdílený parser z
`openbank-libs-domain`; neplatný dokument se nikdy nestane draftem (400) a `behaviour` je pokryto
`contentHash` i `approvalDigest` stejně jako zbytek obsahu.

Publikace revize s IR je samostatná akce čtyř očí, `catalog.publish-ir`:
`POST /api/v2/offerings/{offeringId}/revisions/{revisionId}/publish-ir` IR zvaliduje, spočítá
jeho kanonický `revisionHash`, získá podpis publikační cesty nad obálkou identity revize a uloží
oddělený podpis k revizi ve stejné transakci. Obálka je kanonický JSON polí `productCode`,
`revisionId`, `revisionNumber`, `irVersion` a `revisionHash` s doménovým štítkem
`openbank.product-revision-signature.v1`, takže podpis jednoho produktu nebo jedné revize nikdy
neprojde pod připnutím jiné, ani když dvě revize nesou shodné IR. Čtení jedné revize (`GET /api/v2/revisions/{id}`,
`GET /api/v2/products/{offeringId}`) ho vrací jako `irSignature` (`revisionHash`, `keyId`,
`algorithm`, `signature`, `signedAt`), stejně jako každý seznam revizí; `null` znamená nepodepsáno. Běžný `publish` revizi s `behaviour`
odmítne (409) a `publish-ir` odmítne revizi bez něj, takže revize s IR nikdy není publikována bez
podpisu. Autor a schvalovatel se musí lišit jako u každé publikace.

Podepisovatel se volí přes `openbank.catalog.ir-signing.mode`: `disabled` (výchozí; `publish-ir`
vrací 409 a nic se nepodepisuje), `openbao` (OpenBao Transit `sign` na vyhrazeném klíči; jediná
nasazovaná podoba) nebo `hmac` (adaptér se sdíleným tajemstvím přijímaný jen v režimech dev a
test; produkční start s ním odmítne nastartovat). `publish-ir` smí volat jen ověřený člověk;
služba to kontroluje sama, nezávisle na rozhodnutí OPA. Konzument podpis ověřuje vlastním loaderem
připnuté revize před vyhodnocením: připíná všech pět polí obálky a jakýkoli nesoulad, chybějící
kopie nebo neplatný podpis selže uzavřeně (žádné vyhodnocení, žádné účtování), každý jako vlastní
`outcome` metriky `openbank_product_revision_load_total` (`identity_mismatch` pro podvrženou
revizi); katalog je publikační autorita, nikdy závislost na účtovací
cestě. Verze kontraktu API je 2.9.0; změna je aditivní.
