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
| `openbank.card.processing.ledger.postings` | `outcome` (`POSTED` / `SKIPPED_DISABLED` / `FAILED` / `UNKNOWN`) |
| `openbank.card.processing.ledger.posting.unposted` (gauge) | clearingy, které stále čekají na zaúčtování |
| `openbank.card.processing.ledger.posting.exhausted` (gauge) | clearingy, které vyčerpaly pokusy; alert `CardLedgerPostingExhausted` |
| `openbank.card.processing.fraud.scores` | `outcome` (`SCORED` / `SKIPPED_DISABLED` / `FAILED`) |

Stínové fraud skórování posílá fraud-service jeho skutečný kontrakt `POST /api/v1/fraud/score`: `accountId`, `amount` v hlavních jednotkách podle počtu desetinných míst měny, `currency` a `rail = "CARD"`. Čte `verdict` a celočíselné `score`. Autorizaci nikdy neblokuje. Selhání (jakékoli 4xx/5xx, timeout nebo nečitelné tělo) se počítá jako `outcome=FAILED` a loguje na úrovni WARN, nejvýš jednou za minutu s počtem potlačených selhání. Do 2026-10-04 klient posílal `currencyCode`, žádné `rail` a částky v nejmenších jednotkách a četl `decision`, takže každé stínové skórování tiše selhalo. Pacty `pacts/openbank-card-processing-service-openbank-fraud-service.json` a `…-openbank-transaction-service.json` teď obě volání ukotvují, včetně zaznamenané odpovědi 401.

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

**Podmínka postupného nasazení.** Následující pokyny popisují schválenou verzi s re-driverem, nikoli důkaz jejího nasazení. Před uvolněním holdu image card služby ověřte V22 poskytovatele a získejte výslovný souhlas vlastníka s opakováním backlogu. Nasazení tohoto image aktivuje re-driver, pokud `ledger-posting-enabled` ponechává výchozí hodnotu `true`; samostatný výchozí vypnutý přepínač re-driveru neexistuje. Dokud hold drží starší image, nepředpokládejte automatické opakování, exhausted metriku ani heartbeat workflow. Jejich nepřítomnost nedokládá nápravu.

Clearing je zaznamenán, účetnictví možná ne. Hledejte řádek logu `ledger posting FAILED|UNKNOWN for authorization …` a čítač `outcome=FAILED|UNKNOWN`. `UNKNOWN` znamená nečitelnou odpověď (tělo, které nejde namapovat, přerušené spojení): není známo, zda ledger debet zaúčtoval. Zaúčtování nese idempotency key `card-clearing:<authorizationId>:h:<base64url(SHA-256(key))>`, takže jeho opakování nemůže účtovat dvakrát.

Každý řádek clearingu nese stav zaúčtování (`card_clearings.posting_status` `PENDING` / `POSTED` / `FAILED` / `UNKNOWN`, `posting_attempts`, `posting_last_error`, `posting_next_attempt_at`), zapsaný ve stejné transakci jako clearing. Plánovaný re-driver (`openbank.card-processing.posting-redrive-cron`, výchozí každou minutu, heartbeat `card-processing-ledger-posting-redrive`) zaúčtuje každý nezaúčtovaný řádek znovu s exponenciálním backoffem (`posting-backoff-seconds` 60, strop `posting-backoff-max-seconds` 3600) nejvýše `posting-max-attempts` (12) krát. Opakování stejného clearingu akvirerem ledger stále nezasáhne; jen re-driver.

Po posledním pokusu řádek zůstane `FAILED`/`UNKNOWN` (nikdy se nezahodí) a `openbank.card.processing.ledger.posting.exhausted` stoupne nad nulu: alert `CardLedgerPostingExhausted`. Řádek se opakuje jen dokud není `POSTED` a výsledek zapíše jen pokus, který stále drží claim token řádku, takže pomalý přeběhlý pokus nemůže přepsat novější výsledek ani se započítat. (Claim chrání zápis výsledku, nikoli pomalý HTTP požadavek po vypršení lease. V22 poskytovatele serializuje stejný klíč napříč účetními dny; před zapnutím opakování musí být ověřená.)

`posting_last_error` ukládá pouze řízené diagnostické kódy: `POSTING_FAILED`, `COMPLETION_UNPROVEN`, `AUTHORISATION_NOT_FOUND` nebo `REDRIVE_FAILED`; úspěšné zaúčtování jej vymaže. Neukládá těla odpovědí upstreamu ani zprávy výjimek. Logy diagnostiky zaúčtování ponechávají neprůhledná ID clearingu/autorizace pro korelaci; tato ID zůstávají propojitelná a nejsou anonymizovaná. Stávající varování vypnutého adaptéru stále obsahuje částku, měnu a ID autorizace. Logger zavírání odpovědi sdíleného TransactionClient je mimo tento důkaz aplikačních loggerů a při chybě zavření může stále zaznamenat výjimku.

**Postup u vyčerpaných řádků.** Nikdy neresetovat naslepo - nejdřív prokažte, že transaction-service booking nemá.

