# Provoz

## Build & běh

```bash
# Build (fast-jar, nikdy uber-jar)
./gradlew :openbank-domestic-payment:quarkusBuild

# Lokální brána před PR
./gradlew :openbank-domestic-payment:detekt :openbank-domestic-payment:ktlintCheck \
          :openbank-domestic-payment:koverVerify :openbank-domestic-payment:build

# Docker (multi-stage; build stage JDK 20, runtime JRE 25 alpine, ZGC)
docker build -f openbank-domestic-payment/Dockerfile -t openbank-domestic-payment .
# nebo generický helper:
openbank-infra/scripts/build-push-service.sh openbank-domestic-payment
```

> Vždy fast-jar (`-Dquarkus.package.jar.type=fast-jar`) a build na hostu — Dockerfile i build-push helper to už dělají. Runtime stage běží jako non-root uživatel `openbank`.

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/domestic-payments/...` | 8116 | business REST API |
| `/api/v1/info` | 8116 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8116 | Swagger UI |
| `/q/openapi` | 8116 | OpenAPI spec |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) — management port |
| `/q/health` | 8085 | liveness + readiness (management port) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management rozhraní je zapnuté na **odděleném portu 8085** (`quarkus.management.enabled=true`, root-path `/q`).

## Serverless tier (ADR-0057)

`domestic-payment` je **money-path** služba. Dle ADR-0057 jsou money-path hot cesty **Tier T0 (always-on)** — `minReplicas ≥ 1`, nikdy se neškálují na nulu: synchronní platební hop nesmí spolknout cold-start a PSD2 očekává dostupnost plateb. Tier je odvozen z měřeného chování a CI ho kontroluje proti deklarovanému; nepřiřazuje se zde ručně.

## Konfigurace

| Env var | Default | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — v produkci **nutno** přepsat (Vault) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokeři |
| `SANCTIONS_SERVICE_URL` | `http://localhost:8123` | REST klient sanctions-service (ADR-0032) |
| `AML_SERVICE_URL` | `http://localhost:8117` | REST klient aml-service |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret |
| `OPA_URL` / `OPA_PATH` / `OPA_TIMEOUT_MS` | `http://localhost:8181` / `/v1/data/openbank/rest/allow` / `500` | OPA sidecar (ADR-0034) |
| `AUTHZ_ENFORCE` | `false` | OPA advisory vs enforce |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata v `/api/v1/info` |

Resilience parametry (`openbank.resilience.*`): circuit breaker (volume 15, ratio 0.5, success 5, delay 10s), retry (3, 500ms, jitter 200ms), timeout 15s. Interval pollu outboxu 5s. Rate limit 100 souběžných requestů.

## Health checks

- **Liveness:** `/q/health/live` (port 8085) — restart podu při selhání.
- **Readiness:** `/q/health/ready` (port 8085) — DB + Kafka producer + Redis.
- Flyway `connect-retries: 10` (interval 2s) toleruje pomalou DB při startu.

## SLO (cíle)

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9 % (T0 always-on) | `up{service="openbank-domestic-payment"}` |
| Latence p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 POST (založení vč. sync screeningu) | < 500 ms | zahrnuje round-trip na sanctions-service |
| Outbox lag | < 10 s | stáří PENDING (poll 5s, dávka 25) |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Trvalé hodiny odchozího zaúčtování

Přijetí do `SENT_TO_CLEARING` uloží první okamžik splatného zaúčtování ve stejné zamčené transakci s kontrolou revize jako stav a outbox událost, před aktivitou settlementu. Retry zachová tento čas i klíč `domestic-settlement-<paymentId>`. Jen přísně odpovídající dokončená odpověď s identitou transakce dovolí atomicky uložit důkaz zaúčtování a identitu společně se stavem `SETTLED` a outbox událostí. Obecný zápis stavu nesmaže čekající hodiny ani nepotvrdí pokrytí.

