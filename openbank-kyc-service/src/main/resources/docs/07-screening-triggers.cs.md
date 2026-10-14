# Screeningu triggery (událostmi řízené re-screening)

Perpetual-KYC slice 1 podle [ADR 0256](../../../../docs/adr/0256-perpetual-kyc-event-driven-re-screening-between-periodic-reviews.md): když `openbank-sanctions-service` publikuje diff sankčního seznamu (`SANCTIONS_LIST_CHANGED` na `openbank.sanctions.screening.event`), kyc-service odvodí kandidátní strany a otevře jeden re-screeningový případ na stranu **přes case coordinator** — triggery případ pouze OTEVÍRAJÍ; rozhodnutí zůstává na člověku (ADR-0256 D2).

## Průběh

1. **Konzumace** — `SanctionsListChangedConsumer` čte kanál `sanctions-list-changes-in` (explicitní DLQ topic `openbank.dlq.kyc.sanctions-list-changes`; group id a `auto.offset.reset` přicházejí z ConfigMap `kyc-service-msg-override`, aparát #686). Události `SANCTIONS_LIST_CHANGE_STORM` se ignorují.
2. **Záznam** — `ScreeningTriggerService` vloží jeden trvalý řádek `kyc_screening_triggers` na (publication, party) — `insertIfAbsent`, takže redelivery Kafka zprávy naváže místo duplikování. Celý diff zůstává na řádku, aby „proč je tento klient v re-screeningu" mělo odpověď v jednom řádku.
3. **Otevření** — jeden případ `AML_ALERT` na stranu přes `POST /api/v1/case-coordinator/cases` (`case-coordinator-agent` v namespace `platform`, port 8146, dodáno jako `CASE_COORDINATOR_URL`).
4. **Trvalé selhání** — 429/503/timeout z coordinatoru zaznamená trigger `OPEN_FAILED` a po vyčerpání pokusů outbox řádek `KYC_COORDINATOR_OPEN_FAILED` ve STEJNÉ transakci jako změnu stavu. Chybějící `CASE_COORDINATOR_URL` namapuje každý trigger na trvalou cestu `NOT_CONFIGURED` — nikdy na tiché přeskočení.
5. **Retry** — `ScreeningTriggerRetryJob` opakuje řádky `OPEN_FAILED` po backoffu, takže se smyčka uzavře, když se coordinator zotaví.

## Známá granularita v1

Kandidátní množina **není** rozsahovaná per položka diffu: kyc-service neuchovává mapování strana ↔ položka seznamu, takže v1 triggeruje na strany s neukončeným KYC případem při každé publikaci diffu (omezeno knihou rozpracovaných případů). Per-entry rozsahování vyžaduje index strana ↔ matched-entry (follow-up slice); trigger řádky si celý diff pro audit uchovají tak jako tak.

## Konfigurace

| Env proměnná | Výchozí | Účel |
|---|---|---|
| `KYC_SCREENING_TRIGGERS_ENABLED` | `false` | Hlavní vypínač. Lodi se **vypnuté** podle rollout sekvence kontraktu (Pact verifikace, sandbox exercise, metriky operátorských úkolů před enablementem). |
| `KYC_SCREENING_TRIGGERS_CANDIDATE_LIMIT` | `500` | Strop kandidátních stran na chunk publikace. |
| `KYC_SCREENING_TRIGGERS_RETRY_ENABLED` | `true` | Plánovaný retry řádků `OPEN_FAILED`. |
| `KYC_SCREENING_TRIGGERS_RETRY_INTERVAL` | `1m` | Backoff retry. |
| `KYC_SCREENING_TRIGGERS_RETRY_BATCH_SIZE` | `100` | Řádky zpracované v jednom retry průchodu. |
| `CASE_COORDINATOR_URL` | (prázdné → `NOT_CONFIGURED`) | Base URL case coordinatoru; v gitops nastaveno na `http://case-coordinator-agent.platform.svc:8146`. |

## Poznámky pro provoz

- **Nový Kafka kanál je dva deploye, ne jeden** (.github/CLAUDE.md): group id / offset override `sanctions-list-changes-in` přistanou v msg-override ConfigMap až poté, co je nasazen image nesoucí `connector:` kanálu; do té doby běží consumer na fallback group id (`quarkus.application.name`), na který má KafkaUser `kyc-service` udělen Read.
- Selhávací cesta consumeru rethrowuje do connectoru, takže infrastrukturní selhání dead-letteruje; selhání volání coordinatoru je práce řádku + outbox události, ne Kafky.
- Tabulka `kyc_screening_triggers` (Flyway V12) je append-audited: přechody `state` jsou updaty, diff sloupce se zapisují jednou.

## Obnova práce a rozsah ochrany proti duplicitám

Nový řádek PENDING má desetiminutový pronájem a číslo pokusu. Worker atomicky přebírá pouze vypršelý pronájem; zápis výsledku porovnává převzaté číslo pokusu i pronájem ve stejné transakci jako případný outbox záznam selhání. Starý worker nemůže přepsat novější výsledek. Historické PENDING řádky bez pronájmu lze převzít deset minut po vzniku; nové vložení bez pronájmu se odmítá. Terminální OPEN_FAILED řádky bez času dalšího pokusu se automaticky neopakují.

Nová práce s pronájmem dovoluje nejvýše pět volání koordinátoru; čítač zahrnuje i volání, jehož výsledek se při pádu ztratil. Obnova po pátém opuštěném pokusu provede šesté převzetí pro zajištění výhradního zápisu a uloží čítač šest do zaparkovaného řádku a události, bez šestého volání koordinátoru. Operátor musí nejasný poslední výsledek rekoncilovat místo slepého opakování.

Volání koordinátoru zachovává deterministické ID workflow a politiku Temporal REJECT_DUPLICATE. Ochrana platí během uchování historie workflow; po jejím vypršení nejde o trvalou záruku právě jednoho provedení. API idempotence ani retenční politika koordinátoru se nemění.

Limit kandidátů určuje velikost stránky. Pokračování podle posledního klíče projde celý aktivní seznam místo tichého omezení na první stránku. Rollback vypíná screeningové triggery; čekající a selhané řádky se zachovávají pro rekonciliaci.

Limit pěti volání platí pro nové řádky s pronájmem. Historický řádek s nulovým počtem pokusů a bez pronájmu nedokazuje, zda staré volání koordinátoru proběhlo; jeho historické vedlejší účinky zůstávají nejasné a musí se rekoncilovat s historií koordinátoru. Obnova nedokazuje, že celkový historický počet volání nepřekročil pět.
