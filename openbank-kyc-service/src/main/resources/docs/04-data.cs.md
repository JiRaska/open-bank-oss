# Data

## Datastore

PostgreSQL 16, databáze **`openbank_kyc`** (reaktivní PG klient + JDBC pro Flyway). `hibernate-orm.database.generation = none` — schéma vlastní výhradně Flyway, který migruje při startu (`migrate-at-start: true`).

> Migrační SQL cílí na schéma **`public`** (výchozí search path). Per-service governance manifest deklaruje logický název schématu `kyc_schema`; berte `kyc_schema` jako logický/governance název a `public` v `openbank_kyc` jako fyzické umístění.

## Tabulky

### `kyc_cases` (V1, rozšířeno V2)

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | BIGSERIAL PK | interní id; sekvence `kyc_cases_seq` (V4) |
| `case_id` | UUID UNIQUE | business id (agregát `KycCase.id`) |
| `party_id` | UUID | prověřovaná party — viz PII níže |
| `status` | VARCHAR(30) | OPEN / DOCUMENTS_REQUIRED / UNDER_REVIEW / APPROVED / REJECTED / EXPIRED — DOCUMENTS_REQUIRED nedosažitelný (#8535) |
| `risk_level` | VARCHAR(20) | LOW / MEDIUM / HIGH / VERY_HIGH |
| `assigned_to` | VARCHAR(100) | přiřazení revizora |
| `checks_json` | TEXT | serializovaný seznam `KycCheck` |
| `notes` | TEXT | revizor / důvod zamítnutí |
| `reviewed_by`, `reviewed_at` | VARCHAR(100), TIMESTAMPTZ | metadata rozhodnutí čtyř očí |
| `expires_at` | TIMESTAMPTZ | 30 dní od otevření |
| `created_at`, `updated_at` | TIMESTAMPTZ | |
| **Compliance pole V2** | | |
| `due_diligence_level` | VARCHAR(10) | SDD / CDD / EDD (CHECK constraint) — EBA AML |
| `source_of_funds`, `source_of_wealth` | VARCHAR(100) | deklarace FATF R.10 |
| `business_purpose` | VARCHAR(200) | účel vztahu |
| `expected_turnover`, `expected_turnover_currency` | NUMERIC(20,2), CHAR(3) | |
| `pep_declaration` | BOOLEAN | 5AMLD PEP samoprohlášení klienta |
| `beneficial_owner_id` | UUID | odkaz na skutečného majitele (UBO) |
| `screening_provider`, `screening_ref` | VARCHAR | poskytovatel screeningu + reference |
| `next_review_date` | TIMESTAMPTZ | EBA periodická revize (HIGH 1r / MEDIUM 2r / LOW 3r) |
| `escalated_to`, `escalated_at`, `escalation_reason` | | stopa eskalace |

Indexy: `idx_kyc_cases_party_id`, `idx_kyc_cases_status`, `idx_kyc_due_diligence`, parciální `idx_kyc_review_date`, parciální `idx_kyc_pep` a parciální unikátní index **`uq_kyc_cases_active_party`** (V5) vynucující nejvýše jeden aktivní případ na party.

### `kyc_outbox` (V3)

Transakční outbox: `id`, `event_id` (UUID UNIQUE), `aggregate_id`, `event_type`, `payload` (TEXT), `status`, `attempt_count`, `sent_at`, `last_error`, `created_at`, `updated_at`. Indexy `idx_kyc_outbox_status_created_at`, `idx_kyc_outbox_aggregate_id`. Sekvence `kyc_outbox_seq` (V4).

## Flyway migrace

| Verze | Soubor | Co |
|---|---|---|
| V1 | `V1__create_kyc.sql` | `kyc_cases` + indexy + granty |
| V2 | `V2__compliance_fields.sql` | obohacující sloupce EBA AML / FATF CDD + constrainty |
| V3 | `V3__create_kyc_outbox.sql` | tabulka transakčního outboxu |
| V4 | `V4__hibernate_sequences.sql` | `kyc_cases_seq`, `kyc_outbox_seq` (Hibernate Reactive `INCREMENT BY 50`) |
| V5 | `V5__unique_active_kyc_case_per_party.sql` | parciální unikátní index `uq_kyc_cases_active_party` (idempotence ADR-0068) |

Každá migrace nese rollback poznámku v souboru (např. `DROP SEQUENCE …`, `DROP INDEX uq_kyc_cases_active_party`). **Nikdy needituj aplikovanou migraci** (checksum mismatch) — viz GitOps poznámky repa.

## Události

- **Topic (out):** `openbank.kyc.events` — JSON `{ eventType, kycCaseId, partyId, status, riskLevel, occurredAt }`. Typy událostí: `KYC_CASE_OPENED`, `KYC_CASE_STATUS_CHANGED`, `KYC_CASE_APPROVED`, `KYC_CASE_REJECTED`.
- **Topic (in):** `openbank.party.events` — konzumuje `PARTY_CREATED` pro auto-otevření případu.

## PII a klasifikace dat

Klasifikace: **restricted** (governance.yaml). KYC patří mezi nejcitlivější datové domény platformy.

| Pole | Citlivost | Zacházení |
|---|---|---|
| `party_id` | pseudonymní identifikátor | odkaz na master data `party-service`; přímá totožnost se zde neukládá |
| `checks_json`, `notes`, `escalation_reason` | blízko zvláštní kategorie (AML zjištění, PEP, nepříznivá média) | omezený přístup; pouze role KYC/compliance/admin |
| `source_of_funds`, `source_of_wealth`, `expected_turnover` | finanční profil | data EDD / FATF |
| `pep_declaration`, `beneficial_owner_id` | PEP / UBO | vysoká citlivost |

## Retence

**10 let** (governance.yaml `retentionPolicy: 10 years`), dané povinnostmi vedení záznamů dle AMLD — to přebíjí výmaz dle GDPR po zákonnou dobu po ukončení vztahu. Viz [06 — Compliance](./06-compliance.md).
