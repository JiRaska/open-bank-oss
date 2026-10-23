# Provoz

## Build a spuštění

```bash
# Build (lokálně) — fast-jar (nikdy uber-jar)
./gradlew :openbank-sepa-payment:quarkusBuild

# Dev mód (live reload; OIDC vypnuto v %dev)
./gradlew :openbank-sepa-payment:quarkusDev

# Lokální brána před PR
./gradlew :openbank-sepa-payment:detekt ktlintCheck koverVerify build

# Obecný build image (host-side gradle, pak Docker COPY quarkus-app/)
openbank-infra/scripts/build-push-service.sh sepa-payment
```

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/sepa-payments/...` | 8115 | business REST API |
| `/api/v1/info` | 8115 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8115 | Swagger UI (`quarkus.swagger-ui.path`) |
| `/q/openapi` | 8085 | OpenAPI spec (management) |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/health` | 8085 | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

> Management rozhraní je povoleno na samostatném portu `8085` s root-path `/q` (`quarkus.management.*`).

## Konfigurace

| Env proměnná | Default | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — **v prod MUSÍ být přepsáno přes Vault** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — **v prod přes Vault** |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokeři |
| `SANCTIONS_SERVICE_URL` | `http://localhost:8123` | REST klient sanctions-service (screening gate) |
| `AML_SERVICE_URL` | `http://localhost:8117` | REST klient aml-service (otevírání případů) |
| `OPA_URL` / `OPA_PATH` / `OPA_TIMEOUT_MS` | `http://localhost:8181` / `/v1/data/openbank/rest/allow` / `500` | OPA sidecar (ADR-0034) |
| `AUTHZ_ENFORCE` | `false` | OPA přepínač advisory→enforce |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata pro `/api/v1/info` |

DB je dostupná přes reaktivní (`postgresql://…/openbank_sepa_payments`) i JDBC (Flyway) URL. Redis na `redis://localhost:6379` zajišťuje idempotenci.

## Health checky

- **Liveness:** `/q/health/live` — běží JVM + ArC. Při selhání restart podu.
- **Readiness:** `/q/health/ready` — DB pool + Kafka producer + Redis.

Poznámka ke **screening gate**: REST klienti sanctions/AML jsou obaleni MicroProfile Fault Tolerance (circuit breaker, retry, timeout — viz `openbank.resilience.*`). Výpadek sanctions **neshodí** readiness; způsobí, že `createPayment` je fail-closed (platba držena v `RECEIVED`).

## FinOps tier (ADR-0057)

**T0 — Always-on.** Jako synchronní money-path skok nesmí sepa-payment polknout cold-start, takže `minReplicas ≥ 1`; nikdy neškáluje na nulu. Tier je odvozen z měřeného chování a gateován declared-vs-measured v CI.

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9 % | Prometheus `up{service="openbank-sepa-payment"}` |
| Latence p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 POST (create vč. sync screen) | < 800 ms | zahrnuje zápis DB + 2× volání sanctions screen |
| Outbox lag | < 10 s | stáří nejstaršího PENDING řádku `sepa_payment_outbox` |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Nevyřešené pokusy o odchozí zaúčtování

`openbank_payment_unsettled_total{rail="SEPA",outcome="..."}` používá v Micrometeru název `openbank.payment.unsettled`. Pět povolených výsledků je `mismatch`, `failed-replay`, `conflict`, `unexpected-status` a `transport-error`; všech pět řad se registruje s nulou. Každý neúspěšný pokus vůči provideru zvýší čítač právě jednou, včetně samostatných opakovaných pokusů; nejde o počet unikátních plateb. Zrušení coroutine ani úspěšná odpovídající reakce čítač nezvyšují. `failed-replay` znamená dekódovanou odpověď FAILED, nikoli důkaz, že požadavek byl replay. Labely neobsahují identitu platby, účtu či uživatele ani text výjimky provideru. Nula znamená absenci pozorovaných neúspěšných pokusů od startu procesu, nikoli důkaz dokončeného zaúčtování.

Odchozí settlement vyžaduje HTTP 201 a transakci COMPLETED s identifikátorem a shodou typu, zdrojového účtu, číselné částky, měny, railu a typu instrukce s požadavkem. Strukturovaný požadavek mapuje `SCT` na `SEPA_CT` a `SCT_INST` na `SEPA_INST`, pro oba používá `ONE_OFF`. Tyto údaje o transakci samy nedokazují dokončení clearingu ani splnění SLA okamžité platby. Klíč `sepa-settlement-<payment-id>` zůstává stejný. Dřívější booking pod tímto klíčem může mít `SEPA_CT` nebo null typ instrukce; neodpovídající reakce zůstává nevyřešená. Z dnešního striktního parsování nevyvozujte, že historický booking neexistuje. Nepřijímejte neodpovídající booking, nepřepisujte jeho historii ani nevytvářejte nový klíč k vynucení opakování. Před nápravou ověřte výsledek schválenou reconciliation cestou pro čtení.

