# Provoz

## Build & běh

```bash
# Build (fast-jar — nikdy uber-jar)
./gradlew :openbank-swift-service:quarkusBuild

# Dev režim (live reload; OIDC vypnuto v %dev)
./gradlew :openbank-swift-service:quarkusDev

# Lokální brána před PR
./gradlew detekt ktlintCheck koverVerify build
```

Docker image: vícestupňový `Dockerfile` (Eclipse Temurin 25 JDK build → JRE-alpine runtime, non-root uživatel `openbank`, fast-jar layout `quarkus-app/`, `-XX:+UseZGC`, `EXPOSE 8122`). Obecný build helper: `openbank-infra/scripts/build-push-service.sh swift-service` (host-side `quarkusBuild`, ne Gradle v Dockeru).

## Endpointy / porty

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/swift/...` | 8122 | business REST API |
| `/api/docs` | 8122 | Swagger UI (`always-include: true`) |
| `/api/v1/info` | 8122 | ServiceInfoResource (build metadata) |
| `/q/openapi` | 8085 | OpenAPI dokument |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/health` | 8085 | SmallRye Health (liveness + readiness) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management rozhraní běží na samostatném portu (`quarkus.management.port: 8085`, root-path `/q`).

## Konfigurace

| Env proměnná | Výchozí | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB heslo — **MUSÍ být přepsáno v prod (Vault)** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — **MUSÍ být přepsáno v prod** |
| Kafka bootstrap | `localhost:29092` | `quarkus.smallrye-reactive-messaging.kafka.bootstrap-servers` |
| Redis hosts | `redis://localhost:6379` | podpora cache/idempotence |
| `OPA_URL` | `http://localhost:8181` | OPA sidecar base URL (ADR-0034) |
| `OPA_PATH` | `/v1/data/openbank/rest/allow` | OPA query path |
| `OPA_TIMEOUT_MS` | `500` | timeout OPA rozhodnutí |
| `AUTHZ_ENFORCE` | `false` | OPA **výchozí advisory**; přepni na enforce |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata v `/api/v1/info` |

Bezpečnostní hlavičky jsou nastaveny na HTTP vrstvě (CSP `default-src 'self'`, HSTS, `X-Frame-Options: DENY`, nosniff atd.). CORS povoluje `http://localhost:3000` s `Idempotency-Key` mezi povolenými hlavičkami.

## Resilience (z konfigurace)

- **Rate limit:** zapnuto, `max-concurrent-requests: 200`.
- **Circuit breaker:** request-volume-threshold 10, failure-ratio 0.4, success-threshold 3, delay 15s.
- **Retry:** max 2, delay 1s, jitter 0.5s. **Timeout:** 60s.
- **Outbox dispatch** má vlastní SmallRye Fault Tolerance anotace (viz [02 — Architektura](./02-architecture.md)).

## Health checks

- **Liveness:** `/q/health/live` (port 8085) — běh JVM + ArC.
- **Readiness:** `/q/health/ready` (port 8085) — dosažitelnost datasource (a nakonfigurované messaging/redis).

Graceful shutdown: `quarkus.shutdown.timeout: 30s`.

## Serverless tier (ADR-0057)

`openbank-swift-service` je **money-path** HTTP služba ([ADR 0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md)). Vysokohodnotové wire instrukce jsou citlivé na dostupnost, takže vhodný tier je **T0 — Always-on** (`minReplicas ≥ 1`, nikdy neškáluje na nulu); přesun na nižší tier by vyžadoval ADR-0030 threat model + 2 schválení a musel by být odůvodněn měřeným chováním v nečinnosti. Tier je odvozen z měřeného provozu, ne přiřazen ručně.

## SLO (cíle)

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9 % | `up{service="swift-service"}` |
| Latence p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 POST (submit) | < 300 ms | zahrnuje validaci + DB zápis |
| Outbox lag | < 5 s (dispatcher pollu je každých 5s) | stáří pending na `swift_outbox` |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Nevyřešené výsledky interního zaúčtování

`openbank_payment_unsettled_total{rail="SWIFT",outcome="..."}` počítá neúspěšné pokusy vůči transaction-service. Povolené hodnoty jsou `mismatch`, `failed-replay`, `conflict`, `unexpected-status` a `transport-error`; všech pět řad se registruje s nulou před prvním pokusem. Opakované pokusy se počítají samostatně, nejde tedy o počet unikátních zpráv. Zrušení coroutine ani úspěšná odpovídající reakce čítač nezvyšují. `failed-replay` označuje dekódovanou odpověď FAILED, nikoli důkaz, že tento požadavek byl replay.

