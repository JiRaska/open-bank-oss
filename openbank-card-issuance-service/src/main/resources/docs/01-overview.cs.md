# Přehled

## Co služba dělá

`openbank-card-issuance-service` je **systém záznamu pro karty** v platformě OpenBank. Drží:

- **Agregát Card** — `id` karty, vlastnící `partyId` a `accountId`, `productCode`, `cardType` (DEBIT / CREDIT / PREPAID / VIRTUAL), `network` (VISA / MASTERCARD / AMEX / UNIONPAY), **maskovaný PAN** (pouze poslední 4 číslice), jméno držitele a embosované jméno, datum expirace, stav, per-kartové limity útraty (denní / měsíční v minor units), měnu a volitelnou doručovací adresu.
- **Stavový automat životního cyklu** — karta s plastem se vydává ve stavu `PENDING` a aktivuje se, až ji klient obdrží; karta `VIRTUAL` / `SINGLE_USE` nemá co obdržet a vydává se rovnou jako `ACTIVE`. Dále `PENDING → ACTIVE` (activate), `ACTIVE → SUSPENDED` (suspend) / `SUSPENDED → ACTIVE` (resume), `{ACTIVE, SUSPENDED} → BLOCKED` (trvalá blokace, vyžaduje důvod). `EXPIRED` a `CANCELLED` jsou v modelu terminální stavy.
- **Doménové události** — `CardIssued` a `CardStatusChanged`, emitované při každé změně stavu a publikované přes transakční outbox.

## Co služba **NEDĚLÁ**

- ❌ Neautorizuje platby kartou na POS/ATM — žádná komponenta autorizace/switch zde není.
- ❌ Neukládá celý PAN, CVV/CVC ani PIN — persistuje pouze maskovaný PAN (`**** **** **** 1234`) (minimalizace PCI DSS scope).
- ❌ Neprovádí personalizaci/embosování ani fyzickou výrobu plastu — to je záležitost dodavatele karet downstream od události `CardIssued`.
- ❌ Neotevírá účty ani nedrží zůstatky — to dělá `account-service` / `balance-service`.
- ❌ Neprovádí KYC/AML — to dělá `kyc-service` / `aml-service`; karta se vydává proti již onboardovanému klientovi.

## Pozice v doméně

```
   ┌────────────┐  POST /api/v1/cards   ┌──────────────────────┐
   │  admin UI  │ ───────────────────►  │ card-issuance-service│
   └────────────┘                       └──────────┬───────────┘
                                                    │
   ┌────────────────┐  block (dispute)             │ outbox → Kafka
   │ dispute-service│ ───────────────────────────► │ (openbank.cards.events)
   └────────────────┘                              │
                                                    ▼
                                       ┌────────────────────────┐
                                       │ PostgreSQL              │
                                       │ (db: openbank_cards)    │
                                       └────────────────────────┘
                                                    │
                                    card.issued.v1 / card.status_changed.v1
                                                    ▼
                                       ┌────────────────────────┐
                                       │ audit / notification /  │
                                       │ downstream konzumenti   │
                                       └────────────────────────┘
```

## Klíčové use case

| Use case | API | Událost |
|---|---|---|
| Vydání nové karty pro klienta + účet | `POST /api/v1/cards` | `card.issued.v1` |
| Aktivace čekající karty | `POST /api/v1/cards/{id}/activate` | `card.status_changed.v1` |
| Dočasné pozastavení karty | `POST /api/v1/cards/{id}/suspend` | `card.status_changed.v1` |
| Obnovení pozastavené karty | `POST /api/v1/cards/{id}/resume` | `card.status_changed.v1` |
| Trvalá blokace karty (ztráta/krádež/dispute) | `POST /api/v1/cards/{id}/block` | `card.status_changed.v1` |
| Získání karty podle id | `GET /api/v1/cards/{id}` | — |
| Seznam karet pro účet | `GET /api/v1/cards/account/{accountId}` | — |
| Seznam karet pro klienta | `GET /api/v1/cards/party/{partyId}` | — |
| Seznam všech karet | `GET /api/v1/cards` | — |

## Volající

- **admin-ui** (přes Keycloak token) — operátoři vydávají, aktivují a spravují karty; compliance může blokovat.
- **dispute-service** — spouští blokaci, když fraud/chargeback dispute vyžaduje (deklarováno jako upstream v `governance.yaml`).
- Downstream **konzumenti událostí** (audit, notification a případná integrace dodavatele karet) — read-only, přes Kafku.

## Závislosti

- **PostgreSQL** (databáze `openbank_cards`, schéma `cards_schema` dle `governance.yaml`)
- **Kafka** (topic `openbank.cards.events`)
- **Redis (Valkey)** — přítomný ve stacku (Redis klient nakonfigurován); idempotence vydání je vynucena na úrovni DB přes unikátní sloupec `idempotency_key`.
- **Keycloak** — OIDC autentizace
- **openbank-libs** — sdílená runtime infrastruktura (BuildInfo, ServiceInfoResource, DocsResource, outbox helpery)

## Obchodní hodnota

- **Jediný zdroj pravdy** o existenci a stavu každé karty — žádné duplicitní seznamy karet napříč službami.
- **Minimalizace PCI scope** — ukládáním pouze maskovaného PANu a nikdy CVV/PINu zůstává služba mimo nákladné prostředí cardholder-data (PCI DSS).
- **Auditovatelný životní cyklus** — každý přechod emituje doménovou událost nesoucí aktéra (`changedBy`) a důvod, persistovanou downstream po zákonnou dobu retence.
- **Propagace v reálném čase** přes outbox + Kafku — downstream systémy (notifikace, dodavatel karet, audit) získají eventuálně konzistentní pohled během sekund.
