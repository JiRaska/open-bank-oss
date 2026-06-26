# Přehled

## Co služba dělá

`openbank-api-gateway` je **jediný north-south vstupní bod** dockerizovaného stacku OpenBank. Jde o proxy [Kong OSS](https://github.com/Kong/kong) `3.7.1` běžící v **DB-less (deklarativním) režimu**, která:

- **Vystavuje jednu veřejnou proxy** na `http://localhost:8000` a směruje každý veřejný prefix cesty na správnou backendovou službu.
- **Mapuje čisté veřejné cesty** (`/api/v1/<resource>`) na lokální upstreamy dostupné na hostiteli (`host.docker.internal:8100–8117`).
- **Poskytuje health pass-through routy** (`/health/<service>/*`) pro rychlé smoke kontroly částečně běžícího stacku.
- **Předává autentizační kontext beze změny** — `Authorization`, `X-Request-Id`, `X-Correlation-Id` — takže navazující služby dál provádějí OIDC validaci.

Je to **tenká směrovací/edge vrstva**, ne byznysová služba. Nevlastní doménový model, schéma ani události.

## Co služba **NEdělá**

- ❌ Standardně neověřuje JWT — autentizace je **passthrough**; navazující Quarkus služba ověřuje Keycloak bearer token (OIDC). Ověřování JWT na úrovni brány je *volitelný, zatím nezapnutý* Kong OSS `jwt` plugin (placeholdery v `.env.example`).
- ❌ Nic neukládá — `KONG_DATABASE=off`; jediný „stav" je deklarativní `kong/kong.yml`.
- ❌ Nevlastní žádnou byznysovou doménu (žádné účty, zůstatky, platby) — pouze přeposílá na služby, které je vlastní.
- ❌ Neemituje doménové události a neprovozuje outbox.
- ❌ Dnes neprovádí rate-limiting, transformace requestů ani service discovery — to je záměrně odloženo (viz [ADR 0051](../../../../docs/adr/0051-generic-service-discovery-and-single-admin-gateway.md) pro plánovaný směr discovery/brány).
- ❌ Není to BFF admin UI — admin UI má vlastní backend-for-frontend proxy; tato brána stojí před veřejnou/bankovní API rovinou.

## Pozice v doméně

```
                       klient / integrátor / TPP
                                 │  HTTPS
                                 ▼
                    ┌──────────────────────────┐
                    │   openbank-api-gateway    │
                    │   (Kong OSS 3.7.1, :8000) │
                    │   passthrough autentizace │
                    └────────────┬──────────────┘
            /api/v1/accounts ────┤    předává Authorization,
            /api/v1/ledger   ────┤    X-Request-Id,
            /api/v1/sepa     ────┤    X-Correlation-Id
            …14 rout…        ────┤
                                 ▼
        ┌──────────── host.docker.internal:8100–8117 ───────────┐
        │ account ledger transaction balance consent psd2 agent │
        │ party notification audit kyc sepa domestic aml        │
        │ (každá si sama ověřuje Keycloak OIDC token)            │
        └────────────────────────────────────────────────────────┘
```

## Klíčové případy užití

Brána **nemá vlastní doménové API**; jejími „případy užití" jsou směrovací pravidla. Žádné doménové události nevznikají.

| Případ užití | Veřejná cesta | Upstream | Událost |
|---|---|---|---|
| Směrování account API | `/api/v1/accounts` | `:8100` | — |
| Směrování ledger API | `/api/v1/ledger` | `:8101` | — |
| Směrování transactions API | `/api/v1/transactions` | `:8102` | — |
| Směrování balances API | `/api/v1/balances` | `:8103` | — |
| Směrování consent API | `/api/v1/consents` | `:8106` | — |
| Směrování PSD2 API | `/api/v1/psd2` | `:8107` | — |
| Směrování agent API | `/api/v1/agent`, `/api/v1/agents` | `:8109` | — |
| Směrování party API | `/api/v1/parties` | `:8111` | — |
| Směrování notification API | `/api/v1/notifications` | `:8112` | — |
| Směrování audit API | `/api/v1/audit` | `:8113` | — |
| Směrování KYC API | `/api/v1/kyc` | `:8114` | — |
| Směrování SEPA API | `/api/v1/sepa` | `:8115` | — |
| Směrování domestic-payment API | `/api/v1/domestic` | `:8116` | — |
| Směrování AML API | `/api/v1/aml` | `:8117` | — |
| Health pass-through | `/health/<service>/*` | upstream `/q/health` | — |

## Kdo bránu volá

- **Externí klienti / integrátoři / PSD2 TPP** — jediný veřejný vstupní bod do bankovní API roviny.
- **Lokální smoke nástroje** — `make smoke` a `curl` proti admin API (`:8001`) a health routám.
- **Operátoři** — Kong Admin API (`:8001/status`) pro liveness samotné brány.

## Závislosti

- **Kong OSS** `3.7.1` (kontejner `openbank-kong`).
- **14 upstream služeb** dostupných na Docker hostiteli (`host.docker.internal:8100–8117`). `host.docker.internal` odděluje bránu od interních detailů `openbank-infra`; compose soubor přidává mapování `host-gateway` pro Linux.
- **Keycloak** — *nepřímo*: tokeny protékají, ale brána v passthrough režimu Keycloak nevolá. Volitelný `jwt` plugin by odkazoval na `OPENBANK_JWT_ISSUER` (`/realms/openbank`).
- **Žádné** PostgreSQL, **žádná** Kafka, **žádný** Redis, **žádné** openbank-libs (není to JVM služba).

## Byznys hodnota

- **Jediné north-south hrdlo** — jedno místo, kam později připojit autentizaci, rate-limiting a observabilitu, místo fan-outu z webové vrstvy na každý backend (topologický problém, který řeší ADR-0051).
- **Stabilní veřejný kontrakt** — klienti vidí čisté cesty `/api/v1/<resource>` oddělené od interního rozložení hostů/portů.
- **Local-first parita** — drží lokální chování blízko produkce (bearer tokeny protékají end-to-end) bez závislosti na OIDC funkcích dostupných jen v Kong Enterprise.
- **Provozní jednoduchost** — DB-less znamená žádné úložiště brány k zálohování, migraci či úniku; celá konfigurace je jeden recenzovatelný soubor v gitu.