`openbank_payment_booking_tracked_pending_oldest_age_seconds{rail="DOMESTIC_CZK"}` měří nejstarší sledovaný čekající záznam napříč obchodními stavy. Při neúplném pokrytí jde o dolní mez; před prvním úspěšným dotazem je NaN, prázdná úspěšně načtená sledovaná množina má nulu. Neúspěšný refresh zachová poslední trvalý čas a stáří dále roste. `openbank_payment_booking_unknown_coverage{rail="DOMESTIC_CZK"}` samostatně počítá záznamy bez začátku/důkazu s neznámým pokrytím. Historické záznamy ve všech stavech zůstávají bez backfillu s neznámou proveniencí; obecný zápis obcházející sledování také zůstane neznámý i po přechodu do terminálního stavu. Jde o konzervativní neznámé historické pokrytí, nikoli důkaz nevyřešenosti každého záznamu. Prázdná sledovaná množina při neznámém pokrytí nedokládá vyřešení všeho.

Tyto hodiny se liší od vytvoření platby, clearingu, refundů i původního gauge uvízlých plateb. Alarmuje na ně `DomesticPaymentBookingUnsettled` (1 h, níže). Nepřidávají historické přiřazení, nový retry klíč, lookup/recovery API ani retenční pravidla.


### Nevyřešené výsledky interního zaúčtování

`openbank_payment_unsettled_total{rail="DOMESTIC_CZK",outcome="..."}` počítá neúspěšné pokusy vůči transaction-service. Název v Micrometeru je `openbank.payment.unsettled`. Povolené výsledky jsou `mismatch`, `failed-replay`, `conflict`, `unexpected-status` a `transport-error`; všech pět řad se registruje s nulou před prvním pokusem. Opakované pokusy se počítají samostatně, nejde o počet unikátních plateb. Zrušení coroutine ani úspěšná odpovídající reakce čítač nezvyšují. `failed-replay` označuje dekódovanou odpověď FAILED, nikoli důkaz, že tento požadavek byl replay. Metrika používá label `DOMESTIC_CZK`; požadavek na transakci nadále obsahuje `rail=DOMESTIC` a `instructionType=ONE_OFF`.

Zaúčtování vyžaduje HTTP 201, transakci COMPLETED s identifikátorem a shodu typu, identit účtů, částky, měny, railu a typu instrukce s odeslanými údaji. Neúspěšný či nejistý pokus neposune platbu ze SENT_TO_CLEARING do SETTLED. Existující resilience vůči provideru a politika Temporal activity mohou pokus opakovat; čítač tyto politiky nemění ani znovu nezapočítává vnější obalení výjimky. Při šetření zachovejte `domestic-settlement-<paymentId>`: 409 ani nejistá odpověď nedokazují, že zaúčtování neproběhlo. Před nápravnou akcí ověřte výsledek schváleným reconciliation nástrojem; neměňte klíč ani nepoužívejte nový booking POST jako vyhledávání.

Samotný čítač pokusů nedokládá trvalé stáří ani reconciliation. Samostatné hodiny výše přidávají částečné trvalé pokrytí, nikoli veřejné vyhledání transakce podle idempotentního klíče nebo recovery endpoint; alarm stáří je níže. Existující gauge uvízlých plateb zůstává beze změny a tyto chybějící záruky nedokládá. Nulový čítač ani úspěšný lokální build nedokazují dokončenou reconciliation nebo připravenost nasazení.

### Platby uvíznuté v RECEIVED

Očekávané, když screening vrátí REVIEW (potenciální zásah ≤ 0,85) nebo když byla sanctions-service nedostupná (fail-closed). Postup:
1. Vypsat držené platby: `GET /api/v1/domestic-payments?status=RECEIVED`.
2. Prohlédnout AML případ v `aml-service` (otevřen s alertem `AML_HOLD` / `SCREENING_UNAVAILABLE`).
3. Vyřešit přes `PATCH /{id}/status` → `VALIDATED` (vyčištěno) nebo `REJECTED` s odpovídajícím důvodem. **Neuvolňovat** automaticky.

### Výpadek sanctions-service

