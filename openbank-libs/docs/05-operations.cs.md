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
