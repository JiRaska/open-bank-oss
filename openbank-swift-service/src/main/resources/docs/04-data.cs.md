# Data

## Datastore

- **Engine:** PostgreSQL 16, přístup přes Hibernate Reactive (Panache) + reaktivní PG klient.
- **Databáze:** `openbank_swift` (reaktivní URL `postgresql://localhost:5432/openbank_swift`).
- **Logický název schématu (deklarovaný):** `swift_schema` (dle `governance.yaml`). Flyway DDL je psáno s nekvalifikovanými názvy tabulek, takže tabulky fyzicky vznikají ve výchozím schématu databáze `openbank_swift`.
- **Generování schématu:** `none` — schéma je plně vlastněno Flyway migracemi; Hibernate nikdy negeneruje DDL. Naming strategy: `CamelCaseToUnderscoresNamingStrategy`.

## Flyway migrace

| Migrace | Účel |
|---|---|
| `V1__create_swift.sql` | Tabulka `swift_messages` + indexy na status / sender_bic / receiver_bic |
| `V2__create_swift_outbox.sql` | Tabulka `swift_outbox` + indexy na (status, created_at) a aggregate_id |
| `V3__hibernate_sequences.sql` | `swift_outbox_seq` (INCREMENT BY 50) — nutné, protože Panache alokuje id ze sekvence `<table>_seq`, zatímco DDL použilo `BIGSERIAL`; bez ní každý outbox INSERT za běhu selže |

`migrate-at-start: true`, `validate-on-migrate: false`, connect-retries 10 × 2s. **Nikdy nepřepisuj aplikovanou migraci** — neshoda checksumu shodí start (k zotavení použij `QUARKUS_FLYWAY_REPAIR_AT_START=true`, pak odstraň).

## Tabulka — `swift_messages`

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | `UUID` | PK |
| `idempotency_key` | `VARCHAR(255)` | `NOT NULL UNIQUE` — idempotentní dedup |
| `message_type` | `VARCHAR(10)` | MT103 / MT202 / MT900 / MT910 / MT940 / MT950 / MT199 |
| `sender_bic` | `VARCHAR(11)` | **blízké PII** (směrování instituce) |
| `receiver_bic` | `VARCHAR(11)` | směrování instituce |
| `transaction_reference` | `VARCHAR(16)` | SWIFT pole 20 |
| `related_reference` | `VARCHAR(16)` | pole 21, nullable |
| `value_date` | `CHAR(8)` | YYYYMMDD |
| `currency` | `CHAR(3)` | ISO 4217 |
| `amount_minor_units` | `BIGINT` | **finanční částka** |
| `ordering_customer_account` | `VARCHAR(34)` | **PII** — IBAN/účet, nullable |
| `ordering_customer_name` | `VARCHAR(140)` | **PII** — jméno fyzické osoby, nullable |
| `beneficiary_account` | `VARCHAR(34)` | **PII** — IBAN/účet |
| `beneficiary_name` | `VARCHAR(140)` | **PII** — jméno fyzické osoby |
| `remittance_info` | `VARCHAR(140)` | **PII-riziko** — volný text (pole 70), nullable |
| `charge_code` | `CHAR(3)` | OUR/SHA/BEN, výchozí `SHA` |
| `priority` | `VARCHAR(10)` | výchozí `NORMAL` |
| `status` | `VARCHAR(20)` | výchozí `PENDING` |
| `raw_mt` | `TEXT` | surový text SWIFT MT zprávy, nullable |
| `ack_received_at` | `TIMESTAMPTZ` | nullable |
| `rejection_reason` | `TEXT` | nullable |
| `created_at` | `TIMESTAMPTZ` | výchozí `NOW()` |
| `updated_at` | `TIMESTAMPTZ` | výchozí `NOW()` |

Indexy: `idx_swift_status(status)`, `idx_swift_sender(sender_bic)`, `idx_swift_receiver(receiver_bic)`.

## Tabulka — `swift_outbox`

Řádek transakčního outboxu (`SwiftOutboxEntity`, mapováno na `swift_outbox`):

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | `BIGSERIAL` | PK (id alokována přes `swift_outbox_seq`) |
| `event_id` | `UUID` | `NOT NULL UNIQUE` |
| `aggregate_id` | `UUID` | `swift_messages.id`, ke kterému událost patří |
| `event_type` | `VARCHAR(128)` | typový štítek doménové události |
| `payload` | `TEXT` | serializovaný payload události (publikováno doslovně do Kafky) |
| `status` | `VARCHAR(16)` | `PENDING` / `SENT` / `FAILED` (`SwiftOutboxStatus`) |
| `attempt_count` | `INTEGER` | výchozí 0; inkrementuje se při selhání |
| `sent_at` | `TIMESTAMPTZ` | nullable |
| `last_error` | `TEXT` | poslední chyba dispatche, nullable |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | výchozí `NOW()` |

Indexy: `idx_swift_outbox_status_created_at(status, created_at ASC)` (pořadí drénování), `idx_swift_outbox_aggregate_id(aggregate_id)`.

## PII pole

| Pole | Proč je to PII | Zacházení |
|---|---|---|
| `ordering_customer_name`, `beneficiary_name` | jména fyzických osob | confidential; minimalizovat v logu |
| `ordering_customer_account`, `beneficiary_account` | čísla IBAN/účtů | confidential; maskovat v logu |
| `remittance_info` | volný text může obsahovat osobní údaje | confidential |
| `raw_mt` | celá MT zpráva obsahuje vše výše | confidential at rest |

`dataClassification: confidential` (dle `governance.yaml`).

## Retence

`retentionPolicy: 10 years` (`governance.yaml`) — v souladu s uchováváním AML/platebních záznamů (AMLD/ČNB), které u dokončených wire instrukcí přebíjí GDPR výmaz. Viz [06 — Compliance](./06-compliance.md). V kódu této služby není implementována automatická úloha mazání (TBD — retence je deklarace politiky; vynucení je platformní/follow-up záležitost).

## Payloady událostí

Události jsou drénovány z `swift_outbox` do Kafka topicu `openbank.payments.swift.event` (kanál `swift-events-out`, String klíč + String hodnota). Sloupec `payload` drží serializovanou událost; `event_type` nese typový štítek. Každý přechod stavu zapisuje svůj outbox řádek ve STEJNÉ transakci jako změnu stavu (`SwiftRepository.saveWithOutbox`): verdikt schématu a settlement a — od #8718 — také operátorské potvrzení a zamítnutí, která dříve stav změnila a nepublikovala nic. `event_type` je vždy `swift.message.status-changed`, přechod se tedy čte z klíče `status` v payloadu; tvar payloadu je deklarován jako `SwiftMessageEventPayload` v [`docs/asyncapi/openbank-events.yaml`](../../../../../docs/asyncapi/openbank-events.yaml). Schémata událostí musí být verzována zpětně kompatibilně.