Symptom: každé založení vrací `RECEIVED`, AML případy s `SCREENING_UNAVAILABLE`. Toto je fail-closed záměrně. Obnovit sanctions-service, poté přescreenovat držené platby přes ruční review. Zkontrolovat stav circuit-breakeru REST klienta.

### Rostoucí outbox lag

1. `SELECT count(*) FROM domestic_payment_outbox WHERE status='PENDING';`
2. Zkontrolovat dostupnost Kafky a logy dispatcheru (`DomesticPaymentOutboxDispatcher`).
3. Prohlédnout `FAILED` řádky: `SELECT event_id, last_error, attempt_count FROM domestic_payment_outbox WHERE status='FAILED';` — publish je obalený circuit breakerem, takže výpadek Kafky ho rozpojí a samo se zotaví.

### Aktivace a obnova delegovaného utrácení

Receiver delegovaného utrácení je záměrně ve výchozím stavu vypnutý. Aktivujte jej až poté, co
consumer `openbank.delegation.spend-reservation-state` obnoví projekci od `earliest`, lag je nulový
a projekce po terminální revizi následované opožděnou rezervovanou revizí zůstane terminální. U
kompaktovaného proudu se aplikuje nejvyšší `reservationVersion` z payloadu, nikdy poslední pozorovaný
záznam.

Při přechodu na request fingerprint nejprve zastavte vytváření plateb, nechte doběhnout nakonfigurovaný
timeout requestu (`openbank.domestic.resilience.timeout.value-ms`, aktuálně 15 vteřin), přepněte
všechny writery na zdravý nový image a teprve pak tvorbu znovu otevřete. Použijte blue/green přepnutí,
ne rolling interval se smíšenými verzemi: request přijatý starou instancí a zopakovaný proti nové
během rolling deploye je přesně ten nejednoznačný fingerprint case, kterému má tento cutover
předcházet, ne ho dodatečně řešit. Starý nullable fingerprint záměrně vrací `409
IDEMPOTENCY_KEY_REUSED`; není autoritou pro replay. Nikdy nevyzývejte klienta k novému klíči u
nejednoznačného requestu — vytvořili byste tím druhou platbu za jeden záměr. Ověřte stav platby a
vyžadujte reconciliation operátorem, než rozhodnete, jestli už prošla.

Finalizer zapínejte jako poslední. Smí uvolnit jen binding, který domestic-payment atomicky označil
za neexistující; timeouty a neznámé výsledky zůstávají rezervované. Při zapnutí musí existovat jeho
workflow-liveness signál. Pro rollback zastavte nové delegované vytvoření, vyčistěte/reconcileujte
všechny rezervované bindingy a outbox záznamy a až pak vypněte writer — nikdy neobnovujte Redis ani
neprokázaný legacy fingerprint jako autoritu requestu.

### Nelegální přechod stavu (409)

Volající zkusil přechod, který stavový automat zakazuje (viz [03 — API](./03-api.md)). Není retryovatelné; oprav cílový stav volajícího.

## Deploy / release

- Per-service path-scoped CI (buildují se jen změněné služby).
- Release přes release-please z Conventional Commits; needitovat ručně `version.txt` (aktuálně `0.3.0`) ani `CHANGELOG.md`.
- **Money-path:** vyžaduje 2 schválení + threat model (`docs/threat-models/openbank-domestic-payment.md`); nikdy se neauto-merguje.
- CD: ArgoCD vyzvedne bump image tagu v GitOps manifestech.

## Produkční mTLS volání AML služby

Produkční REST klient AML používá pojmenovanou konfiguraci TLS `aml-authority` a listener AML služby na portu 8443, který vyžaduje klientský certifikát. Nasazení dodává klientský certifikát a svazek důvěryhodné privátní CA; vyžaduje se TLS 1.3. Pokud volání AML po nasazení selhává, zkontrolujte společně klientský certifikát a CA, produkční URL REST klienta a síťovou politiku k listeneru. Lokální vývoj a testy nadále používají HTTP fixture; úspěšný lokální HTTP test sám neprokazuje navázání TLS v nasazení.

## Sdílené výchozí konfigurace

