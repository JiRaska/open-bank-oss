# Provoz

## Build a spuštění

```bash
# Build (fast-jar)
./gradlew :openbank-card-processing-service:quarkusBuild

# Dev režim — OIDC vypnuté, sandbox acquirer zapnutý
./gradlew :openbank-card-processing-service:quarkusDev
```

## Endpointy a porty

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/card-authorizations/...` | 8157 | business REST API |
| `/api/v1/sandbox/acquirer/purchase` | 8157 | sandbox acquirer (404, pokud není zapnutý) |
| `/api/docs` | 8157 | Swagger UI |
| `/q/openbank/docs` | 8085 | tato dokumentace |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus |

## Konfigurace

| Klíč / env | Výchozí | Účel |
|---|---|---|
| `CARD_HOLD_EXPIRY_DAYS` | `7` | jak dlouho trvá neprezentovaný hold |
| `CARD_HOLD_SWEEP_CRON` | `0 */15 * * * ?` | rozvrh expiračního sweepu |
| `CARD_HOLD_SWEEP_BATCH` | `200` | počet holdů uvolněných za jeden běh |
| `CARD_LEDGER_POSTING_ENABLED` | `true` | zaúčtovat zúčtovanou útratu přes transaction-service (`false` ⇒ `SKIPPED_DISABLED`) |
| `CARD_FRAUD_SCORING_ENABLED` | `true` | stínové skórování (`false` ⇒ `SKIPPED_DISABLED`) |
| `CARD_SANDBOX_ACQUIRER_ENABLED` | `false` | **nikdy nezapínat v nasazeném prostředí** — pohybuje penězi end-to-end |
| `CARD_DEFAULT_CURRENCY` | `CZK` | card-issuance zatím nepublikuje měnu pro jednotlivou kartu |
| `CARD_SCHEME_BIN_LOOKUP` | `simulator` | `simulator` / `visa` / `mastercard` |
| `CARD_SCHEME_TOKENISATION` | `simulator` | vendor hodnoty odpovídají `NOT_BOUND` |
| `CARD_SCHEME_DISPUTE` | `simulator` | vendor hodnoty odpovídají `NOT_BOUND` |
| `VISA_API_KEY`, `VISA_KEYSTORE_*`, `VISA_TRUSTSTORE_*` | prázdné | přihlašovací údaje pro Visa sandbox, dodávané z OpenBao |
| `MASTERCARD_CONSUMER_KEY`, `MASTERCARD_SIGNING_KEY`, `MASTERCARD_API_URL` | prázdné | přihlašovací údaje pro Mastercard sandbox (podpisový klíč = base64 PKCS#8 RSA), z OpenBao |
| `CARD_ISSUANCE_SERVICE_URL`, `TRANSACTION_SERVICE_URL`, `FRAUD_SERVICE_URL` | porty na localhost | navazující služby |
| `AUTHZ_ENFORCE` | `false` | rozhodnutí OPA je poradní, dokud se nepřepne |

Timeouty klientů: card-issuance 2 s connect / 3 s read (fail closed), fraud-service 2 s / 2 s, transaction-service 3 s / 10 s, Visa a Mastercard 3 s / 5 s.

V `application.yaml` není žádný přihlašovací údaj; zástupné hodnoty pro lokální vývoj (`CHANGE_ME_LOCAL_DEV_ONLY`) je nutné přepsat.

## Plánované úlohy

| Úloha | Rozvrh | Liveness |
|---|---|---|
| `card-processing-outbox-dispatcher` | každých 5s (`openbank.outbox.poll-interval`) | gauge backlogu outboxu + dead-letter |
| `card-processing-hold-expiry` | cron, každých 15 min | gauge workflow liveness registrovaný při startu (ADR-0237) |

Obě jsou `suspend fun`; v `%test` je plánovač vypnutý a testy je spouštějí explicitně.

## Metriky

| Metrika | Tagy |
|---|---|
| `openbank.card.processing.authorizations` | `approved`, `reason` |
| `openbank.card.processing.presentments` | `fully_cleared` |
| `openbank.card.processing.clearing.conflicts` | — (clearingy, reverzace a expirace, které prohrály souběh zápisů a byly znovu vyhodnoceny; expirace, která prohraje dvakrát, počká na další sweep) |
| `openbank.card.processing.hold.releases` | `kind` (`REVERSAL` / `EXPIRY`) |
| `openbank.card.processing.ledger.postings` | `outcome` (`POSTED` / `SKIPPED_DISABLED` / `FAILED`) |
| `openbank.card.processing.fraud.scores` | `outcome` (`SCORED` / `SKIPPED_DISABLED` / `FAILED`) |

| `openbank.card.token.provisions` | `scheme`, `refusal` |
| `openbank.card.token.status.changes` | `scheme`, `status`, `refusal` |
| `openbank.card.token.reads` | `source` (`NETWORK` / `LOCAL_MIRROR`) |
| `openbank.card.disputes.opened` | `scheme`, `refusal` |
| `openbank.card.dispute.evidence` | `refusal` |
| `openbank.card.dispute.terminal.mismatches` | `scheme`, `stored`, `reported` |

Při úspěchu je `refusal` rovno `none`. `scheme="NONE"` označuje odmítnutí rozhodnuté dříve, než se ptala jakákoli síť (neznámá nebo neaktivní karta, nedostupná card-issuance, nezpůsobilá reklamace) — stejná hodnota na čítačích tokenů i reklamací. Všechny nesou `service="card-processing"`, stejně jako gauge backlogu a dead-letter outboxu.

## Alerty

Definované v `openbank-infra/gitops/components/observability/prometheus-rules-card-money-path.yaml`, s promtool testy v `openbank-infra/tests/promtool/card_money_path_alerts_test.yaml`:

| Alert | Spustí se, když | Závažnost |
|---|---|---|
| `CardClearedButNotPosted` | jakékoli zaúčtování `SKIPPED_DISABLED` za 1h | critical |
| `CardLedgerPostingFailing` | jakékoli zaúčtování `FAILED` za 30m | warning |
| `CardTokenReadsServedFromMirror` | víc než polovina čtení tokenů za 30m šla z `LOCAL_MIRROR` | warning |
| `CardDisputesNotReachingTheScheme` | jakékoli otevření reklamace odmítnuté `SCHEME_UNAVAILABLE` za 6h | warning |
| `CardTokenProvisioningAlwaysRefused` | každý pokus o vydání tokenu za 1h byl odmítnut | warning |

## Runbooky

### Zaúčtování FAILED

Clearing je zaznamenán, účetnictví ne. Hledejte řádek logu `ledger posting FAILED for authorization …` a counter `outcome=FAILED`. Ověřte dosažitelnost transaction-service a token client credentials. Zaúčtování nese idempotenční klíč `card-clearing:<idAutorizace>:h:<base64url(SHA-256(klíč))>` (viz 03 — API), takže opakování přes transaction-service nemůže zaúčtovat dvakrát. Opakování stejného clearingu acquirerem zaúčtování **nezkusí znovu**: klíč clearingu je už započten a vrátí replay bez sáhnutí na účetnictví, takže zaúčtování `FAILED` je nutné znovu spustit vědomě.

### Každá autorizace je zamítnuta

`CardIssuanceAdapter` selhává uzavřeně. Než začnete zkoumat limity, ověřte health card-issuance a odchozího OIDC klienta. Porovnejte tag `reason` na `openbank.card.processing.authorizations`.

### Holdy se neuvolňují

Zkontrolujte liveness gauge `card-processing-hold-expiry` a logy na `card hold expiry sweep failed`. Spočítejte splatné holdy:
`SELECT count(*) FROM card_authorizations WHERE status IN ('APPROVED','PARTIALLY_CLEARED') AND expires_at < now();`

### Zpoždění outboxu nebo dead letters

`SELECT status, count(*) FROM card_outbox GROUP BY status;` a pak prohlédněte `last_error` u řádků `FAILED`/`DEAD`. Ověřte dosažitelnost Kafky; publisher je za circuit breakerem.

### BIN lookup odpovídá NOT_BOUND

Očekávané, když je vazba `visa`/`mastercard` a chybí přihlašovací údaje — adaptér žádný požadavek neodešle. U tokenizace a reklamací je `NOT_BOUND` jedinou vendor odpovědí: tyto programy vyžadují smlouvu se schématem.

### Čtení tokenů jde ze zrcadla

`source: LOCAL_MIRROR` znamená, že vazba tokenizace neodpověděla a seznam může být zastaralý; `degradedReason` uvádí selhání. Je-li `CARD_SCHEME_TOKENISATION` nastaveno na `visa` nebo `mastercard`, je to trvalé (`NOT_BOUND` — vendor adaptér neexistuje); přepněte zpět na `simulator` nebo zrcadlo akceptujte.

### Chargeback nešlo otevřít

`SCHEME_UNAVAILABLE` na `openbank.card.disputes.opened` znamená, že se nezapsal žádný řádek — otevření selhává uzavřeně. Stejná příčina `NOT_BOUND` jako výše, pokud `CARD_SCHEME_DISPUTE` jmenuje vendora. `NO_NETWORK_REFERENCE` je datový problém autorizace (acquirer neposlal referenci), ne výpadek.

### Požadavek na token odpovídá trvale 409 IDEMPOTENCY_REQUEST_IN_PROGRESS

Rezervace klíče uvízla v `PENDING`: požadavek selhal poté, co se ptala síť (zalogováno jako ERROR, „idempotency key left PENDING“). Automaticky se nikdy neuvolní, protože síť mohla token vydat nebo případ otevřít. Ověřte u schématu, co existuje, a pak řádek buď dokončete (`state='COMPLETED'`, `result_id`) proti záznamu, který vytvoříte, nebo ho smažte, pokud síť nic neudělala. Zaseknuté klíče: `SELECT * FROM card_lifecycle_idempotency WHERE state='PENDING' AND created_at < now() - interval '10 minutes'`.

### Uzavřená reklamace nesouhlasí se sítí

`openbank.card.dispute.terminal.mismatches` > 0: refresh případu WON/LOST/WITHDRAWN zjistil, že síť hlásí jiný výsledek. Uložený výsledek se záměrně ponechává; prošetřete to se schématem (logová řádka „closed dispute … is stored … but the network now reports …“).

## Testy a CI

- Unit: `AuthorizationLifecycleTest`, `CardProcessingServiceTest`, `CardIssuanceAdapterTest`, `TransactionLedgerPostingAdapterTest`, `MastercardOAuthSignerTest`, `CardTokenServiceTest`, `CardDisputeServiceTest`, `SchemeAdapterFailureTest` a jeden test na každý simulátor.
- Integrační: `CardLifecycleIdempotencyIT` (souběžné požadavky se stejným klíčem za latchí, stav karty, historie důkazů) a `CardAuthorizationOutboxIT` proti PostgreSQL (`openbank_card_processing_it`), `HoldExpirySweepVertxContextIT` spouští skutečný cron. Zaúčtování a fraud skórování jsou v `%test` vypnuté.
- Kontrakt: `CardIssuanceAuthorizationPactConsumerTest` (consumer pact vůči card-issuance).
- Generovaný runbook: `docs/runbooks/svc-card-processing.md`.

## Nasazení / release

Verzování přes `version.txt` a release-please; ručně neupravovat. Docker image je fast-jar.