Samotný čítač trvalé stáří neměří. Níže popsané booking a samostatné refund hodiny přidávají sledované časy s výslovně neznámým historickým pokrytím, nikoli SLA limit/alarm či recovery endpoint. Veřejné vyhledání transakce pouze pro čtení podle idempotentního klíče, ostatní rails, alarmy a operátorská integrace zůstávají otevřenými částmi #13623.

### Nevyřešené příchozí vratky (#13623)

Vratka dosáhne terminálního stavu `RETURNED` až po prokázané dokončené reverzní transakci s identifikátorem. HTTP 503 znamená, že je stále nutná reconciliation, nikoli důkaz, že kredit neproběhl. Řádek původní platby uchovává první přijetí vratky, identitu původní transakce, serverem odvozeného aktéra, correlation a důvod; opakování tyto údaje nepřepisuje. Interní dvojice `return_first_received_at` / `return_resolved_at` měří čekání na refund nezávisle na vytvoření platby. Nepřidává veřejný stav platby.

Při reconciliation zachovejte `sepa-reversal-<original-payment-id>` a původní transaction ID. Před opakováním původní vratky ověřte uložený výsledek reverzní transakce. Nejistý výsledek neřešte novou platbou či reversal klíčem. Chybějící původní ID znamená `UNRESOLVED_ORIGINAL_TRANSACTION`; před další finanční akcí ověřte původní ledger identitu schváleným postupem. Absence ID nedokazuje absenci debetu. Změna nepřidává automatické nové zaúčtování, operátorský endpoint, alarm ani recovery scheduler; staré řádky `RETURNED` migrace zpětně necertifikuje jako refundované.

Současné transaction REST API nemá vyhledávání pouze pro čtení podle idempotentního klíče. Tato změna chybějící operátorskou integraci nedoplňuje ani nepovoluje POST jako vyhledávání. Bez existující schválené reconciliation cesty pro čtení zůstává recovery podle klíče blokovaná.

API kontrakt `POST /api/v1/sepa-payments/returns` dokumentuje HTTP 409 při konfliktu stavu nebo persistence, který brání commitu vratky/evidence, a HTTP 503 při neprokázaném dokončení reverzní transakce. Ani jedna odpověď nedokazuje, že ledger reversal neproběhl. Při reconciliation zachovejte původní identitu platby a reversal klíč. Klasifikátor ADR-0048 s oasdiff 1.22.0 hodnotí přidané odpovědi jako aditivní změnu `1.16.0` → `1.17.0`; URL major `/api/v1` se nemění. Verze API kontraktu je nezávislá na automaticky spravované release verzi.

Dokud je return intent nevyřešený, odmítají se konfliktní terminální přechody i po načtení čerstvé revize agregátu. Obecný zápis nesmí intent odstranit, přepsat původní metadata ani jej označit za vyřešený bez atomického zápisu evidence. Databáze omezuje pending intent na existující obnovitelné stavy `PROCESSING`/`COMPLETED`.

První odpověď settlementu může přijít až po uložení intentu bez původního transaction ID. První ID smí připojit pouze explicitní settlement-completion operace workflow po validaci odpovědi adapterem vůči přesnému původnímu požadavku. Krátká transakce znovu načte aktuální agregát, zachová receipt a metadata a atomicky uloží připojené ID i completion outbox řádek. Kompatibilní opožděný callback po dokončení či vratce nevytvoří duplicitní event; konfliktní identita nebo terminální stav se odmítne. Nejde o historický lookup identity ani obecné oprávnění operátora/PATCH přepisovat intent.

Refund-only gauge `openbank_payment_refund_unsettled_oldest_age_seconds` má pevné labely `rail="SEPA"` a `operation="refund"`. Čte nejstarší nevyřešený trvalý receipt vratky, nikoli vytvoření platby, začátek odchozího bookingu nebo stáří clearingu. Před prvním úspěšným dotazem vrací `NaN` (neznámé); úspěšný prázdný dotaz vrací nulu. Při selhání dotazu zůstane poslední úspěšný timestamp/prázdný snapshot a nezaznamená se refresh liveness success. Uložený timestamp dál stárne s časem i při výpadku obnovy. Změna nepřidává SLA limit ani alarm refundu.

### Platby uvíznuté v RECEIVED

To je **záměr**, když screening vrátí REVIEW nebo je nedostupný (fail-closed, ADR-0032).
1. Zkontroluj zdraví sanctions služby: `curl $SANCTIONS_SERVICE_URL/q/health/ready`.
2. Vypiš držené platby: `SELECT payment_id, status, created_at FROM sepa_payments WHERE status='RECEIVED' ORDER BY created_at`.
3. Zkontroluj odpovídající AML případy v aml-service (alert kódy `AML_HOLD` / `SCREENING_UNAVAILABLE`).
4. Držená platba je uvolněna nebo zamítnuta přes životní cyklus AML případu / přechod `PATCH …/status`, nikdy automaticky.

### Rostoucí outbox lag

1. `SELECT count(*) FROM sepa_payment_outbox WHERE status='PENDING'`.
2. Zkontroluj dostupnost Kafky a logy dispatcheru (`SepaPaymentOutboxDispatcher`).
3. Prozkoumej `last_error` / `attempt_count` na FAILED řádcích; po opakovaných selháních publikace může být circuit breaker otevřený.

