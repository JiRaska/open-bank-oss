# Data

## Schema

Vlastní PostgreSQL schema `openbank_security` v databázi `openbank` (sdílený cluster, izolace schema-per-service).

**Služba persistuje jednu tabulku: `ict_incidents`.** Po migraci `V5` obsahuje schema
`flyway_schema_history` a `ict_incidents`. Pro cokoli jiného ve službě neexistuje žádná entita,
repozitář ani JPA mapování — výsledky skenů zůstávají pouze in-memory.

Z toho plyne:

- **Výsledky skenů** (`ServiceScanResult`, `PlatformSecurityReport`) žijí v `SecurityScannerService`
  v polích typu `ConcurrentHashMap` (`lastResults`, `lastReport`). S restartem podu zanikají a obnoví
  je až další naplánovaný sken (až o 30 minut později, na studeném podu 2 minuty po startu).
- **ICT incidenty** jsou trvalé v tabulce `ict_incidents` (issue #4728). Dříve žily v
  `IctIncidentService` v `ConcurrentHashMap` (`store`) a s restartem podu zanikaly; to je opraveno —
  incident, jeho časy zvládnutí/vyřešení a stav `reported_to_regulator`/`regulatory_report_id` nyní
  restart podu přežijí.
- Historie skenů neexistuje. `GET /api/v1/security/report` vrací pouze poslední in-memory report.

`ict_incidents` je jediná tabulka, kterou tato služba vlastní:

```
ict_incidents
├── id                      UUID PRIMARY KEY  (přiděluje aplikace, UUID.randomUUID())
├── title                   TEXT NOT NULL
├── description             TEXT NOT NULL
├── category                VARCHAR(64) NOT NULL
├── severity                VARCHAR(32) NOT NULL
├── status                  VARCHAR(32) NOT NULL
├── affected_services       TEXT NOT NULL   (názvy služeb spojené čárkou)
├── detected_at             TIMESTAMPTZ NOT NULL
├── reported_at             TIMESTAMPTZ NOT NULL
├── contained_at            TIMESTAMPTZ
├── resolved_at             TIMESTAMPTZ
├── rto_minutes             INTEGER
├── rpo_minutes             INTEGER
├── reported_to_regulator   BOOLEAN NOT NULL DEFAULT FALSE
├── regulatory_report_id    TEXT
├── assigned_to             TEXT
├── created_at              TIMESTAMPTZ NOT NULL
└── updated_at              TIMESTAMPTZ NOT NULL
```

Žádná jiná tabulka na ni neodkazuje a ona neodkazuje na nic jiného — jednouzlový diagram, není proč
kreslit jako graf.

> Do #4709 tato stránka popisovala tabulku `security_outbox` a tabulku `ict_incidents` a obě
> označovala za fiktivní. Outbox sice existoval, ale nikdy do něj nikdo nezapsal (0 řádků za celou
> dobu a 0 záznamů kdy vyprodukovaných do jeho topicu) a byl odstraněn migrací `V4`; `ict_incidents`
> v tu chvíli také neexistovala. Migrace `V5` (issue #4728) pak vytvořila skutečnou tabulku
> `ict_incidents` popsanou výše — tato stránka je aktualizována, aby odpovídala.

## Migrace

| Skript | Co dělá | Stav |
|---|---|---|
| `V2__create_security_outbox.sql` | Vytvořila `security_outbox` s indexy na `(status, created_at)` a `aggregate_id` | Aplikována na živé DB; nahrazena V4 |
| `V3__hibernate_sequences.sql` | Vytvořila Hibernate/Panache sekvenci pro surrogate klíč outboxu | Aplikována; sekvenci ruší V4 |
| `V4__drop_security_outbox.sql` | `DROP TABLE security_outbox` + `DROP SEQUENCE security_outbox_seq` — outbox neměl producenta (#4709) | Aplikována mimo pořadí (#5628) |
| `V5__create_ict_incidents.sql` | Vytvořila `ict_incidents` (sloupce výše) + indexy na `created_at`, `status`, `severity` — přesouvá registr ICT incidentů dle DORA z in-memory mapy do DB (#4728) | Aktuální hlava |

> V1 chybí — scanner byl v první iteraci bezestavový a V2 je první migrací, která vznikla. V2 a V3
> jsou záměrně ponechány jako soubory a nesmazány: obě jsou zaznamenány jako aplikované v živé
> `flyway_schema_history` a odstranění souboru aplikované migrace selže na validaci Flyway stejně
> jistě, jako její editace selže na checksumu.

> `ict_incidents` nemá Hibernate sekvenci: její id přiděluje aplikace (`UUID.randomUUID()` v
> `IctIncidentService.reportIncident`), ne `@GeneratedValue` — entita je proto
> `PanacheEntityBase` s explicitním `@Id`, ne `PanacheEntity`. Update jde přes
> `Panache.getSession().flatMap { it.merge(entity) }` — `persist()` na přiděleném id by u každého
> uložení naplánoval INSERT a každý přechod stavu po prvním by selhal na duplicitním klíči (viz
> `IctIncidentEntity`, `IctIncidentRepositoryImpl.save`).

## Kde který stav žije

| Data | Úložiště | Životnost |
|---|---|---|
| `ServiceScanResult` (na službu) | In-memory `ConcurrentHashMap` | Do restartu podu; obnoví další sken |
| `PlatformSecurityReport` | In-memory (pouze poslední výsledek) | Do restartu podu; obnoví další sken |
| `SecurityFinding` | In-memory (součást výsledků) | Do restartu podu |
| `IctIncident` | Tabulka `ict_incidents` (PostgreSQL) | Trvalé — přežije restart podu |

## Indexy

`ict_incidents` má tři, podle toho, jak `GET /api/v1/ict-incidents` filtruje a řadí:

| Index | Sloupec(e) | Proč |
|---|---|---|
| `idx_ict_incidents_created_at` | `created_at DESC` | List endpoint řadí podle `created_at DESC` |
| `idx_ict_incidents_status` | `status` | List endpoint filtruje podle statusu |
| `idx_ict_incidents_severity` | `severity` | List endpoint filtruje podle severity |

Žádná jiná tabulka neexistuje, takže tyto jsou jediné indexy služby.

## Retence

Řádky v `ict_incidents` se uchovávají bez omezení — služba nemá TTL, archivační job ani mazací
cestu. Jedinou další trvalou stopou činnosti kolem ICT incidentů je Kafka topic
`openbank.security.ict.incident` a to, co si z něj uloží `audit-service`; tuto retenci vlastní
audit-service, ne tato služba.

Evidence dle DORA čl. 17 vyžaduje trvalý registr ICT incidentů; `ict_incidents` (od #4728, `V5`) je
tímto registrem. Před `V5` byla in-memory úložiště na této stránce správně označena jako mezera, ne
kontrola — tato mezera je uzavřena.

## PII úvahy

`IctIncident` může obsahovat:

- `assignedTo` — email/jméno přiřazeného inženýra (data interního zaměstnance)
- `description` — volný text, který může odkazovat na systémy zákazníků

Jde o data interního operátora, ne zákaznické PII. Zapisují se do `ict_incidents` (sloupce
`assigned_to`, `description`) a zároveň putují v Kafka eventu.

## Odhady velikosti

- Databáze: `flyway_schema_history` (4 řádky) + `ict_incidents`, očekávány desítky řádků měsíčně,
  každý pod 1 KB mimo `description` — nízké stovky KB ročně, pro vlastní schema triviální.
- In-memory výsledky skenů: 27 služeb × ~5 KB každá = ~135 KB (triviální).
