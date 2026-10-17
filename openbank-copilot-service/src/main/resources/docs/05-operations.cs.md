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

## Sdílené výchozí konfigurace

Soubor `application.yaml` této služby nedeklaruje bezpečnostní hlavičky HTTP odpovědí (`quarkus.http.header.*`) ani management rozhraní (`quarkus.management.*`: zapnuto, port 8085, host 0.0.0.0, kořenová cesta `/q`). Hodnoty pocházejí z výchozí konfigurace flotily v `openbank-libs-runtime/src/main/resources/application.yaml`, která je na classpath každé služby s nižší prioritou než vlastní soubor služby. Hodnoty jsou shodné s tím, co služba dříve deklarovala sama. Pro změnu jen v této službě nastavte hodnotu v jejím `application.yaml`; soubor služby má přednost.

Meze poolu reaktivního datasource (`quarkus.datasource.reactive.idle-timeout` 5M, `max-lifetime` 30M, `max-size` 20) se zde také již nedeklarují; pocházejí ze stejné výchozí konfigurace flotily a odpovídají hodnotám, které služba dříve deklarovala sama. Nastavení kteréhokoli z nich v `application.yaml` služby výchozí hodnotu stále přepíše.
