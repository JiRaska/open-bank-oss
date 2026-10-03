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

Omezení a indexy:

- `card_authorizations_cleared_within_authorized` — `cleared ≤ amount` (opakuje `AuthorizationLifecycle.clear`).
- `card_authorizations_decline_reason_iff_declined`.
- UNIQUE `ux_card_authorizations_idempotency_key`; částečný UNIQUE `ux_card_authorizations_network_reference` (jen nenulové).
- `ix_card_authorizations_card_authorized_at (card_id, authorized_at DESC)` — počítání útraty.
- Částečný `ix_card_authorizations_expiring (expires_at)` pro stavy `APPROVED` nebo `PARTIALLY_CLEARED` — expirační sweep.

Blokovaná částka (`amount − cleared`, dokud hold trvá) se **odvozuje**, nikdy neukládá.

### `card_network_tokens` (V3)

Záznam banky o existenci síťového tokenu — zrcadlo odpovědi sítě, nikdy trezor. Žádný PAN, tokenový údaj ani kryptogram.

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | UUID PK | |
| `card_id` | UUID | indexováno (`ix_card_network_tokens_card`) |
| `token_reference` | VARCHAR(128) UNIQUE | neprůhledný identifikátor sítě |
| `requestor_id`, `requestor_label` | VARCHAR | wallet nebo obchodník, který žádal |
| `last4` | VARCHAR(4) | zobrazovací hodnota tokenu, ne karty |
| `status` | VARCHAR(16) | `ACTIVE` / `SUSPENDED` / `DELETED` |
| `scheme` | VARCHAR(16) | `VISA` / `MASTERCARD` / `SIMULATOR` — která vazba odpověděla |
| `expiry` | DATE | volitelné |
| `idempotency_key` | VARCHAR(128) UNIQUE | klíč vydání; pozdější úpravy ho zachovají |
| `provisioned_at`, `updated_at` | TIMESTAMPTZ | |

### `card_dispute_cases` (V3)

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | UUID PK | |
| `authorization_id` | UUID | **FK → `card_authorizations(id)`** |
| `card_id` | UUID | indexováno s `opened_at DESC` |
| `network_case_id` | VARCHAR(128) UNIQUE | přiděluje síť; nikdy null |
| `reason_code` | VARCHAR(32) | kód důvodu schématu, nepřeložený |
| `amount_minor_units`, `currency_code` | BIGINT, CHAR(3) | jak je síť potvrdila |
| `status` | VARCHAR(24) | `OPEN`, `EVIDENCE_SUBMITTED`, `WON`, `LOST`, `WITHDRAWN` |
| `scheme`, `scheme_status` | VARCHAR | vazba, která odpověděla; stav sítě doslovně |
| `respond_by_date` | DATE | volitelné |
| `evidence_reference` | VARCHAR(256) | poslední podaná reference dokumentu |
| `idempotency_key` | VARCHAR(128) UNIQUE | klíč otevření |
| `opened_at`, `updated_at` | TIMESTAMPTZ | |

`ux_card_dispute_live_per_authorization` — částečný UNIQUE na `authorization_id` pro stavy `OPEN` nebo `EVIDENCE_SUBMITTED`: nejvýše jeden živý případ na autorizaci, vynucený databází.

### `card_outbox`

Transakční outbox (ADR-0050): `event_id` (unikátní), `aggregate_id`, `event_type`, `payload`, `status` (výchozí `PENDING`), `attempt_count`, `last_error`, `created_at`, `updated_at`, `sent_at`, `claimed_at`, `synthetic` (V2). Hibernate používá sekvenci `card_outbox_seq` (increment 50), explicitně vytvořenou ve V1.

## Migrace

| Verze | Obsah | Rollback |
|---|---|---|
| V1 `init_card_processing` | obě tabulky, indexy, `card_outbox_seq` | `DROP TABLE card_outbox; DROP TABLE card_authorizations;` — bezpečné jen před první autorizací |
| V2 `synthetic_outbox_taint` | `card_outbox.synthetic BOOLEAN NOT NULL DEFAULT FALSE` (ADR-0252) | `ALTER TABLE card_outbox DROP COLUMN synthetic;` — bezpečné jen před odesláním syntetického provozu |
| V3 `token_and_dispute_lifecycle` | `card_network_tokens`, `card_dispute_cases`, jejich indexy | `DROP TABLE card_dispute_cases; DROP TABLE card_network_tokens;` — obě jsou nové a nic na ně neodkazuje; drop ztratí jen řádky zapsané od V3 |

`migrate-at-start: true`; `validate-on-migrate: false`.

## Osobní a citlivá data

- **Žádná data držitele karty** ve smyslu PCI: žádný PAN, CVV, expirace ani jméno držitele.
- `party_id` / `account_id` vedou přes jiné služby k fyzické osobě; název a země obchodníka popisují, kde zákazník utrácel. Zachází se s nimi jako s důvěrnými finančními daty.
- Řádky tokenů spojují kartu s requestorem (wallet nebo obchodník); řádky reklamací spojují transakci s důvodem chargebacku. Obojí je důvěrné a ani jedno neukládá kartový ani tokenový údaj.
- Události nesou stejná pole jako řádek kromě `idempotency_key`; žádný kartový údaj se nikdy neemituje.
