# Provoz

## Sestavení a spuštění

```bash
./gradlew :openbank-copilot-service:build
```

HTTP port **8131**. Služba je vypnutá feature flagem `copilot-assistant` / `copilot.enabled`.

## Logování proposal tokenů

Id proposal tokenu (`ProposalToken.id`, `{tokenId}` v
`POST /api/v1/copilot/actions/{tokenId}/confirm`) je jednorázové oprávnění, proto se nikdy nezapisuje
do logu v čitelné podobě. Úložiště tokenů (in-memory i Redis), endpoint pro potvrzení i jeho větev
zamítnutí politikou logují místo něj stabilní, nevratný odkaz:

```
token_ref=ptk_<prvních 12 hex znaků SHA-256(id tokenu)>
```

Stejný token dává vždy stejný `token_ref`, takže řádky úložiště, potvrzení a zamítnutí jednoho návrhu
lze stále spárovat. Pro dohledání řádků ke známému id tokenu spočtěte odkaz přes
`ProposalToken.logRef(id)` a hledejte ten. `ProposalTokenLogRefTest` shodí build, pokud produkční
formát logu znovu zavede surové pole `token=` / `tokenId=`.

Pozn.: v `src/main` dnes nic proposal token nevydává (#5900), takže se tyto řádky objevují jen v
testech, dokud nebude cesta potvrzení zapojena.

Provozní diagnostické logy návrhů a rozhodnutí politik obsahují výsledek a hashovanou referenci tokenu, nikdy identitu zákazníka, názvy nástrojů, důvody rozhodnutí ani deserializovaný obsah. Atribuované auditní události zůstávají samostatným záznamem: zachovávají identitu aktéra, zdroj i pole auditního payloadu potřebná k vysvětlení rozhodnutí. Zprávy výjimek a stack trace se v těchto diagnostických cestách nezapisují, protože mohou obsahovat data požadavku. Pro diagnostiku zůstávají HTTP status, TTL tokenu a vygenerované identifikátory akcí.

## Sdílené závislosti sestavení

Soubor `build.gradle.kts` této služby neuvádí závislosti, které sdílí každá Quarkus služba: Quarkus BOM, quarkus-kotlin, RESTEasy Reactive s Jacksonem, SmallRye Health a OpenAPI, OIDC, registr Micrometer Prometheus a config-yaml a testovací knihovny quarkus-junit5, AssertJ a REST Assured. Deklaruje je konvenční plugin `openbank.quarkus-service` v `build-logic`. Výsledná classpath je beze změny. `build.gradle.kts` uvádí jen to, co služba přidává navíc.
