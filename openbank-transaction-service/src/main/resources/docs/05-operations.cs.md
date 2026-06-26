# Provoz

## Build

```
./gradlew :openbank-transaction-service:build       # kompilace + testy
./gradlew detekt ktlintCheck koverVerify build      # lokální gate před PR
```

CI je path-scoped (buildují se jen změněné služby). Doménová vrstva má **nula** importů frameworku. Coverage gate (Kover): line coverage ≥ 40 (`build.gradle.kts`); ratchet-only, nikdy níž. Money-path služby míří výš.

### Image

- **pouze fast-jar** — Dockerfile COPYuje `quarkus-app/`. Nikdy `uber-jar` (nechá `quarkus-app/` prázdný → crashloop).
- **Build na hostu, ne in-Docker Gradle.** Generický build: `openbank-infra/scripts/build-push-service.sh transaction-service`.

## Konfigurace (klíčové env)

| Env | Účel | Default (dev) |
|---|---|---|
| `POSTGRES_PASSWORD` | DB credential | `CHANGE_ME_LOCAL_DEV_ONLY` |
| `OIDC_CLIENT_SECRET` | Keycloak client secret (OIDC + oidc-client) | placeholder |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | URL realmu | `http://localhost:8080/realms/openbank` |
| `LEDGER_SERVICE_URL` | ledger REST klient | `http://localhost:8101` |
| `BALANCE_SERVICE_URL` | balance REST klient | `http://localhost:8103` |
| `FX_SERVICE_URL` | fx REST klient | `http://localhost:8119` |
| `BUILD_TIME` / `GIT_COMMIT` | build metadata pro `/api/v1/info` | `unknown` |

Porty: **8102** aplikace, **8085** management (`/q`). Rate limit: `openbank.rate-limit.enabled=true`, `max-concurrent-requests=150`. Outbox poll: každých 5 s, initial delay 5 s.

## Health probes

SmallRye Health je na management rozhraní (`/q`, port 8085):

- **Liveness:** `/q/health/live`
- **Readiness:** `/q/health/ready` (konektivita DB)
- **Metriky:** `/q/metrics` (Micrometer / Prometheus)
- **Docs:** `/q/openbank/docs` (tato dokumentace)

OpenTelemetry traces exportují do OTLP `http://localhost:4317` (`service.name = openbank-transaction-service`).

## Serverless tier (ADR-0057)

Transaction-service je **money-path** služba, tedy **T0** dle pravidla `t0_baseline: money_path_services` (`rules.yaml`). T0 = vždy teplá, `min > 0` replik. **Není** kandidátem na scale-to-zero: sedí na synchronní platební cestě (cold start by se přičetl k řetězci synchronních volání ledger/balance). Demotion pod T0 vyžaduje aktualizaci threat modelu dle ADR-0030 + 2 schválení.

## SLO

| Metrika | Cíl (navrhovaný) | Poznámky |
|---|---|---|
| Dostupnost | 99,9 % | money-path |
| Iniciace p95 latence | TBD | dominována synchronními voláními ledger + balance + (volitelně) FX |
| Zpoždění publikace outboxu | < 10 s | dispatcher běží každých 5 s |
| RTO / RPO | 15 min / 5 min | zděděný platformní cíl (viz pilot account-service) |

Přesná čísla latence SLO jsou TBD do změření v sandboxu; downstream volání ságy mají 2 s connect / 3 s read timeouty (`application.yaml`).

## Runbooky

### Outbox zaseknutý (události se nepublikují)
1. Zkontroluj `transaction_outbox` na řádky se `status='FAILED'` a `last_error`.
2. Dispatcher obaluje publikaci do `@CircuitBreaker` — vyhozený breaker pozastaví publikaci; prohlédni logy na stav breakeru a konektivitu Kafky.
3. Po obnově Kafky se FAILED řádky retryují při dalším pollu (znovu vylistovány `listProcessable`).

### Sága zaseknutá za běhu
1. Dotaz `payment_sagas WHERE state NOT IN ('COMPLETED','COMPENSATED','FAILED')` (pokryto partial indexem).
2. Sága, která vyhodila výjimku za běhu, by už měla být COMPENSATED/FAILED (kompenzace je best-effort a idempotentní). Řádek ponechaný ve FUNDS_RESERVED značí vložený hold — balance-service jej expiruje po TTL 300 s, takže prostředky neunikají.
3. Ověř konzistenci ledgeru: zaúčtovaný journal je při kompenzaci reverzován; zachycený debet je vrácen (`compensation-{txId}`).

### Flyway checksum mismatch při startu
- Nikdy nepřepisuj aplikovanou migraci. Pokud checksum mismatch blokuje start, nastav `QUARKUS_FLYWAY_REPAIR_AT_START=true` v gitops env, nech DB ustálit, pak odeber.

### FX kurz nedostupný
- Iniciace v jiné měně selže s `FxRateUnavailableException`, když fx-service nemá kotaci. Ověř `FX_SERVICE_URL` a zdraví fx-service; zúčtování ve stejné měně nejsou dotčena (žádný FX leg).

## Deploy

GitOps přes ArgoCD (sandbox sleduje deploy branch). U konfliktů image-tagů v gitops manifestech ber `--ours` (čerstvě sestavený tag), nikdy slepě `--theirs`. Ruleset ověřených podpisů vyžaduje, aby commit email odpovídal registrovanému GPG klíči.
