# Operace

## Build & spuštění

```bash
# Build (lokálně)
./gradlew :openbank-sanctions-service:quarkusBuild

# Dev mode (live reload)
./gradlew :openbank-sanctions-service:quarkusDev

# Docker (z openbank-infra/)
docker compose build sanctions-service
docker compose up -d sanctions-service
```

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/sanctions/...` | 8123 | Screening REST API |
| `/api/v1/sanctions/lists/...` | 8123 | API konfigurace listin |
| `/api/v1/info` | 8123 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8123 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8123 | OpenAPI spec |
| `/q/swagger-ui` | 8123 | Swagger UI (pouze dev) |
| `/q/health` | 8123 | liveness + readiness |
| `/q/metrics` | 8123 | Prometheus |

## Konfigurace

| Env var | Výchozí hodnota | Účel |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | Host DB (v dockeru: `openbank-postgres`) |
| `POSTGRES_PASSWORD` | `openbank_pgpass_local_dev` | Heslo DB — **MUSÍ být přepsáno v prod přes Vault** |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokery |
| `REDIS_URL` | `redis://localhost:6379` | Cache idempotence |
| `KEYCLOAK_URL` | `http://localhost:8080` | OIDC issuer |
| `QUARKUS_LOG_LEVEL` | `INFO` | per-package: `com.openbank.sanctions=DEBUG` |

⬜ **Toto se nekoná.** V `openbank-libs` žádný `BootstrapVerifier` není (`git grep BootstrapVerifier -- '*.kt'` vrací 0) — ADR-0017 ho předepisuje a jeho delivery note uvádí, že dodán nebyl. Start se tedy při dev placeholderu neodmítne, protože to nekontroluje nic.

