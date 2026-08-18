# Data

## Schema

Vlastní PostgreSQL schema `openbank_security` v databázi `openbank` (sdílený cluster, izolace schema-per-service).

**Služba nepersistuje nic provozního.** Po migraci `V4` obsahuje schema `flyway_schema_history` a
žádnou další tabulku. Ve službě neexistuje žádná entita, repozitář ani JPA mapování — databáze
existuje výhradně proto, aby si Flyway měl kam zapisovat vlastní historii migrací a aby měla
readiness sonda co kontrolovat.

Z toho plyne:

- **Výsledky skenů** (`ServiceScanResult`, `PlatformSecurityReport`) žijí v `SecurityScannerService`
  v polích typu `ConcurrentHashMap` (`lastResults`, `lastReport`). S restartem podu zanikají a obnoví
  je až další naplánovaný sken (až o 30 minut později, na studeném podu 2 minuty po startu).
- **ICT incidenty** žijí v `IctIncidentService` v `ConcurrentHashMap` (`store`). S restartem podu
  zanikají a **nelze je obnovit** — jedinou jejich kopií je Kafka event vyslaný v okamžiku vzniku.
- Historie skenů neexistuje. `GET /api/v1/security/report` vrací pouze poslední in-memory report.

Není co nakreslit jako ER diagram: služba nevlastní žádné tabulky.

> Do #4709 tato stránka popisovala tabulku `security_outbox` a tabulku `ict_incidents`. Outbox sice
> existoval, ale nikdy do něj nikdo nezapsal (0 řádků za celou dobu a 0 záznamů kdy vyprodukovaných
> do jeho topicu) a byl odstraněn migrací `V4`; tabulka `ict_incidents` neexistovala nikdy.

## Migrace

| Skript | Co dělá | Stav |
|---|---|---|
| `V2__create_security_outbox.sql` | Vytvořila `security_outbox` s indexy na `(status, created_at)` a `aggregate_id` | Aplikována na živé DB; nahrazena V4 |
| `V3__hibernate_sequences.sql` | Vytvořila Hibernate/Panache sekvenci pro surrogate klíč outboxu | Aplikována; sekvenci ruší V4 |
| `V6__drop_security_outbox.sql` | `DROP TABLE security_outbox` + `DROP SEQUENCE security_outbox_seq` — outbox neměl producenta (#4709) | Aktuální hlava |

> V1 chybí — scanner byl v první iteraci bezestavový a V2 je první migrací, která vznikla. V2 a V3
> jsou záměrně ponechány jako soubory a nesmazány: obě jsou zaznamenány jako aplikované v živé
> `flyway_schema_history` a odstranění souboru aplikované migrace selže na validaci Flyway stejně
> jistě, jako její editace selže na checksumu.

## Kde který stav žije

| Data | Úložiště | Životnost |
|---|---|---|
| `ServiceScanResult` (na službu) | In-memory `ConcurrentHashMap` | Do restartu podu; obnoví další sken |
| `PlatformSecurityReport` | In-memory (pouze poslední výsledek) | Do restartu podu; obnoví další sken |
| `SecurityFinding` | In-memory (součást výsledků) | Do restartu podu |
| `IctIncident` | In-memory `ConcurrentHashMap` | Do restartu podu — **neobnovitelné** |

## Indexy

Žádné — služba nemá vlastní tabulky.

## Retence

V databázi této služby není co uchovávat. Jedinou trvalou stopou její činnosti je Kafka topic
`openbank.security.ict.incident` a to, co si z něj uloží `audit-service`; tuto retenci vlastní
audit-service, ne tato služba.

Kdo potřebuje trvalý registr ICT incidentů — což evidence dle DORA čl. 17 reálně vyžaduje — měl by
současné in-memory úložiště považovat za mezeru, ne za kontrolu.

## PII úvahy

`IctIncident` může obsahovat:

- `assignedTo` — email/jméno přiřazeného inženýra (data interního zaměstnance)
- `description` — volný text, který může odkazovat na systémy zákazníků

Jde o data interního operátora, ne zákaznické PII, a zde se nikdy nezapisují do databáze. Putují ale
v Kafka eventu.

## Odhady velikosti

- Databáze: pouze `flyway_schema_history` — 3 řádky.
- In-memory výsledky skenů: 27 služeb × ~5 KB každá = ~135 KB (triviální).
- In-memory ICT incidenty: omezené pouze životností podu; očekávány desítky měsíčně.
