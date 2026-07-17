# Provoz

## Build & běh

```bash
# Build (lokálně, fast-jar — nikdy uber-jar)
./gradlew :openbank-notification-service:quarkusBuild

# Běh v dev módu (live reload; OIDC vypnuté, mailer mockovaný)
./gradlew :openbank-notification-service:quarkusDev

# Lokální gate před PR
./gradlew detekt ktlintCheck koverVerify build
```

Generický build image: `openbank-infra/scripts/build-push-service.sh notification-service` (nejdřív host-side `quarkusBuild`, pak Docker COPY `quarkus-app/`).

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/notifications/...` | 8112 | čtení notifikací |
| `/api/v1/devices` | 8112 | registr push device tokenů |
| `/api/v1/ops/dispatch/...` | 8112 | break-glass řízení výpravy |
| `/api/v1/info` | 8112 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8112 | Swagger UI |
| `/q/openapi` | 8085 / 8112 | OpenAPI specifikace |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Vyhrazené **management rozhraní** je zapnuté na portu **8085** (`quarkus.management`) a nese health, metriky a docs; business API je na **8112**. (Management je pod test profilem vypnutý.)

## Konfigurace

| Env var | Default | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — **v produkci NUTNO přepsat přes Vault** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret |
| `SLACK_WEBHOOK_ENABLED` | `false` | zapnutí oversight webhooku (ADR-0059) |
| `SLACK_WEBHOOK_URL` | (nenastaveno) | Slack incoming-webhook URL — injektováno z Vaultu, nikdy v gitu |
| `FCM_ENABLED` | `false` | zapnutí FCM push adaptéru |
| `FCM_SERVICE_ACCOUNT_JSON` | (nenastaveno) | FCM service-account JSON (Vault) |
| `FCM_PROJECT_ID` | (nenastaveno) | volitelné, fallback z JSON |
| `APNS_ENABLED` | `false` | zapnutí APNs push adaptéru |
| `APNS_KEY_ID` / `APNS_TEAM_ID` / `APNS_BUNDLE_ID` | / / `tech.openbank.app` | APNs identifikátory |
| `APNS_PRIVATE_KEY` | (nenastaveno) | .p8 PKCS#8 EC klíč (Vault) |
| `APNS_SANDBOX` | `false` | true → APNs sandbox host |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata v `/api/v1/info` |

Push adaptéry a oversight webhook jsou **ve výchozím stavu vypnuté**; vypnutý adaptér zaznamená úspěšný no-op (žádný egress). Přihlašovací údaje jsou za běhu injektované z Vaultu přes ExternalSecret — nikdy commitnuté.

## Health checky

- **Liveness:** `/q/health/live` (port 8085) — JVM + ArC. Restart podu při selhání.
- **Readiness:** `/q/health/ready` — reaktivní DB spojení. Flyway na startu opakuje připojení 10× po 2 s.

## Serverless / workload tier (ADR-0057)

notification-service je řízená událostmi a nárazová (reaguje na upstream události), což z ní dělá kandidáta pro **scale-to-zero / scale-from-zero** workload tiery [ADR 0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md). Výhrady specifické pro tuto službu:

- `@Scheduled` outbox tick (každých 5 s) a trvalá Kafka consumer subscription znamenají, že **nejde** o čistě request-driven HTTP workload — scale-to-zero musí počítat s tím, že consumer group drží přiřazenou partition. Klasifikace tieru je **TBD** do běhu FinOps klasifikátoru.

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,5 % | Prometheus `up{service="notification-service"}` |
| Consumer lag | < 5 s | lag consumer-group na `openbank.notification.requests` |
| Úspěšnost doručení e-mail/push | best-effort (ne peněžní cesta) | rozložení `notifications.status` |
| Tick výpravy outboxu | každých 5 s, batch 25 | `NotificationOutboxDispatcher` |
| Chybovost (REST) | < 0,5 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Notifikace se nedoručují

1. Je výprava zastavená? `GET /api/v1/ops/dispatch` — pokud `state=HALTED`, operátor sáhl po break-glass. Obnovte přes four-eyes (`/resume/propose` + *jiný* aktér `/approve`).
2. Zkontrolujte consumer lag na `openbank.notification.requests`; zkontrolujte logy `NotificationConsumer` na parse/processing chyby.
3. EMAIL zaseknutý v `PENDING`/`FAILED`? Ověřte SMTP (`quarkus.mailer.*`); v dev je mailer mockovaný.
4. PUSH `FAILED` s "no active devices"? Party nemá žádné ACTIVE `device_tokens`, nebo jsou FCM/APNs adaptéry vypnuté (pak je push no-op SENT).

### Zastavení / obnovení výpravy (break-glass)

```bash
# Zastavení (jeden aktér)
curl -XPOST .../api/v1/ops/dispatch/halt -d '{"reason":"incident #123"}'
# Obnovení vyžaduje four-eyes:
curl -XPOST .../api/v1/ops/dispatch/resume/propose -d '{"reason":"cleared"}'   # aktér A
curl -XPOST .../api/v1/ops/dispatch/resume/{id}/approve -d '{"reason":"ok"}'    # aktér B (≠ A) → 422 pokud A==B
```

### Push tokeny jsou odmítány

Odmítnutí poskytovatelem (`UNREGISTERED` / `BadDeviceToken`) automaticky označí řádek `device_tokens` jako `INVALID`, takže vypadne z budoucího rozesílání. Žádná manuální akce; rostoucí počet INVALID signalizuje zastaralé instalace klienta.

### Poison message na topicu

Neparsovatelný payload se zaloguje a potvrdí (nezaklíní partition). Prozkoumejte zalogovaný payload; opravte producenta.

## Deploy / release

- Per-service path-scoped CI; release-please vlastní `version.txt` (aktuálně `0.4.0`) a changelog z Conventional Commits.
- CI běží testy proti izolovanému PostgreSQL na test JVM přes Testcontainers (#578); Kafka je v testech in-memory (jen `@Incoming` kanál), `@Scheduled` outbox tick je pod testem vypnutý.
- CD přes ArgoCD při bumpu image tagu (GitOps); u konfliktů image tagu berte `--ours`.