Zaúčtování vyžaduje HTTP 201, transakci COMPLETED s identifikátorem a shodu s odeslanými údaji. Ostatní výsledky neprokazují dokončení zaúčtování; nezávislý ACK/odmítnutí může změnit obchodní stav. Při šetření zachovejte klíč `swift-settlement-<messageId>`: 409 ani nejistá odpověď nedokazují dokončené zaúčtování. Před nápravnou akcí ověřte výsledek schváleným reconciliation nástrojem; neměňte klíč ani nepoužívejte nový booking POST jako vyhledávání. transaction-service stále nemá veřejné vyhledání podle idempotentního klíče; runbook čte jeho databázi. Alarm nezaúčtované zprávy popisuje sekce níže.

### Outbox lag / události neproudí

1. Počet pending: `SELECT count(*) FROM swift_outbox WHERE status='PENDING'`.
2. Prozkoumej selhání: `SELECT event_id, attempt_count, last_error FROM swift_outbox WHERE status='FAILED'`.
3. Zkontroluj logy dispatcheru pro `SwiftOutboxDispatcher` (otevřený circuit breaker?) a dosažitelnost Kafka brokeru.
4. Dispatcher běží každých 5s s `concurrentExecution = SKIP`; zaseknutý publish vyhodí breaker (otevře po 10 voláních při poměru selhání 0.5, delay 5s).

### Flyway checksum mismatch při startu

Symptom: `FlywayValidateException`. Příčina: aplikovaná migrace byla upravena. Fix: nastav `QUARKUS_FLYWAY_REPAIR_AT_START=true`, restartuj, pak flag odstraň, jakmile se DB usadí. Nikdy nepřepisuj aplikované migrace.

### Chybějící `swift_outbox_seq`

Symptom: outbox INSERT selže s `relation "swift_outbox_seq" does not exist`. Příčina: `V3` neaplikováno. Fix: zajisti spuštění migrace V3; pokryto `HibernateSequenceGuardTest`.

### Konflikt idempotency key

Opakovaný submit se stejným `idempotencyKey` vrátí stávající zprávu. `409` značí duplicitní klíč s konfliktním payloadem — neopakuj; oprav klienta nebo použij nový klíč.

## Testy & pokrytí

- Testy: `SwiftServiceTest`, `SwiftMessageTest`, `HibernateSequenceGuardTest` (Quarkus JUnit5, AssertJ, MockK).
- Pokrytí: Kover line floor **40 %** (money-path baseline, ratchet-only, dle `rules.yaml`); zvyšuj s přibývajícími testy směrem k cíli 70 % pro money-path.

## Deploy / release

- **Release osa:** release-please vlastní `version.txt` (aktuálně `0.2.0`); nikdy needituj ručně. Changelog řídí Conventional Commits.
- **API osa:** `openapi.yaml: info.version` (1.0.0) bumpováno nezávisle z OpenAPI diffu.
- CI je path-scoped (buildí se jen změněné služby). CD přes ArgoCD při bumpu image tagu.
- **Money-path:** každý PR vyžaduje 2 schválení + udržovaný [threat model](../../../../docs/threat-models/openbank-swift-service.md).

## Sdílené výchozí konfigurace

Soubor `application.yaml` této služby nedeklaruje bezpečnostní hlavičky HTTP odpovědí (`quarkus.http.header.*`) ani management rozhraní (`quarkus.management.*`: zapnuto, port 8085, host 0.0.0.0, kořenová cesta `/q`). Hodnoty pocházejí z výchozí konfigurace flotily v `openbank-libs-runtime/src/main/resources/application.yaml`, která je na classpath každé služby s nižší prioritou než vlastní soubor služby. Hodnoty jsou shodné s tím, co služba dříve deklarovala sama. Pro změnu jen v této službě nastavte hodnotu v jejím `application.yaml`; soubor služby má přednost.

## Sdílené závislosti sestavení

Soubor `build.gradle.kts` této služby neuvádí závislosti, které sdílí každá Quarkus služba: Quarkus BOM, quarkus-kotlin, RESTEasy Reactive s Jacksonem, SmallRye Health a OpenAPI, OIDC, registr Micrometer Prometheus a config-yaml a testovací knihovny quarkus-junit5, AssertJ a REST Assured. Deklaruje je konvenční plugin `openbank.quarkus-service` v `build-logic`. Výsledná classpath je beze změny. `build.gradle.kts` uvádí jen to, co služba přidává navíc.

## Hodiny odchozího zaúčtování (#13623)

