# Data

## Přehled persistence

- **Primární úložiště:** PostgreSQL 16, dedikovaná databáze `openbank_sca` (reactive PG klient; Flyway běží přes JDBC URL).
- **Generování schématu:** `none` — Flyway je jediným zdrojem pravdy (`migrate-at-start: true`).
- **Přechodný stav:** Redis (Valkey) drží OTP, idempotenční klíče a decoupled rozhodnutí zařízení — nic z toho není trvalé; je klíčováno id výzvy a omezeno TTL výzvy.

> Pozn.: `governance.yaml` deklaruje logický název schématu `sca_schema` (kurátorská metadata ADR-0071); živé migrace vytvářejí tabulky ve výchozím schématu `public` databáze `openbank_sca`.

## Flyway migrace

| Verze | Soubor | Účel |
|---|---|---|
| V1 | `V1__init_sca.sql` | tabulka `sca_challenges` + indexy na `party_id`, `status`, `expires_at` |
| V2 | `V2__create_sca_outbox.sql` | tabulka `sca_outbox` + indexy na `(status, created_at)` a `aggregate_id` |
| V3 | `V3__hibernate_sequences.sql` | sekvence `sca_outbox_seq` (INCREMENT BY 50) — vyžadována alokací id v Panache při `generation:none`. Rollback: `DROP SEQUENCE sca_outbox_seq;` |
| V4 | `V4__enrolled_devices.sql` | tabulka `sca_enrolled_devices` + index na `party_id` (ADR-0021). Rollback: `DROP TABLE IF EXISTS sca_enrolled_devices;` (credentialy jsou znovu zapsatelné ze zařízení) |

**Nikdy nepřepisuj aplikovanou migraci** (CLAUDE.md / Flyway gotcha) — přidej novou verzovanou migraci.

## Tabulky

### `sca_challenges`
| Sloupec | Typ | Pozn. |
|---|---|---|
| `id` | UUID PK | `gen_random_uuid()` |
| `party_id` | UUID | indexováno; PII odkaz (pseudonymní identifikátor) |
| `purpose`, `method`, `status` | VARCHAR(50) | enum; `status` výchozí `PENDING` |
| `expires_at`, `completed_at`, `failed_at` | TIMESTAMPTZ | časová razítka životního cyklu |
| `failure_reason` | TEXT | nastaveno jen při terminálním FAILED |
| `attempt_count`, `max_attempts` | INT | výchozí 0 / 3 |
| `dynamic_amount` | VARCHAR(30) | dynamické provázání (RTS čl. 5) |
| `dynamic_currency` | VARCHAR(3) | |
| `dynamic_creditor_iban` | VARCHAR(34) | **PII** — IBAN příjemce |
| `dynamic_creditor_name` | VARCHAR(255) | **PII** — jméno příjemce |
| `dynamic_reference` | VARCHAR(255) | reference platby |
| `redirect_url` | TEXT | |
| `created_at` | TIMESTAMPTZ | `NOW()` |

### `sca_enrolled_devices`
| Sloupec | Typ | Pozn. |
|---|---|---|
| `id` | UUID PK | |
| `party_id` | UUID | indexováno; PII odkaz |
| `credential_id` | TEXT UNIQUE | stabilní per-credential id, které zařízení předkládá |
| `public_key_spki` | TEXT | Base64 X.509 SubjectPublicKeyInfo (pouze veřejný klíč — **privátní klíč nikdy neopustí hardwarové úložiště zařízení**) |
| `algorithm` | VARCHAR(16) | ES256 / ED25519 |
| `created_at` | TIMESTAMPTZ | |

### `sca_outbox`
Standardní outbox tabulka: `id BIGSERIAL`, `event_id UUID UNIQUE`, `aggregate_id UUID` (= partyId), `event_type` (např. `DEVICE_ENROLLED`), `payload TEXT`, `status`, `attempt_count`, `sent_at`, `last_error`, `created_at`, `updated_at`. Vyprazdňuje `ScaOutboxDispatcher` do Kafka topicu `openbank.sca.challenge.event`.

## Inventář PII

| Pole | Umístění | Klasifikace | Zacházení |
|---|---|---|---|
| `party_id` | challenges, devices, outbox aggregate | pseudonymní identifikátor | není přímý identifikátor; rozlišuje se přes `party-service` |
| `dynamic_creditor_iban` / `dynamic_creditor_name` | challenges | PII (platební kontext) | uchováno pro důkaz dynamického provázání; nikdy nelogováno v plain textu |
| `public_key_spki` | devices | netajné (veřejný klíč) | bezpečné persistovat; odpovídající privátní klíč zůstává na zařízení |
| OTP | pouze Redis | tajné, přechodné | TTL 300 s, invalidováno při úspěchu, nikdy nepersistováno do Postgresu |
| podpis zařízení | Redis (store rozhodnutí) | uchováno pro audit, přechodné | omezeno TTL výzvy |

Celková klasifikace dat (`governance.yaml`): **restricted**.

## Retence

| Data | Retence |
|---|---|
| `sca_challenges`, `sca_enrolled_devices`, `sca_outbox` | **5 let** (`governance.yaml: retentionPolicy`), v souladu s uchováváním autentizačních důkazů dle PSD2/AMLD |
| OTP / rozhodnutí (Redis) | sekundy–minuty (TTL výzvy, výchozí 300 s) |

`evidenceExported: true` — záznamy autentizačních důkazů jsou exportovány do audit/evidence pipeline.
