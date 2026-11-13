# Provoz

## Build a spuštění

```bash
# Build (lokálně) — fast-jar, nikdy uber-jar
./gradlew :openbank-aml-service:quarkusBuild

# Dev režim (live reload, OIDC vypnuté v %dev)
./gradlew :openbank-aml-service:quarkusDev

# Lokální brána před PR
./gradlew detekt ktlintCheck koverVerify build

# Kontejnerový image (jednofázový; recept je .github/workflows/Dockerfile.deploy)
#   build: hostem, ./gradlew build -Dquarkus.package.jar.type=fast-jar
#   runtime: eclipse-temurin:25-jre (glibc, #3354), non-root uživatel, -XX:+UseZGC
```

> Build používá host-side Gradle build, fast-jar packaging (`-Dquarkus.package.jar.type=fast-jar`) dle GitOps pravidel repa — nikdy in-Docker Gradle, nikdy uber-jar.

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/aml/cases/...` | 8117 | byznysové REST API |
| `/api/v1/aml/cases/...` | 8443 | mTLS rozhraní s privátní CA pro volající uvnitř clusteru |
| `/api/v1/info` | 8117 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8117 | Swagger UI |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8117 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management rozhraní je na **samostatném portu 8085** (`quarkus.management.enabled=true`, root-path `/q`). V `%test` je vypnuté.

V produkci port 8443 vyžaduje klientský certifikát důvěryhodný pro privátní CA. Serverový certifikát a svazek důvěry se připojují z Certificate `aml-service-internal-tls`; služba používá TLS 1.3. Rozhraní se nasadí dříve, než na něj přejdou volající. Port 8117 zůstává během přechodu dostupný pro dosavadní readiness a admin UI. Před přepnutím volajících ověř stav Certificate a přístup autorizovaného klienta na 8443; při selhání rozhraní ponech volající na 8117 a obnov předchozí nasazení.

## Konfigurace

| Env proměnná | Výchozí | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — **v produkci MUSÍ být přepsáno přes Vault** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | secret Keycloak klienta — v produkci Vault |
| (datasource) | `postgresql://localhost:5432/openbank_aml` | reaktivní + JDBC URL |
| (kafka) | `localhost:29092` | Kafka bootstrap servers |
| (redis) | `redis://localhost:6379` | idempotenční cache |
| (oidc) | `http://localhost:8080/realms/openbank` | OIDC issuer, klient `openbank-services` |
| `OPA_URL` | `http://localhost:8181` | OPA sidecar PDP (ADR-0034) |
| `AUTHZ_ENFORCE` | `false` | přepne OPA z advisory na enforce |
| `openbank.aml.auto-clear` | `false` | **pouze sandbox** auto-clear onboarding případů — v produkci ponechat `false` |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata v `/api/v1/info` |

Logování je JSON do konzole (`quarkus.log.console.json=true`), člověkem čitelné v `%dev`.

## Odolnost

Konfigurováno pod `openbank.resilience` a na Kafka publisheru:
- **Rate limit:** `max-concurrent-requests=100`.
- **Circuit breaker:** requestVolumeThreshold 10, failureRatio 0.5, successThreshold 5, delay 10s.
- **Retry:** maxRetries 2, delay 300ms, jitter 150ms. **Timeout:** 15s (request), 3s (Kafka publish).
- **Publisher:** `@Bulkhead(1,1)`, `@CircuitBreaker`, `@Retry`, `@Timeout(3000)`.

## Health checky

- **Liveness:** `/q/health/live` — běží JVM + ArC. Při selhání restart podu.
- **Readiness:** `/q/health/ready` — dostupné datasource + Kafka + Redis.

Flyway běží při startu (`migrate-at-start=true`, 10 connect retries × 2s), takže studená DB nezhodí pod okamžitě.

## SLO (cíle)

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99.9% | Prometheus `up{service="aml-service"}` |
| Latence p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 POST (založení případu) | < 300 ms | zápis DB + insert outbox |
| Outbox lag | < 10 s | stáří nejstaršího PENDING řádku `aml_outbox` (dispatcher běží každých 5s) |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Serverless tier (ADR-0057)