Přijetí MT103 uloží první čas splatnosti zaúčtování atomicky se SENT a existující stavovou
událostí před voláním transaction-service. Chybějící účet povinnost neuzavírá. Pouze striktně
ověřená odpověď 201 COMPLETED s identitou transakce dovolí atomicky uložit lokální důkaz,
COMPLETED a stavovou událost. Souběžný ACK/odmítnutí nebo změna revize blokuje zastaralé
dokončení; stav callbacku se nepřepisuje. Chybějící lokální důkaz neznamená, že poskytovatel
nezaúčtoval. Rekonciliace používá původní klíč `swift-settlement-<messageId>`; nový retry ani klíč
nevzniká.

`openbank_payment_booking_tracked_pending_oldest_age_seconds{rail="SWIFT"}` je dolní mez stáří
nejstarší sledované povinnosti bez lokálního důkazu. Zůstává číselná i při nenulové
`openbank_payment_booking_unknown_coverage{rail="SWIFT"}` pro nepokryté historické řádky MT103.
Dotaz nefiltruje obchodní stav: ACKNOWLEDGED/REJECTED není důkaz zaúčtování. Před prvním
úspěšným dotazem je hodnota NaN, známá prázdná sledovaná množina má nulu nezávisle na neznámém
pokrytí. Chyba dotazu zachová čas v cache, stáří dále roste a nezaznamená se úspěch workflow.
Nullable sloupce nedoplňují smyšlené historické časy. Alarmuje na ně `SwiftBookingUnsettled` (30 min, níže); re-drive níže opakuje jen sledované závazky;
nepřibývá veřejné API ani automatické historické opravy.

### Alarm nezaúčtované zprávy (#13623)

`SwiftBookingUnsettled` alarmuje, když jsou nejstarší otevřené hodiny zaúčtování MT103 starší než 30 minut (`openbank-infra/gitops/components/payments/prometheus-rules-unsettled.yaml`, testy v `openbank-infra/tests/promtool/payment_unsettled_alerts_test.yaml`). swift-service volá transaction-service jednou za přijatou zprávu; cokoli neprokázaného pak znovu zkouší re-drive níže, a pokud ani ten zaúčtování neprokáže, k operátorovi se dostane signál stáří. Platí to i pro zprávu bez id účtu příkazce nebo s nečitelným datem valuty, kterou čítač výsledků nevidí. Náprava (dotaz do transaction-service podle klíče, pak reconcile nebo reverse) je v sekci SWIFT v `docs/runbooks/0032-payment-rail-unsettled-booking.md`. `ack`/`reject` hodiny nezavírají a nesmí se jimi alarm umlčet.

### Re-drive zaúčtování (#13686)

`SwiftSettlementRedriveScheduler` běží každých 60 s a znovu zkouší zaúčtovat každou MT103 ve stavu `SENT` s otevřenými hodinami zaúčtování (`booking_first_due_at` nastaveno, `booking_proven_at` null), starší než `openbank.swift.settlement-redrive.min-age` (2 min). Volá transaction-service znovu pod stejným klíčem `swift-settlement-<messageId>` s požadavkem sestaveným z uložené zprávy, takže datum valuty je datum z pole 32A, nikdy dnešní. Nikdy znovu neodesílá do schématu. Souběžné běhy nemohou volat oba: každou zprávu si jedním podmíněným UPDATE zabere token s 2min leasem a každý další zápis je podmíněný tímto tokenem. Neprokázaný pokus se počítá v `booking_redrive_attempts` a další čeká `min(2 min * 2^(n-1), 1 h)`; po 10 pokusech se zpráva označí `booking_redrive_exhausted_at`, zvýší se `openbank_payment_unsettled_total{rail="SWIFT",outcome="redrive-exhausted"}` a otevřené hodiny dál drží `SwiftBookingUnsettled` v alarmu. Zastavený sweep zachytí `WorkflowLivenessStale` (`swift-settlement-redrive`). Vypínač: `SWIFT_SETTLEMENT_REDRIVE_ENABLED` (výchozí `true`). Zprávu, kterou operátor už potvrdil nebo odmítl, re-drive nebere (není `SENT`); řeší ji runbook. Uložená odpověď `FAILED` nebo neshodná se vrací při každém pokusu stejně, re-drive se na ní vyčerpá a nápravou je opět runbook. Alarm (30 min) může vzniknout, když re-drive ještě běží (10 pokusů trvá asi 6 h), proto každá ruční náprava začíná zastavením podle runbooku ve čtyřech očích (`booking_redrive_stopped_at`, odmítnuto, dokud re-drive drží živý claim); zastavenou zprávu sweep nikdy nezabere. `ack`/`reject` re-drive také zastaví, při živém claimu vrátí 409 a hodiny zaúčtování nechají otevřené. Nečitelné uložené datum valuty se počítá jako `outcome="bad-data"` a vyčerpá se hned; chyba databáze u jedné zprávy se počítá jako `outcome="redrive-error"` a sweep pokračuje.
