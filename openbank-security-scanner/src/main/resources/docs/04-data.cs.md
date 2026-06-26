# Data

## Schema

Vlastní PostgreSQL schema `openbank_security` v databázi `openbank` (sdílený cluster, izolace schema-per-service).

Scanner ukládá v PostgreSQL pouze **outbox** a **ICT incidenty**. Výsledky bezpečnostních skenů jsou drženy in-memory (ConcurrentHashMap) a republishovány do Kafky přes outbox; nejsou individuálně persistovány jako DB záznamy.

```mermaid
erDiagram
  ICT_INCIDENTS ||--o{ SECURITY_OUTBOX : "spouští"

  ICT_INCIDENTS {
    uuid id PK
    varchar title
    text description
    varchar category "AVAILABILITY|INTEGRITY|CONFIDENTIALITY|..."
    varchar severity "P1_CRITICAL|P2_HIGH|P3_MEDIUM|P4_LOW"
    varchar status "OPEN|INVESTIGATING|CONTAINED|RESOLVED|CLOSED"
    text affected_services "JSONB pole"
    timestamptz detected_at
    timestamptz reported_at
    timestamptz contained_at "nullable"
    timestamptz resolved_at "nullable"
    integer rto_minutes "nullable"
    integer rpo_minutes "nullable"
    boolean reported_to_regulator
    varchar regulatory_report_id "nullable"
    varchar assigned_to "nullable"
    timestamptz created_at
    timestamptz updated_at
  }

  SECURITY_OUTBOX {
    bigint id PK
    uuid event_id UK
    uuid aggregate_id
    varchar event_type
    text payload
    varchar status "PENDING|PUBLISHED|FAILED"
    integer attempt_count
    timestamptz sent_at "nullable"
    text last_error "nullable"
    timestamptz created_at
    timestamptz updated_at
  }
```

## Migrace

| Skript | Co dělá |
|---|---|
| `V2__create_security_outbox.sql` | Tabulka `security_outbox` s indexy na `(status, created_at)` a `aggregate_id` |
| `V3__hibernate_sequences.sql` | Hibernate sekvence pro generování surrogate klíčů |

> Poznámka: V1 chybí — scanner byl iniciálně bezestavový (žádná persistence ICT incidentů v první iteraci); V2 je první migrací, která vznikla.

## In-memory store vs. DB

| Data | Úložiště | Odůvodnění |
|---|---|---|
| `ServiceScanResult` (na službu) | In-memory `ConcurrentHashMap` | Nízká frekvence zápisů (každých 30m), rychlé čtení pro dashboard, obnova po restartu |
| `PlatformSecurityReport` | In-memory (pouze poslední výsledek) | Stejné odůvodnění; snapshot v čase |
| `SecurityFinding` | In-memory (součást výsledků) | Dočasné; historická zjištění přes Kafka / audit-service |
| `IctIncident` | PostgreSQL `ict_incidents` | Potřeba správy životního cyklu, DORA evidence, záznam regulatorního reportování |
| `SecurityOutbox` | PostgreSQL `security_outbox` | Transakční záruka pro Kafka publish |

## Indexy

- `security_outbox(status, created_at ASC)` — poll dispatcheru pro PENDING záznamy
- `security_outbox(aggregate_id)` — vyhledávání eventu podle ID incidentu
- `ict_incidents(status)` — filtrování podle stavu
- `ict_incidents(severity)` — filtrování podle závažnosti
- `ict_incidents(detected_at DESC)` — chronologický seznam incidentů

## Retence

| Tabulka | Retence | Důvod |
|---|---|---|
| `ict_incidents` | 10 let | DORA čl. 17 evidence; záznamy ICT incidentů jsou regulatorní důkaz |
| `security_outbox` | 30 dní po PUBLISHED | Řešení problémů, přehrávání |

ICT incidenty musí být uchovány pro regulatorní kontrolu ČNB dle implementace DORA. GDPR právo na výmaz se NEVZTAHUJE — jde o provozní záznamy, ne osobní data.

## PII úvahy

`ict_incidents` může obsahovat:
- `assigned_to` — email/jméno přiřazeného inženýra (data interního zaměstnance)
- `description` — volný text, který může odkazovat na systémy zákazníků

Tato pole nejsou externě vystavena. `assigned_to` jsou data interního operátora, ne zákaznické PII — žádná povinnost výmazu dle GDPR.

## Odhady velikosti

- `ict_incidents` — nízký objem. Odhad 10–50 incidentů/měsíc × 10 let = **max 6 000 řádků** (zanedbatelné)
- `security_outbox` (30denní okno) — 2 eventy na sken × 48 skenů/den × 30 dní = ~2 880 řádků (zanedbatelné)
- In-memory výsledky: 27 služeb × ~5 KB každá = ~135 KB (triviální)
