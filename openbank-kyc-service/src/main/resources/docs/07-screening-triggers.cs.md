# Screeningu triggery (událostmi řízené re-screening)

Perpetual-KYC slice 1 podle [ADR 0256](../../../../docs/adr/0256-perpetual-kyc-event-driven-re-screening-between-periodic-reviews.md): když `openbank-sanctions-service` publikuje diff sankčního seznamu (`SANCTIONS_LIST_CHANGED` na `openbank.sanctions.screening.event`), kyc-service odvodí kandidátní strany a otevře jeden re-screeningový případ na stranu **přes case coordinator** — triggery případ pouze OTEVÍRAJÍ; rozhodnutí zůstává na člověku (ADR-0256 D2).

## Průběh

1. **Konzumace** — `SanctionsListChangedConsumer` čte kanál `sanctions-list-changes-in` (explicitní DLQ topic `openbank.dlq.kyc.sanctions-list-changes`; group id a `auto.offset.reset` přicházejí z ConfigMap `kyc-service-msg-override`, aparát #686). Události `SANCTIONS_LIST_CHANGE_STORM` se ignorují. Typ události se čte z Kafka **hlavičky** `ce-type`: topic je plněn z outboxu a těla zpráv žádné pole `eventType` nenesou (`openbank-contracts/openbank-sanctions-service/asyncapi.yaml`). Každý záznam se počítá v `openbank.kyc.sanctions_list_change_events{outcome}` — `handled`, `storm`, `disabled`, `ignored` (screeningové události sdílející topic), `malformed`, `unrecognised`.
2. **Záznam** — `ScreeningTriggerService` vloží jeden trvalý řádek `kyc_screening_triggers` na (publication, party) — `insertIfAbsent`, takže redelivery Kafka zprávy naváže místo duplikování. Celý diff zůstává na řádku, aby „proč je tento klient v re-screeningu" mělo odpověď v jednom řádku.
3. **Otevření** — jeden případ `AML_ALERT` na stranu přes `POST /api/v1/case-coordinator/cases` (`case-coordinator-agent` v namespace `platform`, port 8146, dodáno jako `CASE_COORDINATOR_URL`).
4. **Trvalé selhání** — 429/503/timeout z coordinatoru zaznamená trigger `OPEN_FAILED` a po vyčerpání pokusů outbox řádek `KYC_COORDINATOR_OPEN_FAILED` ve STEJNÉ transakci jako změnu stavu. Chybějící `CASE_COORDINATOR_URL` namapuje každý trigger na trvalou cestu `NOT_CONFIGURED` — nikdy na tiché přeskočení.
5. **Retry** — `ScreeningTriggerRetryJob` opakuje řádky `OPEN_FAILED` po backoffu, takže se smyčka uzavře, když se coordinator zotaví.

## HTTP výsledky koordinátoru

Adaptér pro otevření AML případu klasifikuje mapované HTTP chyby uvnitř stávající hranice opakování:

- `201` otevře případ pouze tehdy, když odpověď obsahuje neprázdné `caseId`. Chybějící identifikátor se odmítne.
- `409` znamená, že koordinátor případ již eviduje (`AlreadyExists`); volání se neopakuje.
- Ostatní deterministické odpovědi `4xx`, včetně `400` a `403`, se odmítnou bez opakování. Výjimkou je `429`, které zůstává opakovatelné.
- `429`, `5xx` a transportní chyby zachovávají stávající omezené opakování: nejvýše tři HTTP pokusy na jedno volání adaptéru. Circuit breaker a timeout mohou volání ukončit dříve. Tento limit je oddělený od rozpočtu trvalé obnovy triggeru popsaného níže.

Odpovědi poskytovatele se po klasifikaci uzavírají, včetně odpovědí získaných z mapovače výjimek REST klienta. Detaily chybového výsledku obsahují pouze HTTP status nebo typ výjimky, nikdy tělo odpovědi poskytovatele, URL ani zprávu výjimky. Klasifikace zachovává stávající trvalé zpracování triggerů; nezapíná screeningové triggery ani nemění idempotenci koordinátoru.

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
- **`outcome="unrecognised"` nad nulou znamená, že se consumer a producent neshodnou na tvaru zprávy** (chybí hlavička `ce-type`, nebo jde o typ, který consumer nezná). Záznamy se potvrzují (ack), takže to nic jiného — lag, DLQ ani chybovost — neukáže; při zapnuté smyčce je stejným signálem z druhé strany publikace na straně sanctions bez přírůstku `handled` zde.
- Selhávací cesta consumeru rethrowuje do connectoru, takže infrastrukturní selhání dead-letteruje; selhání volání coordinatoru je práce řádku + outbox události, ne Kafky.
- Tabulka `kyc_screening_triggers` (Flyway V12) je append-audited: přechody `state` jsou updaty, diff sloupce se zapisují jednou.

## Metriky a alerty backlogu

Issue #15961. Backlog tvoří řádky `kyc_screening_triggers` ve stavu PENDING nebo OPEN_FAILED: změny seznamů, které se k zákazníkovi ještě nedostaly jako otevřený případ. `RescreenBacklogGauge` jej jednou za minutu vzorkuje z databáze (`openbank.kyc.screening-triggers.backlog-sample-interval`, výchozí `1m`) jedním agregátem nad úvodním sloupcem `state` indexu `ix_kyc_screening_triggers_retry`, takže každý pod publikuje stejnou hodnotu a restart nic neztratí.

| Metrika | Význam | Studený pod |
|---|---|---|
| `openbank_kyc_rescreen_backlog_pending{service="kyc"}` | Řádky PENDING nebo OPEN_FAILED (rozpracované, opakované nebo odstavené). | NaN do prvního vzorku |
| `openbank_kyc_rescreen_backlog_oldest_age_seconds{service="kyc"}` | `now() - created_at` nejstaršího takového řádku podle hodin databáze, od zařazení do fronty. `0` jen při prázdném backlogu. Nikdy z `next_attempt_at`, který každý pokus posouvá dopředu. | NaN do prvního vzorku |
| `openbank_kyc_rescreen_loop_disabled_with_pending{service="kyc"}` | `1`, když je `enabled` nebo `retry-enabled` false a backlog není prázdný, jinak `0`. Samostatná řada, aby „vypnuto a prázdno“ a „vypnuto se zbylou prací“ nikdy nesdílely hodnotu. | NaN do prvního vzorku |

NaN, ne 0 a ne stáří odvozené od epochy: studený pod netvrdí prázdnou zdravou frontu, kterou nikdo neměřil, ani nehlásí desetiletí. Neúspěšný vzorek ponechá předchozí hodnoty, takže výpadek databáze nemůže zrušit spuštěný alert; místo toho zestárne heartbeat `kyc-rescreen-backlog-sample`.

**Lhůta: 24 hodin.** ADR-0256 slibuje, že se změna seznamu k zákazníkovi dostane v rámci cyklu obnovy, a seznamy se obnovují denně. Zdravý nejhorší případ je asi hodina (pět pokusů s backoffem 5 minut krát pokus), takže řádek starší než den předběhne další změna seznamu.

- `KycRescreenBacklogOverdue` (critical): nejstarší stáří nad 86400 s po dobu 15 minut, pokud smyčka není vypnutá se zbylou prací.
- `KycRescreenLoopDisabledWithPending` (warning): řada vypnuto-se-zbylou-prací je 1 po dobu 30 minut.

Ani jeden nemůže vystřelit ve výchozím nasazení (smyčka vypnutá, tabulka prázdná: stáří 0, vypnuto-se-zbylou-prací 0). Testy: `openbank-infra/tests/promtool/kyc_rescreen_backlog_alerts_test.yaml`. Runbook: [05-operations](05-operations.cs.md#backlog-opětovného-prověření).

## Obnova práce a rozsah ochrany proti duplicitám

Nový řádek PENDING má desetiminutový pronájem a číslo pokusu. Worker atomicky přebírá pouze vypršelý pronájem; zápis výsledku porovnává převzaté číslo pokusu i pronájem ve stejné transakci jako případný outbox záznam selhání. Starý worker nemůže přepsat novější výsledek. Historické PENDING řádky bez pronájmu lze převzít deset minut po vzniku; nové vložení bez pronájmu se odmítá. Terminální OPEN_FAILED řádky bez času dalšího pokusu se automaticky neopakují.

Nová práce s pronájmem dovoluje nejvýše pět volání koordinátoru; čítač zahrnuje i volání, jehož výsledek se při pádu ztratil. Obnova po pátém opuštěném pokusu provede šesté převzetí pro zajištění výhradního zápisu a uloží čítač šest do zaparkovaného řádku a události, bez šestého volání koordinátoru. Operátor musí nejasný poslední výsledek rekoncilovat místo slepého opakování.

Volání koordinátoru zachovává deterministické ID workflow a politiku Temporal REJECT_DUPLICATE. Ochrana platí během uchování historie workflow; po jejím vypršení nejde o trvalou záruku právě jednoho provedení. API idempotence ani retenční politika koordinátoru se nemění.

Limit kandidátů určuje velikost stránky. Pokračování podle posledního klíče projde celý aktivní seznam místo tichého omezení na první stránku. Rollback vypíná screeningové triggery; čekající a selhané řádky se zachovávají pro rekonciliaci.

Limit pěti volání platí pro nové řádky s pronájmem. Historický řádek s nulovým počtem pokusů a bez pronájmu nedokazuje, zda staré volání koordinátoru proběhlo; jeho historické vedlejší účinky zůstávají nejasné a musí se rekoncilovat s historií koordinátoru. Obnova nedokazuje, že celkový historický počet volání nepřekročil pět.