Soubor `application.yaml` této služby nedeklaruje bezpečnostní hlavičky HTTP odpovědí (`quarkus.http.header.*`) ani management rozhraní (`quarkus.management.*`: zapnuto, port 8085, host 0.0.0.0, kořenová cesta `/q`). Hodnoty pocházejí z výchozí konfigurace flotily v `openbank-libs-runtime/src/main/resources/application.yaml`, která je na classpath každé služby s nižší prioritou než vlastní soubor služby. Hodnoty jsou shodné s tím, co služba dříve deklarovala sama. Pro změnu jen v této službě nastavte hodnotu v jejím `application.yaml`; soubor služby má přednost.

## Sdílené závislosti sestavení

Soubor `build.gradle.kts` této služby neuvádí závislosti, které sdílí každá Quarkus služba: Quarkus BOM, quarkus-kotlin, RESTEasy Reactive s Jacksonem, SmallRye Health a OpenAPI, OIDC, registr Micrometer Prometheus a config-yaml a testovací knihovny quarkus-junit5, AssertJ a REST Assured. Deklaruje je konvenční plugin `openbank.quarkus-service` v `build-logic`. Výsledná classpath je beze změny. `build.gradle.kts` uvádí jen to, co služba přidává navíc.

### Alarm nezaúčtované platby a re-drive (#13623)

`DomesticPaymentBookingUnsettled` alarmuje, když jsou nejstarší otevřené hodiny bookingu starší než 1 h (`openbank-infra/gitops/components/payments/prometheus-rules-unsettled.yaml`, testy v `openbank-infra/tests/promtool/payment_unsettled_alerts_test.yaml`). Temporal opakuje settlement každých 5 minut po 7 dní se stejným klíčem; to zhojí přechodný výpadek, nikdy deterministickou odpověď. Postup operátora (dotaz do transaction-service podle klíče, pak reconcile, reverse nebo re-drive, včetně resetu již selhaného workflow) je v `docs/runbooks/0032-payment-rail-unsettled-booking.md`.

## Typ platby v pacs.008 (#13663)

Domácí adaptér výslovně ponechává volitelný blok typu platby nepřítomný. Úroveň služby SEPA je kód
EPC, nikoli kód domácího clearingového schématu; nové pole sdílené knihovny z domácího převodu
nedělá SEPA platbu. Výchozí XML bez bloku se nemění.
`DomesticPaymentPriority.INSTANT` stále nemá odpovídající kód v této zprávě: volba úrovně služby
a lokálního instrumentu domácího schématu zůstává nevyřešená. Toto zachování kompatibility
nedokládá, že se okamžitá priorita předává schématu.

## Příznak vynucování fraud rozhodnutí (#4403, ADR-0084 §4.2)

`openbank.domestic.fraud.enforcement-enabled` (env `OPENBANK_DOMESTIC_FRAUD_ENFORCEMENT_ENABLED`) je ve výchozím stavu **vypnutý**. Dokud je vypnutý, každá platba pokračuje beze změny a případné rozhodnutí se pouze započítá do `openbank_fraud_enforcement_decisions_total{decision,mode="shadow"}`. Před zapnutím kalibrujte podle této metriky.

Po zapnutí workflow aktivita `fraudGate` selhává uzavřeně: pokračuje jen skutečné ALLOW, DECLINE platbu zamítne jako `FRAUD_SUSPECTED` a každý jiný verdikt (CHALLENGE, REVIEW, UNKNOWN, jakýkoli syntetický) platbu před odesláním do schématu pozdrží a otevře případ `FRAUD_REVIEW`. Pozdržení uvolní jen signál workflow `resolveFraudHold(RELEASE)`; signál REJECT nebo žádný signál do 48 h platbu zamítne. Signál se při uzavření případu zatím neodesílá, proto příznak nezapínejte, dokud toto napojení neexistuje. Cesta zamítnutí neposílá zákazníkovi žádné oznámení (tipping-off). Rollback: nastavte příznak na `false`; již čekající platby dál čekají na signál nebo timeout.