### Idempotenční replay

Opakovaný `POST` se stejným `Idempotency-Key` vrátí cachovanou odpověď s `X-Idempotency-Replayed: true`. Prázdný klíč vrátí `400`. Neřeš to opakovaným použitím klíče s jiným tělem — generuj nový klíč per logická platba.

### DB / migrace

Nikdy neměň aplikovanou migraci (checksum mismatch → pád při startu). U settlované živé DB použij `QUARKUS_FLYWAY_REPAIR_AT_START=true`, pak odeber.

## Matice verzí tech stacku

Rozřešeno z `libs.versions.toml` při buildu, vystaveno v `/api/v1/info`:

| Komponenta | Verze |
|---|---|
| Kotlin | 2.x |
| Quarkus | 3.x LTS |
| JDK runtime | 25 (Eclipse Temurin) |
| PostgreSQL | 16 |
| Hibernate | Reactive (Panache) |

> Přesné připnuté verze vlastní `openbank-libs` `libs.versions.toml`; tato služba je dědí přes konvenční plugin `openbank.quarkus-service`.

## Deploy / release

Per-service CI pipeline + release-please (per-service komponenta, `version.txt`). Jako **money-path** služba vyžaduje merge **2 schválení + aktuální threat model**. Image se buildí jako fast-jar, host-side gradle pak Docker COPY `quarkus-app/`. CD přes ArgoCD vyzvedne nový image tag z GitOps manifestu.

## Produkční mTLS volání AML služby

Produkční REST klient AML používá pojmenovanou konfiguraci TLS `aml-authority` a listener AML služby na portu 8443, který vyžaduje klientský certifikát. Nasazení dodává klientský certifikát a svazek důvěryhodné privátní CA; vyžaduje se TLS 1.3. Pokud volání AML po nasazení selhává, zkontrolujte společně klientský certifikát a CA, produkční URL REST klienta a síťovou politiku k listeneru. Lokální vývoj a testy nadále používají HTTP fixture; úspěšný lokální HTTP test sám neprokazuje navázání TLS v nasazení.

## Sdílené výchozí konfigurace

Soubor `application.yaml` této služby nedeklaruje bezpečnostní hlavičky HTTP odpovědí (`quarkus.http.header.*`) ani management rozhraní (`quarkus.management.*`: zapnuto, port 8085, host 0.0.0.0, kořenová cesta `/q`). Hodnoty pocházejí z výchozí konfigurace flotily v `openbank-libs-runtime/src/main/resources/application.yaml`, která je na classpath každé služby s nižší prioritou než vlastní soubor služby. Hodnoty jsou shodné s tím, co služba dříve deklarovala sama. Pro změnu jen v této službě nastavte hodnotu v jejím `application.yaml`; soubor služby má přednost.

## Sdílené závislosti sestavení

Soubor `build.gradle.kts` této služby neuvádí závislosti, které sdílí každá Quarkus služba: Quarkus BOM, quarkus-kotlin, RESTEasy Reactive s Jacksonem, SmallRye Health a OpenAPI, OIDC, registr Micrometer Prometheus a config-yaml a testovací knihovny quarkus-junit5, AssertJ a REST Assured. Deklaruje je konvenční plugin `openbank.quarkus-service` v `build-logic`. Výsledná classpath je beze změny. `build.gradle.kts` uvádí jen to, co služba přidává navíc.


### Hodiny nevyřízeného interního bookingu (#13623)

`openbank_payment_booking_tracked_pending_oldest_age_seconds{rail="SEPA"}` měří stáří nejstarší sledované povinnosti zaúčtovat odchozí platbu. Čas vzniká ve stejném commitu jako přijatý přechod VALIDATED → PROCESSING před voláním poskytovatele. Refund má samostatné hodiny. Pouze validovaná odpovídající settlement odpověď uloží důkaz spolu s původním transaction ID, stavem COMPLETED a outbox událostí. Pending refund metadata zůstávají zachována; obecný zápis stavu povinnost nesmaže ani nepotvrdí booking.

`openbank_payment_booking_unknown_coverage{rail="SEPA"}` samostatně počítá řádky bez počátku/důkazu s neznámým pokrytím ve všech stavech. Historické hodnoty zůstávají NULL bez backfillu a bez odvozování z createdAt/submittedAt. Sledované stáří je při neúplném pokrytí dolní mez; neznámé řádky nesmějí potlačit známou nevyřízenou platbu. Před prvním úspěšným dotazem je hodnota NaN, úspěšně zjištěná prázdná množina má nulu. Po chybě dotazu stáří posledního časového údaje dále roste. Samotná nula při neznámém pokrytí není potvrzení bezchybného stavu.

Změna nepřidává SLA limit, alert, nápravnou operaci, nový retry klíč ani lookup endpoint. Zachovejte `sepa-settlement-<paymentId>` a `sepa-reversal-<paymentId>`; nejasnost vyžaduje rekonciliaci, nikoli nový finanční záměr. Testovací endpoint s dodaným ID neprokazuje skutečného poskytovatele ani nasazení.