Co drží heslo mimo prod: `openbank-infra/gitops/components/sanctions-service/sanctions-service.yaml` bere credentials přes `secretKeyRef` z ESO/OpenBao (ADR-0007) a neobsahuje žádný placeholder literál. Vlastnost konfigurace, ne boot-time kontrola (#8426).

## Health checks

Sdílené zahřátí při startu volá `/api/v1/sanctions/pending` bez přihlašovacích údajů, aby prošlo skutečnou neautentizovanou bezpečnostní cestou; čekající přezkumy nenačítá. Kroky pro JSON, volitelnou databázi a HTTP jsou best-effort. Readiness kontrola startup-warmup zůstává DOWN do jejich dokončení nebo uplynutí výchozího limitu 20 sekund. Pokud po nasazení vzroste latence prvního požadavku, zkontrolujte v aplikačních logách selhání kroků a varování o překročení limitu. Cestu nastavuje `openbank.warmup.protected-path`.

- **Liveness:** `/q/health/live` — JVM + ArC running. Restart podu při selhání.
- **Readiness:** `/q/health/ready` — DB connection pool + Kafka producer + Redis.

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9% | `up{service="sanctions-service"}` |
| Latence p95 GET | < 80 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 POST /screen | < 500 ms | včetně fuzzy matching + zápis do DB + outbox insert |
| Zpoždění outboxu | < 2 s | `sanctions_outbox_pending_age_seconds` |
| Chybovost | < 0,1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |
| Čerstvost listin | < 24 h | `sanctions_list_last_updated_age_seconds` |

## Runbooky

### Rostoucí zpoždění outboxu

1. Zkontroluj počet čekajících: `SELECT count(*) FROM openbank_sanctions.sanctions_outbox WHERE status='PENDING'`
2. Ověř dostupnost Kafka brokeru: `kcat -L -b kafka:9092`
3. Zkontroluj logy dispatcheru: `kubectl logs -l app=sanctions-service | grep SanctionsOutboxDispatcher`
4. Pokud > 5k PENDING: zvyš batch size nebo počet vláken dispatcheru přes config map.

### Selhání obnovy sankční listiny

1. Zkontroluj zastaralá `last_updated_at` časová razítka:
   ```sql
   SELECT list_type, last_updated_at, enabled FROM openbank_sanctions.sanctions_lists ORDER BY last_updated_at ASC;
   ```
2. Zkontroluj síťové chyby v logu: `kubectl logs -l app=sanctions-service | grep "refresh"`
3. Spusť manuální obnovu přes API: `POST /api/v1/sanctions/lists/{listType}/refresh`
4. Pokud je zdrojová URL nedostupná, aktualizuj ji přes `PUT /api/v1/sanctions/lists/{id}`.

### Stárnutí referenčních dat jurisdikcí

`sanctions_jurisdictions` se přebírá z vydaných seznamů migrací, takže stárne bez jakékoli chyby.
Viditelné to dělají tři metriky podle `source_list`
(`prometheus-rules-sanctions-jurisdictions.yaml`):

| Série | Význam |
|---|---|
| `openbank_sanctions_jurisdiction_rows` | aktivní řádky; `0` = očekávaný seznam, který není načten (`JurisdictionReferenceDataNotLoaded`, info) |
| `openbank_sanctions_jurisdiction_publication_age_seconds` | stáří nejnovějšího načteného vydání |
| `openbank_sanctions_jurisdiction_verification_age_seconds` | doba od posledního porovnání se zdrojem; nad 150 dní spustí `JurisdictionReferenceDataNotReverified` |

Stáří se počítá z dat uložených v tabulce, ne od startu podu, takže je správné i na čerstvě
spuštěném podu a alert restartem nezmizí. Jak jej uzavřít:

1. Načti aktuální vydání přímo od vydavatele (nikdy zpaměti ani z druhotného shrnutí).
2. Pokud se změnilo, přidej migraci, která vloží nové řádky s `version + 1` a novým
   `published_on` a nahrazeným řádkům nastaví `active = false`.
3. Pokud se nezměnilo, přidej migraci, která jen posune `verified_on` pro daný `source_list`.

#### Seznamy FATF: odkud jsou a kdy je další aktualizace

fatf-gafi.org odmítá automatizované stažení (kontrola botů, kterou se nikdy nepokoušíme obejít).
V24 proto vychází z **oficiálního vládního převzetí** obou prohlášení z 30. 10. 2026 — prozatímních
překladů japonského ministerstva financí (URL příloh a SHA-256 v hlavičce V24) — ověřeného proti
tiskové zprávě amerického FinCEN ze stejného dne. Je to jediná povolená výjimka z pravidla
„nikdy z druhotného shrnutí": oficiální vládní kopie se zaznamenaným hashem, ověřená druhým
vládním zdrojem. Řádek šedého seznamu je `RISK_FACTOR` a výsledek prověření nikdy nemění; řádek
výzvy k akci je `HIGH_RISK` (přezkum), nikdy `EMBARGO`.

- **Alert stáří ověření:** `JurisdictionReferenceDataNotReverified` se pro oba seznamy FATF spustí
  **13. 4. 2027** (ověřeno 14. 11. 2026 + 150 dní; seznam EU, ověřený 11. 11. 2026, 10. 4. 2027).
- **Další povinná aktualizace:** po **plenárním zasedání FATF v únoru 2027** — prohlášení čekejte ve
  druhé polovině února 2027 (okno 15. 2. – 6. 3. 2027; data ověřte v kalendáři FATF). Načtěte je do
  dvou týdnů od zveřejnění, nejpozději před 13. 4. 2027. Prohlášení z října 2026 uvádí, že FATF
  zváží protiopatření vůči Myanmaru, pokud do února 2027 nedojde k pokroku — to je změna opatření
  k posouzení, ne běžné přeověření.

### Velký backlog přezkumů POTENTIAL_HIT

1. Zkontroluj frontu čekajících: `GET /api/v1/sanctions/pending`
2. Upozorni compliance tým — čekající přezkumy starší 24 h by měly být eskalovány.
3. Pokud je backlog způsoben špatně nastaveným prahem (příliš mnoho fuzzy shod), uprav práh skóre `POTENTIAL_HIT` v konfiguraci služby.

### Příliš vysoká míra falešně pozitivních výsledků

Příznak: > 10 % prověření resultuje v `POTENTIAL_HIT`, ale je vyčištěno při přezkumu.

1. Zkontroluj distribuci `overall_score` pro záznamy POTENTIAL_HIT:
   ```sql
   SELECT overall_score, count(*) FROM openbank_sanctions.sanctions_checks
   WHERE status = 'POTENTIAL_HIT' GROUP BY overall_score ORDER BY overall_score;
   ```
2. Zvaž zvýšení prahu `POTENTIAL_HIT` z 0,85 na 0,90 v konfiguraci.
3. Přezkum často čištěných entit pro kandidáty na whitelist.

### Vyčerpání connection poolu DB

Velikost poolu: 20 (výchozí). Při přesycení:

1. Zkontroluj aktivní dotazy: `SELECT * FROM pg_stat_activity WHERE application_name='sanctions-service'`
2. Zabij dlouho běžící: `SELECT pg_cancel_backend(pid)`
3. Zvyš pool: `quarkus.datasource.jdbc.max-size=40` (přes config map).


## Zahřátí při startu (#11890)

sanctions nastavuje `openbank.warmup.protected-path: /api/v1/sanctions/pending`, takže zahřátí z libs-runtime (viz dokumentace openbank-libs, 05-operations) projde bezpečnostní cestou tohoto endpointu dřív, než pod nahlásí připravenost. Naměřeno na této službě: první autentizovaný `GET /api/v1/sanctions/pending` po readiness měl medián 2622 ms bez zahřátí a 967 ms se zahřátím; další požadavky 15–60 ms v obou případech.

## Odmítnutí XML listiny

Importy OFAC a EU FSF používají sdílený zabezpečený SAX parser `SecureXml`. Listina s DOCTYPE se odmítne, včetně externích DTD a deklarací entit. Při chybě parsování import ponechá dosavadní seznam a oznámí `FAILED_KEPT_EXISTING`; prošetřete zdrojovou listinu a nevypínejte ochranu parseru kvůli vynucení obnovy. Odmítnutí ověřují `SanctionsImportServiceTest` a `EuFsfSaxParserTest`.

## Interní mTLS listener v produkci

V produkci služba naslouchá na dvou portech. Nešifrovaný HTTP port (8123) se nemění a dál obsluhuje sondy i všechny stávající volající. Port 8443 je druhý listener pro volání mezi službami: TLS 1.3 s certifikátem z privátní CA platformy a klientský certifikát je povinný. Deployment připojuje certifikát, klíč a CA bundle do `/mnt/internal-tls` ze secretu `sanctions-service-internal-tls`; secret není volitelný, takže bez něj pod nenastartuje.

Klientský certifikát je pouze autentizace transportu. API se na obou portech autentizuje OIDC bearer tokenem a požadavek na 8443 s platným certifikátem a bez tokenu dostane 401, stejně jako na nešifrovaném portu.

`quarkus.http.ssl.client-auth` je pevně dané při sestavení image, v profilu `%prod` v `application.yaml`. Proměnná `QUARKUS_HTTP_SSL_CLIENT_AUTH` v deploymentu jen uvádí stejnou hodnotu a změnit ji nemůže. Lokální vývoj a testy běží bez tohoto listeneru, takže úspěšný lokální běh nedokazuje nasazený handshake.

Pokud volání na 8443 po rolloutu selhávají, zkontrolujte společně: připojený secret a jeho platnost, zda volající předkládá certifikát ze stejné CA, síťovou politiku k portu 8443 a zda volající stále posílá bearer token.

## Sdílené výchozí konfigurace

Soubor `application.yaml` této služby nedeklaruje bezpečnostní hlavičky HTTP odpovědí (`quarkus.http.header.*`) ani management rozhraní (`quarkus.management.*`: zapnuto, port 8085, host 0.0.0.0, kořenová cesta `/q`). Hodnoty pocházejí z výchozí konfigurace flotily v `openbank-libs-runtime/src/main/resources/application.yaml`, která je na classpath každé služby s nižší prioritou než vlastní soubor služby. Hodnoty jsou shodné s tím, co služba dříve deklarovala sama. Pro změnu jen v této službě nastavte hodnotu v jejím `application.yaml`; soubor služby má přednost.

## Sdílené závislosti sestavení

Soubor `build.gradle.kts` této služby neuvádí závislosti, které sdílí každá Quarkus služba: Quarkus BOM, quarkus-kotlin, RESTEasy Reactive s Jacksonem, SmallRye Health a OpenAPI, OIDC, registr Micrometer Prometheus a config-yaml a testovací knihovny quarkus-junit5, AssertJ a REST Assured. Deklaruje je konvenční plugin `openbank.quarkus-service` v `build-logic`. Výsledná classpath je beze změny. `build.gradle.kts` uvádí jen to, co služba přidává navíc.

## Průběžný monitoring (ADR-0376)

| Vlastnost | Výchozí hodnota | Účel |
|---|---|---|
| `openbank.sanctions.monitoring.enabled` (`SANCTIONS_MONITORING_ENABLED`) | `false` | Hlavní vypínač. Vypnuto: registrace odpovídá 503 a žádný běh nic neporovnává |
| `openbank.sanctions.monitoring.delta.max-entries-per-run` | `5` | Počet změněných položek porovnaných za tick |
| `openbank.sanctions.monitoring.delta.max-pending` | `20000` | Délka fronty, nad kterou deltu nahradí úplný sweep |
| `openbank.sanctions.monitoring.delta.max-candidates-per-entry` | `5000` | Počet shod na položku, nad kterým práci převezme sweep |
| `openbank.sanctions.monitoring.sweep.batch-size` | `25` | Počet subjektů vyhodnocených za tick |
| `openbank.sanctions.monitoring.sweep.interval` | `P30D` | Jak často se každý subjekt plně vyhodnotí |
| `openbank.sanctions.monitoring.claim-lease` | `PT15M` | Jak dlouho zůstává převzatý subjekt nebo řádek fronty převzatý |

Metriky (názvy v Prometheu):

| Metrika | Význam |
|---|---|
| `openbank_sanctions_monitoring_enabled` | 1 nebo 0: stav vypínače |
| `openbank_sanctions_monitoring_subjects` | Počet registrovaných subjektů. NaN, dokud běh registr nepřečetl, tedy NaN při vypnutém monitoringu |
| `openbank_sanctions_monitoring_delta_pending` | Změněné položky čekající na porovnání |
| `openbank_sanctions_monitoring_sweep_lag_seconds` | O kolik je nejdéle nevyhodnocený subjekt po termínu; 0, dokud sweep stíhá |
| `openbank_sanctions_monitoring_findings_total{match_kind,detection}` | Zapsané nálezy. Shoda už nalezená nebo krytá vyjasněním se nepočítá |
| `openbank_sanctions_monitoring_runs_total{path,outcome}` | Běhy podle výsledku: `completed`, `resweep_requested`, `disabled`, `failed` |

Dimenzování: sweep pokryje `batch-size` subjektů za minutu, celý cyklus přes N subjektů tedy trvá N / (batch-size × 1440) dní; tato hodnota má zůstat pod `sweep.interval`, jinak `sweep_lag_seconds` roste. Jedna položka delty stojí prohledání trigramových indexů registru, ne průchod registrem: změřeno na milionu syntetických subjektů průměrně 0,3 s (asi 0,05 s pro položku s jedním jménem, asi 0,6 s s pěti) proti 7,3 s při úplném průchodu. Cena roste s registrem a s tím, jak běžné jsou trigramy položky, takže 5 000 změněných položek denně je při této velikosti asi 25 minut jednoho databázového jádra; delta je dimenzována pro registry do nízkých milionů a zdroje do nízkých desítek tisíc změn denně. Nad `delta.max-pending` běh místo toho vyžádá nový sweep. Čísla pocházejí ze syntetických jmen na jednom jádru; před zvýšením `delta.max-entries-per-run` je třeba měření zopakovat na skutečných datech.

### Roste zpoždění sweepu

`sweep_lag_seconds` nad nulou znamená subjekty po termínu. Zkontrolujte `runs_total{path="sweep",outcome="failed"}` a log se zprávou `Sanctions monitoring sweep failed for registry row` (subjekt, který nelze vyhodnotit, se po vypršení převzetí zkouší znovu a pokaždé označí běh jako neúspěšný); jinak zvyšte `sweep.batch-size`.

### Roste fronta delty

Rostoucí `delta_pending` znamená, že změny seznamů přicházejí rychleji, než se porovnávají. Nad `delta.max-pending` služba frontu zahodí a vyhodnotí všechny subjekty znovu (`outcome="resweep_requested"`), což je správné, jen pomalejší. Zvýšení `delta.max-entries-per-run` spotřebuje víc času databáze na tick.
