# openbank-wealth-service — Dokumentace

> **Co to je:** vlastník aktiv a závazků mimo banku, které deklaruje sám zákazník. Patří sem nemovitosti, sbírky, cenné papíry jinde, podíly ve firmách, soukromé půjčky a podobně ([ADR 0301](../../../../docs/adr/0301-wealth-service-declared-holdings-and-net-worth-composition.md)). **Co to NENÍ:** zdroj bankovních pozic. Nehýbe penězi a deklarovaná částka nikdy není zůstatek.

Dokumentaci publikuje služba na management endpointu `/q/openbank/docs` (Docs-as-Service, [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)).

## TL;DR

- **Stack:** Kotlin / Quarkus 3.x / Hibernate Reactive Panache / PostgreSQL (`wealth-db`, CNPG)
- **Porty:** 8154 (aplikace), 8090 (management)
- **Události:** transakční outbox (ADR-0003) do `openbank.wealth.events`; syntetický provoz je v outboxu označený (ADR-0252)
- **Autentizace:** jen M2M, `ROLE_API`, `ROLE_OPERATOR` nebo `ROLE_ADMIN`. Vlastník přichází v hlavičce `X-Customer-Party-Id`.
- **Hranice důvěry:** routy podle id pracují jen s id aktiva a vlastníka NEOVĚŘUJÍ. Zákazník se ke službě dostane jen přes customer-edge, která vlastnictví ověří před každým voláním podle id.

## API

| Metoda a cesta | Co dělá |
|---|---|
| `POST /api/v1/holdings` | Deklaruje aktivum pro party z `X-Customer-Party-Id` |
| `GET /api/v1/holdings` | Vypíše aktivní a zastavená aktiva této party |
| `GET /api/v1/holdings/{id}` | Načte jedno aktivum |
| `PUT /api/v1/holdings/{id}/valuation` | Přecení ho; předchozí hodnota zůstává |
| `GET /api/v1/holdings/{id}/valuations` | Všechny kdy uvedené hodnoty, nejnovější první |
| `DELETE /api/v1/holdings/{id}` | Stáhne ho; 409, dokud je zastavené jako zajištění úvěru |

Každé aktivum nese `valuationSource` (`CUSTOMER_DECLARED`, `EXPERT_APPRAISAL`, `MARKET_REFERENCE`) a `valuationAgeDays`. Částku zadanou zákazníkem nesmí nikdo zaměnit za ověřenou a roky staré ocenění nesmí vypadat čerstvě.

Historie ocenění je jen pro přidávání (`declared_holding_valuations`). Je to jediný záznam v této službě, který po ztrátě nejde obnovit.

## Volající a kontrakty

Jediným volajícím je **customer-edge**. Výpis používá pro složení `GET /customer/v1/net-worth` a volá všech šest rout za `/customer/v1/holdings`.

Tento vztah je Pact kontrakt (`pacts/openbank-customer-edge-openbank-wealth-service.json`), který přehrávají tři providerské třídy:

- `WealthPactFolderProviderVerificationTest` ho přehrává na každém PR proti skutečnému Postgresu.
- `WealthNegativeAuthProviderVerificationTest` dokazuje, že volající bez identity dostane 401.
- `WealthPactBrokerProviderVerificationTest` publikuje ověření při pushi do main, aby `can-i-deploy` uměl odpovědět o customer-edge.