1. V databázi card-processing vypište řádky a jejich ledger klíče:

```sql
SELECT id, authorization_id, idempotency_key, posting_status, posting_attempts, posting_last_error,
       'card-clearing:' || authorization_id || ':h:' ||
       translate(rtrim(encode(sha256(convert_to(idempotency_key, 'UTF8')), 'base64'), '='), '+/', '-_') AS ledger_key
FROM card_clearings
WHERE posting_status <> 'POSTED' AND posting_attempts >= 12;
```

2. V databázi transaction-service každý `ledger_key` vyhledejte:

```sql
SELECT idempotency_key, id, status FROM transactions WHERE idempotency_key IN ('<ledger_key>', ...);
-- V22 registry: a retained/orphan claim also prevents automatic retry.
SELECT idempotency_key, transaction_id, booking_date
FROM transaction_idempotency_keys WHERE idempotency_key IN ('<ledger_key>', ...);
```

   - `COMPLETED`: debet JE zaúčtovaný; označte řádek: `UPDATE card_clearings SET posting_status = 'POSTED', posting_transaction_id = '<id>', posting_claim_token = NULL, posting_last_attempt_at = now() WHERE id = '<clearing id>';`
   - žádná transakce ani rezervace v registru: po ověření, že neběží zápis ani opakovaný požadavek, může vlastník schválit opakování: `UPDATE card_clearings SET posting_attempts = 0, posting_next_attempt_at = now(), posting_claim_token = NULL WHERE id IN ('<clearing id>', ...) AND posting_status <> 'POSTED';`
   - `FAILED` / `REVERSED` / `PENDING` / `PROCESSING`: transaction-service drží klíč v tomto stavu a bude jím dál odpovídat; je to incident transaction-service, ne reset - neresetovat.

**Jednorázová rekonciliace clearingů před V5.** V5 označila všechny existující řádky `POSTED` (jejich stav se nikdy nezaznamenával). Poznáte je podle `posting_last_attempt_at IS NULL`. Krok 1 spusťte v databázi card-processing, krok 2 v databázi transaction-service; krok 2 vrací pouze klíče, které chybí v historii transakcí i v globálním registru idempotence (vyžaduje transaction-service V22). Po ověření, že neběží žádný zápis ani opakovaný požadavek, lze schválit jejich opakované odeslání (na řádku nastavte `posting_status = 'UNKNOWN'`, `posting_attempts = 0`, `posting_next_attempt_at = now()`, `posting_claim_token = NULL`).

```sql
-- 1. DB card-processing
SELECT 'card-clearing:' || authorization_id || ':h:' ||
       translate(rtrim(encode(sha256(convert_to(idempotency_key, 'UTF8')), 'base64'), '='), '+/', '-_') AS ledger_key
FROM card_clearings WHERE posting_status = 'POSTED' AND posting_last_attempt_at IS NULL;
-- 2. DB transaction-service: vložte klíče z kroku 1
SELECT k.ledger_key
FROM (VALUES ('<ledger_key>'), ...) AS k(ledger_key)
WHERE NOT EXISTS (
    SELECT 1 FROM transactions t WHERE t.idempotency_key = k.ledger_key
)
AND NOT EXISTS (
    SELECT 1 FROM transaction_idempotency_keys r WHERE r.idempotency_key = k.ledger_key
);
```

Klíče existujících transakcí ve stavu `FAILED`, `REVERSED`, `PENDING` nebo `PROCESSING` se do seznamu k opakování nedostanou a vyžadují výše uvedenou analýzu incidentu; neresetovat je ani nevytvářet nový klíč. Rezervace v registru bez odpovídající transakce také vyžaduje rekonciliaci, není důkazem, že zaúčtování neproběhlo.

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

## Sdílené výchozí konfigurace

Soubor `application.yaml` této služby nedeklaruje bezpečnostní hlavičky HTTP odpovědí (`quarkus.http.header.*`) ani management rozhraní (`quarkus.management.*`: zapnuto, port 8085, host 0.0.0.0, kořenová cesta `/q`). Hodnoty pocházejí z výchozí konfigurace flotily v `openbank-libs-runtime/src/main/resources/application.yaml`, která je na classpath každé služby s nižší prioritou než vlastní soubor služby. Hodnoty jsou shodné s tím, co služba dříve deklarovala sama. Pro změnu jen v této službě nastavte hodnotu v jejím `application.yaml`; soubor služby má přednost.

## Sdílené závislosti sestavení

Soubor `build.gradle.kts` této služby neuvádí závislosti, které sdílí každá Quarkus služba: Quarkus BOM, quarkus-kotlin, RESTEasy Reactive s Jacksonem, SmallRye Health a OpenAPI, OIDC, registr Micrometer Prometheus a config-yaml a testovací knihovny quarkus-junit5, AssertJ a REST Assured. Deklaruje je konvenční plugin `openbank.quarkus-service` v `build-logic`. Výsledná classpath je beze změny. `build.gradle.kts` uvádí jen to, co služba přidává navíc.
