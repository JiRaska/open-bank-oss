# Provoz

## Build

```
./gradlew :openbank-lending-service:build
./gradlew detekt ktlintCheck koverVerify build   # lokální brána před PR
```

- Konvenční plugin `openbank.quarkus-service` (ADR-0049 D1).
- Práh pokrytí: kover **40% LINE** (money-path baseline, ratchet-only; aspirovaný cíl 70%). REST/CDI/reflection třídy jsou z metriky vyloučeny.
- Image: **pouze fast-jar** (`-Dquarkus.package.jar.type=fast-jar`); runtime stage kopíruje `quarkus-app/`. Build na hostiteli (`openbank-infra/scripts/build-push-service.sh openbank-lending-service`), nikdy in-Docker Gradle.

## Konfigurace (klíčové env proměnné)

| Proměnná | Výchozí | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB heslo. ⬜ Žádný `BootstrapVerifier` neexistuje, takže tento placeholder při startu nic neblokuje (#8426) — v prod hodnota přichází přes `secretKeyRef` z ESO/OpenBao v `lending-service.yaml` (ADR-0007) |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | `http://localhost:8080/realms/openbank` | OIDC issuer |
| `LEDGER_SERVICE_URL` | `http://localhost:8101` | base REST klienta ledger-service |
| `LENDING_LEDGER_BACKEND` | `none` | `rest` aktivuje `RestLedgerPostingAdapter` (build-time přepínač) |
| `LENDING_LEDGER_SYSTEM_ACTOR_ID` | `…00aa` | `createdBy` na ledger zápisech |
| `LENDING_GL_*` | (UUID výchozí) | GL leaf účty: loans-receivable, funding-clearing, interest-income, interest-receivable, loan-loss-expense, loan-loss-allowance |
| `LENDING_ACCRUAL_EVERY` | `24h` | Interval akruálního průchodu úročení |
| `LENDING_ACCRUAL_BATCH_SIZE` | `500` | Počet splátek na akruální průchod |
| `LENDING_PROVISIONING_EVERY` | `720h` (~30d) | Interval cyklu IFRS 9 provisioningu (ADR-0028 Fáze 3); obyčejná doba trvání, ne kalendářní měsíc |
| `LENDING_PROVISIONING_BATCH_SIZE` | `500` | Počet ACTIVE úvěrů zpracovaných za jeden cyklus provisioningu (bez stránkování nad tento limit — viz threat model §5) |

`LENDING_LEDGER_BACKEND` je **build-time** (`@IfBuildProperty`): vybírá adaptér při sestavení image, ne za běhu.

## Porty & probes

- **App:** `8126`. **Management:** `8086`, root-path `/q` (`quarkus.management.enabled=true`).
- **Health (SmallRye):** `/q/health`, `/q/health/live`, `/q/health/ready` na management portu.
- **Metriky:** Micrometer → Prometheus na `/q/metrics`. **Tracing:** OpenTelemetry OTLP → `http://localhost:4317` (`service.name=openbank-lending-service`).
- **Docs:** `/q/openbank/docs` (Docs-as-Service, ADR-0019). **Swagger UI:** `/api/docs`.
- Bezpečnostní hlavičky nastaveny globálně (HSTS, CSP `default-src 'self'`, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy). Logy jsou JSON mimo dev.

## Serverless tier (ADR-0057)

Lending je **money-path služba** a money-path služby jsou ve výchozím stavu **T0** (`rules.yaml: t0_baseline = money_path_services`) — vždy zapnuté, bez scale-to-zero. Členství v T0 je posvátné: demote by vyžadovalo ADR-0030 threat model + 2 schválení. In-process naplánovaná servicing smyčka (úročení) je další argument proti scale-to-zero.

## SLO (cílové)

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl |
|---|---|
| Dostupnost | 99.9 % (T0, vždy zapnuté) |
| Latence čtení (p99) | < 200 ms |
| Latence decision/disburse (p99) | < 500 ms (vč. hopu účetního zápisu při `backend=rest`) |
| Zpoždění výdeje outboxu | < 10 s (dispatcher tiká každých 5 s) |
| RTO / RPO | 15 min / 5 min (viz DORA mapování, [06 — Compliance](./06-compliance.md)) |

## Runbooky

### Roste backlog outboxu
Řádky `lending_outbox.status` uvíznou neodeslané a `attempt_count` stoupá ⇒ zkontroluj Kafka konektivitu a `last_error`. Dispatcher (`@Scheduled every 5s`, batch 25, `SKIP` překryv) opakuje automaticky; trvalý backlog ukazuje na broker nebo topic `openbank.lending.events`. Nemazat řádky — jsou zárukou at-least-once doručení.

### Selhává účetní zápis
Při `LENDING_LEDGER_BACKEND=rest` jdou zápisy přes `LedgerCallGuard` (fault tolerance) do `ledger-service POST /api/v1/journals`. Selhání se projeví v disburse/repay/writeoff. Ověř `LEDGER_SERVICE_URL`, OIDC token služby a že GL účty `LENDING_GL_*` existují v účtové osnově. Zápisy jsou idempotentní (reference = idempotency key ledgeru), takže je bezpečné je opakovat.

### Akruální průchod úročení neběží / má zpoždění
Zkontroluj logy `InterestAccrualScheduler` ("interest accrual pass: N installments accrued"). Interval je `LENDING_ACCRUAL_EVERY` (výchozí 24h, delayed 30s). Průchod je idempotentní (příznak `interest_accrued`); zmeškané okno se samo zhojí dalším tikem, protože vybírá všechny splatné-ale-nenaběhnuté splátky.

### Cyklus IFRS 9 provisioningu neběží / nezaúčtoval deltu
Zkontroluj logy `ProvisioningCycleScheduler` ("IFRS 9 provisioning cycle {period}: N loans assessed, M provisioning journals posted"). Interval je `LENDING_PROVISIONING_EVERY` (výchozí ~720h/30d, delayed 60s). Nula zaúčtovaných zápisů při nenulovém počtu zpracovaných úvěrů je **očekávané a správné**, pokud se stage/ECL žádného úvěru od minulého období nezměnilo — než usoudíš na chybu, zkontroluj řádky v `loan_provisioning` pro dané období. Průchod je idempotentní podle `(loan_id, period)`; zmeškané okno se samo zhojí dalším tikem, ale kniha větší než `LENDING_PROVISIONING_BATCH_SIZE` je za jeden tik pokryta jen částečně (zatím bez kurzoru pro pokračování — sledováno v threat modelu).

### Doúčtování do hlavní knihy (jednorázové, #10746)
Pro úvěry, jejichž účetní historie se do hlavní knihy nikdy nedostala (#6057). Pouze ROLE_ADMIN, dvě různé osoby.
1. **Zkouška nanečisto** (nic nezapisuje): `GET /api/v1/lending/ledger-backfill/plan?cutoverDate=<dnes>&disbursedBefore=<datum>`. Ověřte `executable=true`, `plan.tieOut[*].ties=true` a `glTotals`.
2. **Návrh** (maker): `POST /api/v1/lending/ledger-backfill/requests`.
3. **Schválení** (checker, jiný admin): `POST /requests/{id}/decide`. Vlastní schválení vrací 422.
4. **Provedení**: `POST /requests/{id}/execute?execute=true` v den cut-overu. 409 znamená, že se kniha od schválení změnila, cut-over uplynul, nebo běží jiné provedení.
5. U selhání se úvěr zastaví na první chybě a žádost zůstává APPROVED. Po opravě krok 4 zopakujte. Už zaúčtované zápisy se v hlavní knize přehrají bez účinku.
6. **Storno**: každý zápis stornujte v ledger-service (`reverseJournal`) podle klíče `loan:<id>:…`.
Zápisy se účtují k datu cut-overu, s původním datem události ve `valueDate`. Pouze hlavní kniha: na účet klienta se nic nepřipisuje.

### Flyway checksum mismatch při startu
Nikdy nepřepisuj nasazenou migraci. Nastav dočasně `QUARKUS_FLYWAY_REPAIR_AT_START=true`, nech DB ustálit a pak odstraň.

## Deploy

GitOps (ArgoCD) dle platformního vzoru. Při konfliktech image-tagů ber `--ours` (čerstvě sestavený tag), `--theirs` pro RBAC/config/env (CLAUDE.md). Verzování a changelog vlastní release-please z Conventional Commits — nikdy ručně needituj `version.txt` ani `CHANGELOG.md`.
