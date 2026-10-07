# 05 — Provoz

## Moduly a build

Kořenový modul `openbank-libs` je kompatibilitní zastřešení: znovu exportuje `openbank-libs-domain` a `openbank-libs-runtime`. Nové služby závisí na modulu, který potřebují. Doménový modul obsahuje primitivy bez frameworku; runtime modul adaptéry Quarkus a `/q/openbank/docs`.

```bash
./gradlew :openbank-libs-domain:build :openbank-libs-runtime:build :openbank-libs:build
./gradlew :openbank-libs-domain:test :openbank-libs-runtime:test
```

Pro integraci služeb používejte kořenový Gradle build. Zdrojem verzí Kotlinu a Quarkusu je `openbank-libs/gradle/libs.versions.toml`; verzi Gradlu určuje wrapper. Aktuální hodnoty čtěte z těchto souborů, nikoli z opsané tabulky.

## Dokumentace služeb

Každá běžící služba s `openbank.quarkus-service` dostane při `processResources` generovaný `00-build.md` ze svého `version.txt` a z verzovaného `openapi.yaml`. Stránka je součástí JARu služby. Ručně psané kapitoly patří do `<service>/src/main/resources/docs/` a balí se s ní. Služba publikuje index a Markdown na `/q/openbank/docs`; index obsahuje release verzi, čas buildu a Git commit.

Gradle úloha `verifyServiceDocs` kontroluje zabalené údaje a v CI běží přes `check`. PR kontrola vyžaduje změnu ručně psané dokumentace při změně produkčních vstupů služby. Generovaná stránka dokládá původ a dostupnost; ručně psané kapitoly vysvětlují chování a musí se revidovat s kódem.

`openbank-libs` nemá vlastní běžící endpoint. Jeho složka `docs/` se kopíruje do image Admin UI. Tato stránka je proto snímkem buildu Admin UI, zatímco stránky služeb pocházejí z jejich běžících image.

## Vydávání a diagnostika

Služby používají vlastní `version.txt` a konfiguraci release-please. Verzi release služby neodvozujte z verze sdíleného modulu. Na nasazené službě čtěte `/api/v1/info`, `/q/openbank/docs` a `/q/openbank/docs/_meta`: ukazují běžící verzi, zdrojový commit a otisk dokumentů. Nedostupný dokument v Admin UI může znamenat nenasazenou službu nebo nedostupný endpoint; nedokazuje absenci dokumentace ve zdrojovém stromu.

## Zahřátí při startu a readiness (#11890)

Každá služba postavená na `openbank-libs-runtime` před hlášením připravenosti zahřeje JVM. Po `StartupEvent` spustí `StartupWarmup` na vlastním vlákně: průchody Jacksonem, `select 1` na reaktivním poolu (pokud existuje) a volání sebe sama na `/api/v1/info` a na `openbank.warmup.protected-path` bez tokenu (v produkci 401, které i tak projde bezpečnostními filtry). `WarmupReadinessCheck` hlásí DOWN, dokud zahřátí neskončí, a nejpozději po `openbank.warmup.max-duration` (výchozí 20s) hlásí UP vždy. Argo Rollouts tak pošle provoz jen na zahřátý pod a žádný pod nezůstane mimo rotaci navždy.

Proč: v sandboxu byl každý pomalý požadavek za 48 h první požadavek po startu podu (1–2 s proti 17–60 ms). Naměřený medián prvního požadavku se zahřátím 967 ms, bez něj 2622 ms.

Konfigurace: `openbank.warmup.enabled` (výchozí true, v `%test` vypnuto), `openbank.warmup.max-duration`, `openbank.warmup.json-iterations`, `openbank.warmup.http-iterations`, `openbank.warmup.protected-path`. Každý krok loguje `warm-up step <name>: <ms>`; selhání kroku se zaloguje a ostatní kroky pokračují.

## Bezpečné XML a TLS odchozích požadavků

Pro nedůvěryhodné XML používejte `SecureXml` z `openbank-libs-domain`; vydávané DOM, SAX, StAX, schema a transformer factory zakazují externí entity a externí DTD. DOM a SAX odmítnou celý DOCTYPE. Produkční parsování nesmí vytvářet vlastní JAXP factory. `SecureXmlTest` a `XxeRejectionTest` ověřují odmítnutí externích entit a DTD.

`SafeHttpClient` z `openbank-libs-runtime` při HTTPS ověřuje certifikační řetězec pomocí JVM a kontroluje hostname. Testovací kořeny stále procházejí platformním PKIX trust managerem; volající nemůže předat vlastní trust manager ani TLS kontext.

Klienti interních služeb s injektovaným JDK `HttpClient` (LLM gateway, obsahový guard, embeddingy, flagd a OPA sidecar) používají `BoundedBodyHandlers.ofString()` místo neomezeného `BodyHandlers.ofString()`. Výchozí limit činí 4 MiB; při jeho překročení handler ukončí odběr odpovědi a volání selže s `IOException`. Externí odchozí provoz patří přes `SafeHttpClient`, který omezuje tělo odpovědi a kontroluje cílové hosty.