`aml-service` nemá **explicitně deklarovaný tier** v `rules.yaml: finops.tiering.declared` a není money-path služba, takže dědí default řízený klasifikátorem (unclassified, dokud není změřen). Outbox dispatcher je `@Scheduled` a Deployment musí běžet na **`replicas: 1`** jako jediný outbox writer — scale-to-zero je tedy omezeno dispatcherem: jakýkoli tiering musí udržet právě jednoho writera teplého (platí guardraily cold-start / single-writer z ADR-0057).

## Runbooky

### Roste outbox lag

1. Spočítej PENDING: `SELECT count(*) FROM aml_outbox WHERE status='PENDING';`
2. Ověř dostupnost Kafky: `kcat -L -b kafka:9092`.
3. Zkontroluj logy dispatcheru: `kubectl logs -l app=aml-service | grep AmlOutboxDispatcher`.
4. Ověř právě jednu replicu (jediný writer). Pokud řádek uvázl ve FAILED s vyčerpanými retry, přejde do DEAD — prozkoumej `last_error`.

### Rozhodnutí odmítnuto s 409

Symptom: `409 conflict` (`InvalidAmlCaseStateTransitionException`). Příčina: požadovaný `targetStatus` není platný přechod z aktuálního stavu, nebo je případ už terminální (`CLEARED`/`BLOCKED`). Akce: znovu načti případ, zvol platný přechod; terminální případy nelze znovu otevřít.

### Onboarding případy se v sandboxu neclearují

Zkontroluj `openbank.aml.auto-clear` — ve výchozím stavu je `false`. V produkci je to záměr (vyžadováno rozhodnutí analytika). V sandboxu nastav na `true`, aby `PartyEventConsumer` onboarding případy auto-clearoval.

### Flyway checksum mismatch při startu

Nikdy needituj aplikovanou migraci. Pokud checksum mismatch blokuje start, nastav `QUARKUS_FLYWAY_REPAIR_AT_START=true` v GitOps env, nech DB ustálit, pak odeber.

## Deploy / release

- Per-service CI pipeline (path-scoped): test → fast-jar build → SBOM → build/push image → ArgoCD zvedne tag.
- **Bump verze:** jakákoli změna pod `src/main/**` bumpuje `version.txt` dle commit typu — ale release axis vlastní release-please; neprováděj ruční bump `version.txt` proti otevřenému Release PR.
- U konfliktů image-tagů v GitOps ber `--ours` (čerstvě sestavený tag), nikdy slepě `--theirs`.

## Testy

