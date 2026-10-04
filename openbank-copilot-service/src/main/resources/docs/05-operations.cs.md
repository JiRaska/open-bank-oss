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
