# Data

## Žádné datové úložiště

`openbank-api-gateway` je **bezstavová**. Provozuje Kong OSS v **DB-less režimu** (`KONG_DATABASE: off` v `docker-compose.yml`), takže:

- ❌ **Žádné PostgreSQL / žádné DB schéma** — neexistuje nic obdobného schématům `account` / `ledger` byznysových služeb.
- ❌ **Žádné Flyway migrace** — není zde adresář `db/migration/` ani version tabulka.
- ❌ **Žádný Kafka topic / žádný outbox** — brána neemituje doménové události.
- ❌ **Žádný Redis / idempotenční cache** — idempotenci vlastní navazující služby.

## Jediný „stav": deklarativní konfigurace

Celá konfigurace je jediný git-trackovaný soubor, načtený při startu a namountovaný **read-only**:

| Artefakt | Role | Měnitelnost |
|---|---|---|
| `kong/kong.yml` | Deklarativní směrovací konfigurace (`_format_version: "3.0"`, `_transform: true`) — 14 služeb + jejich health routy | Za běhu neměnné; změna = redeploy |
| `.env` (z `.env.example`) | Runtime přepínače: porty, log level, auth mode, JWT placeholdery | Jen lokálně; `.env` je v gitignore |
| `docker-compose.yml` | Definice kontejneru (image, listenery, host mapping) | Git-trackováno |

Neexistuje žádná za běhu měnící se datová rovina: requesty protékají a neukládají se.

## Data v přenosu

Brána **přenáší** těla a hlavičky requestů/odpovědí, ale **žádné z nich neperzistuje**. Jediná data, kterých se aktivně dotýká:

| Pole | Zpracování | PII? |
|---|---|---|
| `Authorization: Bearer <jwt>` | Předáno doslovně, neinspektuje se (passthrough) | Citlivé (bearer credential) — nikdy nelogovat token |
| `X-Request-Id`, `X-Correlation-Id` | Předáno doslovně pro tracing | Ne |
| Těla requestů/odpovědí (např. IBANy, party data přes `/api/v1/...`) | Streamována skrz; nebufferují se na disk, neukládají se | **Ano, v přenosu** — PII patří navazujícím službám; brána je jen vodič |

## Logy

Kong píše **access a error logy na stdout/stderr** (`KONG_PROXY_ACCESS_LOG=/dev/stdout`, `KONG_PROXY_ERROR_LOG=/dev/stderr`), zachycené runtime kontejneru. Access logy obsahují řádky requestů (metoda, cesta, status, latence) a IP klienta. Standardně **neobsahují** těla requestů. S hodnotami hlavičky `Authorization` zacházejte jako s tajemstvím — nesmějí se logovat (výchozí formát access logu Kongu nelozuje libovolné hlavičky).

## Retence

| Data | Retence |
|---|---|
| Směrovací konfigurace (`kong/kong.yml`) | Git historie (trvale) |
| Request/odpověď v přenosu | Nedrží se (bezstavová) |
| Access/error logy kontejneru | Dle log pipeline platformy / politiky runtime kontejneru — TBD, řízeno centrálně, ne touto komponentou |

Brána neukládá žádná osobní data, takže na této vrstvě neexistuje povinnost výmazu ani exportu pro subjekt údajů; tyto povinnosti leží na navazujících vlastnících služeb (viz [06 — Compliance](./06-compliance.md)).
