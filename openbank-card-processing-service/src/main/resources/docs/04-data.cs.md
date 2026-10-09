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
| `idempotency_key` | VARCHAR(160) UNIQUE | klíč vydání (`adopted:<token_reference>` u tokenu převzatého ze čtení sítě); pozdější úpravy ho zachovají. Jen pojistka — idempotenci zajišťuje rezervace v `card_lifecycle_idempotency` |
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
| `evidence_reference` | VARCHAR(256) | POSLEDNÍ podaná reference dokumentu; celá historie je `card_dispute_evidence` |
| `idempotency_key` | VARCHAR(128) UNIQUE | klíč otevření |
| `opened_at`, `updated_at` | TIMESTAMPTZ | |

`ux_card_dispute_live_per_authorization` — částečný UNIQUE na `authorization_id` pro stavy `OPEN` nebo `EVIDENCE_SUBMITTED`: nejvýše jeden živý případ na autorizaci, vynucený databází.

### `card_dispute_evidence` (V3)

Append-only historie podání důkazů — jeden řádek na podání, nikdy se neaktualizuje. `card_dispute_cases.evidence_reference` je jen ten poslední.

| Sloupec | Typ | Poznámka |
|---|---|---|
| `id` | UUID PK | |
| `dispute_id` | UUID | **FK → `card_dispute_cases(id)`**, indexováno se `submitted_at` |
| `document_reference` | VARCHAR(256) | odkaz, nikdy dokument |
| `note` | VARCHAR(2000) | volitelné |
| `scheme_status` | VARCHAR(64) | stav sítě v odpovědi na toto podání, doslovně |
| `submitted_at` | TIMESTAMPTZ | |

### `card_lifecycle_idempotency` (V3)

Idempotenční rezervace pro volání, která jdou do karetní sítě (vydání tokenu, otevření reklamace, podání důkazu). Vkládá se (`ON CONFLICT DO NOTHING`) **před** voláním sítě; dokončuje se ve stejné transakci jako řádek výsledku.

| Sloupec | Typ | Poznámka |
|---|---|---|
| `reservation_key` | VARCHAR(160) PK | `<operace>:<Idempotency-Key>` |
| `operation` | VARCHAR(32) | `TOKEN_PROVISION`, `DISPUTE_OPEN`, `DISPUTE_EVIDENCE` |
| `fingerprint` | CHAR(64) | SHA-256 požadavku, ke kterému je klíč vázán |
| `state` | VARCHAR(16) | `PENDING` nebo `COMPLETED` (CHECK) |
| `result_id` | UUID | řádek tokenu, případu nebo důkazu; vyplněn právě když `COMPLETED` |
| `created_at`, `updated_at` | TIMESTAMPTZ | |

Řádek `PENDING` nikdy nevyprší: požadavek, který spadl poté, co síť jednala, se nesmí zopakovat do druhé akce. `ix_card_lifecycle_idempotency_pending` (částečný, `state = 'PENDING'`) je dotaz operátora na zaseknuté klíče.

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
| V3 `token_and_dispute_lifecycle` | `card_network_tokens`, `card_dispute_cases`, `card_dispute_evidence`, `card_lifecycle_idempotency`, jejich indexy | `DROP TABLE card_dispute_evidence; DROP TABLE card_lifecycle_idempotency; DROP TABLE card_dispute_cases; DROP TABLE card_network_tokens;` — všechny jsou nové a nic mimo V3 na ně neodkazuje; drop ztratí jen řádky zapsané od V3 |
| V4 `card_clearing_idempotency` | `card_clearings` + UNIQUE `(authorization_id, idempotency_key)`; `card_authorizations.version` | `DROP TABLE card_clearings; ALTER TABLE card_authorizations DROP COLUMN version;` — pro schéma bezpečné, ale znovu otevře dvojí prezentaci a zahodí záznam započtených klíčů |

`migrate-at-start: true`; `validate-on-migrate: false`.

## Osobní a citlivá data

- **Žádná data držitele karty** ve smyslu PCI: žádný PAN, CVV, expirace ani jméno držitele.
- `party_id` / `account_id` vedou přes jiné služby k fyzické osobě; název a země obchodníka popisují, kde zákazník utrácel. Zachází se s nimi jako s důvěrnými finančními daty.
- Řádky tokenů spojují kartu s requestorem (wallet nebo obchodník); řádky reklamací spojují transakci s důvodem chargebacku. Obojí je důvěrné a ani jedno neukládá kartový ani tokenový údaj.
- Události nesou stejná pole jako řádek kromě `idempotency_key`; žádný kartový údaj se nikdy neemituje.

## Retence outboxu (řádky SENT)

`card_outbox` je doručovací buffer, ne záznam. Řádky, které už dorazily do brokeru (`status = 'SENT'`), se mažou, jakmile je jejich `sent_at` starší než `openbank.outbox.retention.sent-days` (výchozí **7**), sdíleným jobem `OutboxSentRetentionJob` z libs-runtime (ADR-0329, ADR-0327 D8). Repozitář se zapojuje delegací `SentOutboxRetention` na `PanacheOutboxRetention`.

- Běží každou noc (`openbank.outbox.retention.cron`, výchozí `0 17 3 * * ?`) na každé replice; každé mazání je omezené (`batch-size` 5 000, nejvýš `max-batches` 200 za běh), takže dlouho nečištěná tabulka se vyprázdní během několika nocí.
- Řádků PENDING, FAILED, DISPATCHING a DEAD se nikdy nedotkne — DEAD řádky jsou DLQ na straně producenta.
- Replay události starší než okno jde z Kafka topicu nebo z audit-service, ne z této tabulky.
- Signály: `openbank_outbox_purged_total{service,status="SENT"}`, `openbank_outbox_purge_failed_total` a workflow liveness `outbox-sent-retention`.
- Vypnutí: `openbank.outbox.retention.enabled=false` (při startu zaloguje WARN). Nesnižujte `sent-days` pod potřebu kteréhokoli čtenáře SENT řádků.
