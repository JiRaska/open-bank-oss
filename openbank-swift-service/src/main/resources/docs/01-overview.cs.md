# Přehled

## Co služba dělá

`openbank-swift-service` je **systém záznamu pro SWIFT MT zprávy** na platformě OpenBank. Drží jediný agregát:

- **SwiftMessage** — SWIFT MT/MX instrukce s BIC směrováním (`senderBic`, `receiverBic`), referencemi (`transactionReference` pole 20, `relatedReference` pole 21), `valueDate` (YYYYMMDD), `currency` + `amountMinorUnits`, ordering customer a beneficiary, `chargeCode` (OUR/SHA/BEN pole 71A), `priority` (NORMAL/URGENT/SYSTEM), surovým MT textem (`rawMt`) a životním cyklem `status`.

Podporované typy zpráv: **MT103** (jednotlivý zákaznický úhradový převod), **MT202** (obecný převod mezi finančními institucemi), **MT900** (potvrzení debetu), **MT910** (potvrzení kreditu), **MT940/MT950** (výpisy), **MT199** (volný formát).

Životní cyklus stavu je: `PENDING → VALIDATED → SENT → ACKNOWLEDGED | REJECTED`, s `FAILED` pro interní selhání zpracování. Při odeslání služba provede doménovou validaci (délka BIC 8..11, neprázdná transakční reference, kladná částka, platný charge code) a uloží zprávu jako `VALIDATED`.

## Co služba **NEDĚLÁ**

- ❌ Neiniciuje ani neautorizuje podkladovou platbu — to vlastní platební služby a SCA.
- ❌ Nevede podvojné účetnictví — to je `ledger-service`.
- ❌ Nepřepočítává ani nedrží zůstatky — `balance-service` / `account-service`.
- ❌ Neprovádí sankční/AML screening — `sanctions-service` / `aml-service` screenují výše v toku před uvolněním (viz reziduální rizika v threat modelu).
- ❌ Nevlastní fyzické připojení k síti SWIFT v tomto kódu — odeslání je modelováno přes outbox/event tok; síťová brána je externí entita.

## Pozice v doméně

```
   ┌────────────┐  POST /api/v1/swift   ┌──────────────────┐
   │ platby /   │ ───────────────────►  │  swift-service   │
   │ operátoři  │  ack / reject         │  (Quarkus)       │
   └────────────┘ ───────────────────►  └────────┬─────────┘
                                                  │ outbox → Kafka
                                                  ▼
   PostgreSQL (openbank_swift)        ┌────────────────────────────┐
   swift_messages / swift_outbox      │ transaction-service        │
                                      │ aml-service / audit-service│
                                      │ (downstream konzumenti)    │
                                      └────────────────────────────┘
```

## Klíčové use case

| Use case | API | Událost |
|---|---|---|
| Odeslat SWIFT zprávu k dispatchi | `POST /api/v1/swift` | (drénováno přes `swift_outbox` → `openbank.payments.swift.event`) |
| Získat zprávu podle id | `GET /api/v1/swift/{id}` | — |
| Vypsat zprávy podle stavu | `GET /api/v1/swift/status/{status}` | — |
| Vypsat všechny zprávy | `GET /api/v1/swift/messages` | — |
| Potvrdit zprávu (ACK od přijímající banky) | `POST /api/v1/swift/{id}/ack` | stav → `ACKNOWLEDGED` |
| Zamítnout zprávu | `POST /api/v1/swift/{id}/reject` | stav → `REJECTED` |

> Poznámka: use case send aktuálně persistuje agregát; explicitní zápis do `swift_outbox` ze send cesty zatím není v kódu use case zapojen — viz [02 — Architektura](./02-architecture.md). Outbox dispatcher a Kafka publisher jsou implementované a funkční.

## Volající

- **platební služby / operátoři** (přes Keycloak token) — odesílají, potvrzují, zamítají SWIFT zprávy.
- **SWIFT brána / protistrana** — příchozí ACK / reject (modelováno jako autentizovaná volání ack/reject endpointů; identita brány má být uzamčena přes mTLS allow-list dle threat modelu).
- **admin-ui** — čtecí pohledy, dotazy na stav pro platební cockpit.

## Závislosti

- **PostgreSQL** (databáze `openbank_swift`, logické schéma `swift_schema`)
- **Kafka** (kanál `swift-events-out`, topic `openbank.payments.swift.event`)
- **Redis (Valkey)** — klient zapojen pro caching/podporu idempotence
- **Keycloak** — OIDC auth (klient `openbank-services`)
- **OPA sidecar** — autorizační rozhodnutí (ADR-0034), výchozí advisory režim
- **openbank-libs** — `libs.authz` (`@Authorize`, `OpaSidecarPolicyDecisionPoint`), service-info/docs plumbing, build metadata

## Obchodní hodnota

- **Jediný zdroj pravdy** pro stav každé odchozí/příchozí SWIFT instrukce — žádné duplicitní wire záznamy napříč službami.
- **Auditovatelný životní cyklus** — každý create/ack/reject je samostatný, dohledatelný přechod stavu na povrchu citlivém na vysokohodnotový podvod.
- **Transakční outbox** — at-least-once propagace událostí downstream konzumentům (transaction, AML, audit) bez nekonzistence dvojího zápisu.
- **Připraveno na compliance** — money-path klasifikace s udržovaným threat modelem, OPA-gated akcemi a bezpečnostními hlavičkami vynucenými na edge.
