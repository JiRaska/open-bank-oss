# Data

Databáze: `openbank_card_processing` (PostgreSQL), vlastněná výhradně touto službou (`governance.yaml`). Datová doména `payments`, klasifikace `confidential`, retence **7 let** (účetní záznamy, ADR-0118).

## Tabulky

### `card_authorizations`

Jeden řádek na autorizaci — schválenou i zamítnutou. Je to zároveň hold.

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | UUID PK | UUIDv7 (ADR-0106) |
| `card_id`, `account_id`, `party_id` | UUID | reference karty z card-issuance — **žádný PAN** |
| `amount_minor_units` | BIGINT | `> 0` |
| `currency_code` | VARCHAR(3) | |
| `channel` | VARCHAR(16) | `CONTACTLESS` / `ONLINE` / `ATM` / `CHIP_AND_PIN` |
| `mcc`, `merchant_name`, `merchant_country` | | volitelné |
| `status` | VARCHAR(20) | `APPROVED`, `DECLINED`, `PARTIALLY_CLEARED`, `CLEARED`, `REVERSED`, `EXPIRED` |
| `category` | VARCHAR(40) | kategorie MCC podle card-issuance, uchovaná, aby ji clearing znovu neposuzoval |
| `decline_reason` | VARCHAR(40) | vyplněno právě když `DECLINED` |
| `cleared_amount_minor_units` | BIGINT | kumulativní prezentovaná částka |
| `network_reference` | VARCHAR(64) | reference acquirera |
| `idempotency_key` | VARCHAR(128) | zapsán jednou při insertu |
| `authorized_at`, `expires_at`, `updated_at` | TIMESTAMPTZ | |
| `version` | BIGINT | optimistický zámek (V4, Hibernate `@Version`) — serializuje každý souběžný zápis (clearing, reverzace, expirace) |

Omezení a indexy:

- `card_authorizations_cleared_within_authorized` — `cleared ≤ amount` (opakuje `AuthorizationLifecycle.clear`).
- `card_authorizations_decline_reason_iff_declined`.
- UNIQUE `ux_card_authorizations_idempotency_key`; částečný UNIQUE `ux_card_authorizations_network_reference` (jen nenulové).
- `ix_card_authorizations_card_authorized_at (card_id, authorized_at DESC)` — počítání útraty.
- Částečný `ix_card_authorizations_expiring (expires_at)` pro stavy `APPROVED` nebo `PARTIALLY_CLEARED` — expirační sweep.

Blokovaná částka (`amount − cleared`, dokud hold trvá) se **odvozuje**, nikdy neukládá.

### `card_clearings`

Jeden řádek na každou **započtenou** prezentaci clearingu (V4) — idempotenční záznam jejího klíče. Pouze insert.

| Sloupec | Typ | Poznámka |
|---|---|---|
| `id` | UUID PK | UUIDv7 |
| `authorization_id` | UUID FK → `card_authorizations.id` | |
| `idempotency_key` | VARCHAR(128) | klíč clearingu od acquirera |
| `request_fingerprint` | VARCHAR(64) | libs `RequestFingerprint` (SHA-256 hex) cesty, částky a měny velkými písmeny |
| `amount_minor_units` | BIGINT | `> 0` |
| `currency_code` | VARCHAR(3) | |
| `applied_at` | TIMESTAMPTZ | |

UNIQUE `ux_card_clearings_authorization_key (authorization_id, idempotency_key)` — záruka, že se jeden klíč clearingu započte jednou i při souběžných duplikátech.

### `card_outbox`

Transakční outbox (ADR-0050): `event_id` (unikátní), `aggregate_id`, `event_type`, `payload`, `status` (výchozí `PENDING`), `attempt_count`, `last_error`, `created_at`, `updated_at`, `sent_at`, `claimed_at`, `synthetic` (V2). Hibernate používá sekvenci `card_outbox_seq` (increment 50), explicitně vytvořenou ve V1.

## Migrace

| Verze | Obsah | Rollback |
|---|---|---|
| V1 `init_card_processing` | obě tabulky, indexy, `card_outbox_seq` | `DROP TABLE card_outbox; DROP TABLE card_authorizations;` — bezpečné jen před první autorizací |
| V2 `synthetic_outbox_taint` | `card_outbox.synthetic BOOLEAN NOT NULL DEFAULT FALSE` (ADR-0252) | `ALTER TABLE card_outbox DROP COLUMN synthetic;` — bezpečné jen před odesláním syntetického provozu |
| V4 `card_clearing_idempotency` | `card_clearings` + UNIQUE `(authorization_id, idempotency_key)`; `card_authorizations.version` | `DROP TABLE card_clearings; ALTER TABLE card_authorizations DROP COLUMN version;` — pro schéma bezpečné, ale znovu otevře dvojí prezentaci a zahodí záznam započtených klíčů |

`migrate-at-start: true`; `validate-on-migrate: false`.

## Osobní a citlivá data

- **Žádná data držitele karty** ve smyslu PCI: žádný PAN, CVV, expirace ani jméno držitele.
- `party_id` / `account_id` vedou přes jiné služby k fyzické osobě; název a země obchodníka popisují, kde zákazník utrácel. Zachází se s nimi jako s důvěrnými finančními daty.
- Události nesou stejná pole jako řádek kromě `idempotency_key`; žádný kartový údaj se nikdy neemituje.