- Unit: `AmlCaseServiceTest`, `AmlCaseTest` (stavový automat), `AmlOutboxDispatchTest`.
- Integrační: `AmlOutboxDispatchIT` s `PostgresRedisTestResource` (Testcontainers — izolovaný Postgres + Valkey na test JVM, CI infra sweep #578). V `%test` je scheduler vypnutý, takže IT řídí `dispatchScheduledBatch()` explicitně.
- Coverage je ratchet-only (Kover, ADR-0020) — nikdy nesnižovat.

## Interní mTLS listener

V produkci vyžaduje TLS listener AML služby na portu 8443 klientský certifikát. Dosavadní HTTP listener na portu 8117 zůstává zapnutý pro readiness a objevování v Admin UI; jde o oddělenou cestu od mTLS volání. Při nasazení ověřte, že volající na 8443 předkládají certifikáty důvěryhodné privátní CA a že readiness stále kontroluje 8117. Připravený pod ani úspěšná HTTP sonda nedokazují funkčnost listeneru s ověřením klienta. Požadavek na klientský certifikát je konfigurační volba Quarkusu při buildu, proto patří do produkčního profilu, ne do proměnné prostředí nasazení.

Generovaná politika příchozího provozu nyní povoluje migrovaným namespace pouze port 8443. Admin UI a bezpečnostní skener si ponechávají port 8117; provoz uvnitř namespace AML zůstává povolen. Volajícího, který stále potřebuje HTTP, je třeba určit před zpřísněním jeho pravidla.

## Sdílené výchozí konfigurace

Soubor `application.yaml` této služby nedeklaruje bezpečnostní hlavičky HTTP odpovědí (`quarkus.http.header.*`) ani management rozhraní (`quarkus.management.*`: zapnuto, port 8085, host 0.0.0.0, kořenová cesta `/q`). Hodnoty pocházejí z výchozí konfigurace flotily v `openbank-libs-runtime/src/main/resources/application.yaml`, která je na classpath každé služby s nižší prioritou než vlastní soubor služby. Hodnoty jsou shodné s tím, co služba dříve deklarovala sama. Pro změnu jen v této službě nastavte hodnotu v jejím `application.yaml`; soubor služby má přednost.

## Sdílené závislosti sestavení

Soubor `build.gradle.kts` této služby neuvádí závislosti, které sdílí každá Quarkus služba: Quarkus BOM, quarkus-kotlin, RESTEasy Reactive s Jacksonem, SmallRye Health a OpenAPI, OIDC, registr Micrometer Prometheus a config-yaml a testovací knihovny quarkus-junit5, AssertJ a REST Assured. Deklaruje je konvenční plugin `openbank.quarkus-service` v `build-logic`. Výsledná classpath je beze změny. `build.gradle.kts` uvádí jen to, co služba přidává navíc.

## Příjem případů pozdržených plateb (ADR-0374)

- Kanál `payment-held-in` (env `AML_PAYMENT_HELD_CONSUMER_ENABLED`, ve výchozím stavu **vypnutý**) čte topic událostí každé platební cesty, která přijala řetězec přijetí platby, dnes `openbank.domestic.payment.events`, a z události `PAYMENT_HELD` otevře jeden případ na pozdrženou platbu. Ostatní typy událostí na topicu ignoruje.
- Případ: typ screeningu `TRANSACTION_MONITORING`, riziko `HIGH` (otevře se tedy jako `UNDER_REVIEW`), kód `PAYMENT_HELD`, idempotency klíč `payment-hold:<rail>:<paymentId>`, `transactionId` je platba. Detail uvádí, proč je platba pozdržena, kdy pozdržení vyprší a co odpověděla každá kontrola. Platební cesta, která při uvolnění najde nový důvod pozdržení, požádá o další posouzení téže platby (`review` 2, 3, … v události); každé posouzení otevře vlastní případ pod stejným klíčem s příponou `:<review>` a rozhodnutí se vztahuje jen na důvody uvedené ve vlastním případu.
- Rozhodnutí o případu ukončí pozdržení na platební cestě: `CLEARED` platbu uvolní, `BLOCKED` ji zamítne. `ESCALATED` a `UNDER_REVIEW` ji nechávají čekat a platba se pak zamítne vypršením pozdržení.
- Otevření případu závisí jen na vlastní databázi této služby. Platební cesta, která nedokázala zjistit stav účtu plátce, stranu nepošle; příjem se autority účtů neptá a otevře případ bez strany (`party_id IS NULL`). Obě události případu pak nesou `partyId: null`; odpověď REST, kde je `partyId` povinné, uvádí `partyResolved: false` vedle nulového UUID, které nikoho neoznačuje. Nic jiného chybějící stranu neukládá: každý jiný případ zveřejňuje a vrací uložené `party_id` beze změny, včetně případu, kam platební cesta zapsala id účtu. Id účtu se jako strana nikdy neukládá.
- Stranu doplní z účtu případu úloha dohledání stran, jakmile autorita účtů odpoví. Dotaz, který neuspěje (autorita nedostupná, chybová odpověď, účet, který nezná), zapíše `party_resolution_attempted_at` a každý běh bere nejdřív případy s nejstarším pokusem, takže nedohledatelný případ nezdržuje ty za ním. Nevyřízené případy ukazuje `openbank_aml_cases_party_unresolved` a vypíše je `GET /api/v1/aml/cases?partyResolved=false`. O případu lze rozhodnout i bez dohledané strany.
- Rozhodnutí o případu bez strany se zveřejní jako obvykle, protože je potřebuje platební cesta a ta čte klíč případu, ne stranu. Událost nese `partyId: null`, takže pro konzumenta, který jedná podle strany, neobsahuje nic: party-service nemá stranu, na kterou by ji uplatnila, context-service nemá stranu, pod kterou by ji zaznamenala. `BLOCKED` se pro stranu neztratí. V transakci, která dohledá stranu případu uloženého bez ní, se `BLOCKED` zveřejní ještě jednou na `aml.case.status_changed.v1` se skutečnou stranou: `previousStatus` i `newStatus` jsou `BLOCKED` a událost nenese klíč případu, kód ani id platby, takže ji platební cesta nepovažuje za druhé rozhodnutí. Nic jiného se znovu nezveřejňuje: party-service drží jeden AML stav na stranu a platí poslední událost, takže zopakované `CLEARED` by mohlo znovu aktivovat stranu, kterou mezitím zablokoval jiný případ, kdežto zopakované `BLOCKED` může jen blokovat. Takto zablokovaná strana zůstává zablokovaná, dokud ji někdo neuvolní obvyklým způsobem. Dohledání případu, který nesl id účtu na místě strany, nezveřejní nic, stejně jako dřív. Rozhodnutí a dohledání téhož případu se řadí za sebe na řádku případu: rozhodnutí načtené před dohledáním a zapsané po něm je odmítnuto s 409 a při opakování projde.
- Dokud tato událost není zpracována, `BLOCKED` na případu bez strany zablokoval platbu, ale ne stranu. `openbank_aml_cases_party_unresolved` nad nulou je tedy i počet rozhodnutí, která ke straně možná ještě nedorazila; případ, jehož účet autorita nezná, se ho nedočká nikdy.
- Výmaz (`PARTY_ERASED`) se řídí jedním pravidlem pro každý případ strany: `customer_reference` se změní na `ERASED-<partyId>`, `matched_entity` a `alert_detail` se vymažou, řádek zůstává. Případ, který stranu zatím neuvádí, výmaz najít nemůže, proto se výmaz zaznamená (`aml_erased_party`: id strany a čas) a případ, který úloha dohledání k této straně připojí později, je anonymizován v téže transakci, která jej připojí. Do té doby, a u případu, který se nikdy nedohledá, zůstávají data na případu, který žádnou stranu neuvádí.
- Dokud strana není dohledána, stojí případ mimo filtr `?partyId=` a mimo hlášení podezřelého obchodu pro danou stranu.
- Do `openbank.dlq.aml.payment-held-in` jde jen událost, kterou nelze přečíst (včetně té, která neuvádí účet plátce nebo nese jiný klíč případu než svůj), pozdržení, jehož klíč drží případ, který příjem neotevřel, a neúspěšný zápis do úložiště případů. Záznam tam znamená pozdrženou platbu bez případu: přehrajte jej, nebo o platbě rozhodněte na platební cestě dřív, než pozdržení vyprší.
- Jmenný prostor klíčů `payment-hold:` je vyhrazený: `POST /api/v1/aml/cases` jej (stejně jako `tm:`) odmítne s 400, takže pod klíčem pozdržení nemůže vzniknout jiný případ než ten od příjmu. Každý případ navíc nese záznam, který vstupní bod jej otevřel (`origin`, nastavuje služba, nikdy volající). Případ jiného původu pod klíčem pozdržení tedy může pocházet jen ze sestavení staršího než toto vyhrazení a platí pro něj dvě věci. Nelze o něm rozhodnout: `PUT /decision` odpoví 409, takže z případu, který příjem neotevřel, nemůže pod klíčem pozdržení odejít žádná událost o rozhodnutí a žádná platební cesta podle něj platbu neuvolní. A příjem jej nepovažuje za případ pozdržení: událost pozdržení skončí v dead-letter frontě, zvýší se `openbank_aml_payment_held_foreign_key_claims_total` a řádek ERROR případ pojmenuje. Platba pak zůstane pozdržená a po vypršení se zamítne, pokud o ní nerozhodnete na platební cestě.
- Každé zvýšení `openbank_aml_payment_held_foreign_key_claims_total` stojí za prověření, kdo ten případ vytvořil a zda o něm nebylo rozhodnuto dřív, než toto sestavení běželo: dříve odeslané rozhodnutí pod klíčem pozdržení odsud odvolat nelze a to, zda podle něj platební cesta jednala, je třeba ověřit v jejích záznamech.
- Metrika: `openbank_aml_payment_held_events_total{outcome}` s hodnotami `case_opened`, `ignored` (jiný typ události) a `rejected`.
- Před zapnutím kanálu: každý běžící pod aml-service musí používat sestavení, které umí přečíst případ bez strany, a každý konzument `openbank.aml.events` sestavení, které přijme `partyId: null`. Dokud je kanál vypnutý, žádný případ bez strany nevzniká a na topicu se nic neliší od dřívějška.
- Zapínejte spolu s řetězcem platební cesty, nikdy jen platební cestu: pozdržení, jehož případ se neotevře, skončí vypršením, tedy zamítnutím.
