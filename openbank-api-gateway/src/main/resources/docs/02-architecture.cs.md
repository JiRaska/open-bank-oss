# Architektura

## C4 — kontext a kontejner

```
[ Klient / Integrátor / TPP ]
            │  HTTPS, Authorization: Bearer <jwt>
            ▼
┌────────────────────────────────────────────────────┐
│ Kontejner: openbank-kong (Kong OSS 3.7.1)           │
│   proxy  : 0.0.0.0:8000                              │
│   admin  : 0.0.0.0:8001                              │
│   režim  : DB-less (KONG_DATABASE=off)               │
│   config : /etc/kong/kong.yml (read-only mount)      │
│   pluginy: žádné zapnuté (passthrough)               │
└───────────────┬────────────────────────────────────┘
                │  HTTP, host.docker.internal:<port>
                ▼
   14 OpenBank upstream služeb (Quarkus), každá vlastní
   svou doménu, DB schéma, OIDC validaci a outbox.
```

Brána je záměrně **jeden kontejner bez sidecarů a bez úložiště**. Není zde žádné hexagonální dělení domain/application/adapter — ten vzor (ADR-0002) platí pro Quarkus *byznysové* služby, ne pro konfiguračně řízenou proxy. „Zdrojový kód" této komponenty je její **deklarativní konfigurace**.

## Deployment topologie (lokální)

Definováno v `docker-compose.yml`:

- Image: `kong:3.7.1`, kontejner `openbank-kong`, `restart: unless-stopped`.
- `KONG_DATABASE: off` + `KONG_DECLARATIVE_CONFIG: /etc/kong/kong.yml`.
- Listenery: `KONG_PROXY_LISTEN=0.0.0.0:8000 reuseport backlog=16384`, `KONG_ADMIN_LISTEN=0.0.0.0:8001 reuseport backlog=16384`.
- Logy na stdout/stderr (`KONG_PROXY_ACCESS_LOG=/dev/stdout`, chyby na `/dev/stderr`); `KONG_LOG_LEVEL` výchozí `info`.
- Publikované porty `${KONG_PROXY_PORT:-8000}:8000` a `${KONG_ADMIN_PORT:-8001}:8001`.
- `extra_hosts: host.docker.internal:host-gateway` aby brána dosáhla na služby vystavené na hostiteli (i pro Linux Docker hostitele).
- `kong/kong.yml` namountováno read-only — konfigurace je za běhu neměnná; změny znamenají redeploy.

## Model směrování

`kong/kong.yml` je `_format_version: "3.0"`, `_transform: true`. Každý backend je vyjádřen jako **dvě Kong služby + routy**:

1. **Byznysová routa** — např. služba `account-service` → `host.docker.internal:8100`, `path: /`, routa `account-route` na `paths: [/api/v1/accounts]` s **`strip_path: false`** (celá veřejná cesta se předá upstreamu).
2. **Health routa** — např. `account-health-service` → upstream `path: /q/health`, routa `account-health-route` na `paths: [/health/account]` s **`strip_path: true`** (prefix `/health/account` se odstřihne, takže `/health/account/ready` dopadne na upstream `/q/health/ready`).

Výchozí resilience nastavení aplikovaná na každý upstream:

- `retries: 2`
- `connect_timeout: 5000` ms
- `write_timeout: 30000` ms
- `read_timeout: 30000` ms

### Úplná tabulka rout

| Kong služba | Upstream | Veřejná cesta/y | strip_path |
|---|---|---|---|
| account-service | `:8100` `/` | `/api/v1/accounts` | false |
| account-health-service | `:8100` `/q/health` | `/health/account` | true |
| ledger-service | `:8101` `/` | `/api/v1/ledger` | false |
| transaction-service | `:8102` `/` | `/api/v1/transactions` | false |
| balance-service | `:8103` `/` | `/api/v1/balances` | false |
| consent-service | `:8106` `/` | `/api/v1/consents` | false |
| psd2-service | `:8107` `/` | `/api/v1/psd2` | false |
| agent-service | `:8109` `/` | `/api/v1/agent`, `/api/v1/agents` | false |
| party-service | `:8111` `/` | `/api/v1/parties` | false |
| notification-service | `:8112` `/` | `/api/v1/notifications` | false |
| audit-service | `:8113` `/` | `/api/v1/audit` | false |
| kyc-service | `:8114` `/` | `/api/v1/kyc` | false |
| sepa-service | `:8115` `/` | `/api/v1/sepa` | false |
| domestic-service | `:8116` `/` | `/api/v1/domestic` | false |
| aml-service | `:8117` `/` | `/api/v1/aml` | false |

Každá byznysová služba výše má i odpovídající `*-health-service` na `/health/<service>` (strip_path: true) → upstream `/q/health`.

## Tok autentizace (passthrough)

```
Klient ──Bearer jwt──► Kong ──(hlavičky předány doslovně)──► Quarkus služba
                        │                                      │
                        │ žádný plugin, žádná inspekce tokenu  │ OIDC validace
                        ▼                                      ▼
                   pouze match routy                     401/403 při špatném tokenu
```

- Výchozí `OPENBANK_AUTH_MODE=passthrough`. Kong předává `Authorization`, `X-Request-Id`, `X-Correlation-Id` beze změny; navazující služby vlastní AuthN/AuthZ.
- **Volitelně do budoucna:** Kong OSS `jwt` plugin se statickými consumery/klíči, validující proti `OPENBANK_JWT_ISSUER` (`http://localhost:8080/realms/openbank`), `OPENBANK_JWT_AUDIENCE`, `OPENBANK_JWT_CLAIMS=exp,nbf`. Dnes nezapnuto; drží lokální tok blízko produkce bez Kong Enterprise OIDC.

## Žádný outbox / žádné události

Brána **nemá outbox a neprodukuje Kafka události**. Doménové události vlastní navazující služby. Na této vrstvě tedy neexistuje problém verzování event schématu.

## Klíčová návrhová rozhodnutí

- **DB-less místo DB-backed Kongu** — žádné úložiště k provozování; konfigurace je recenzovatelná v gitu.
- **Explicitní směrování místo service discovery** — jednoduché a inspekovatelné pro lokální stack; dynamická discovery je směr [ADR 0051](../../../../docs/adr/0051-generic-service-discovery-and-single-admin-gateway.md) (control plane přes Kubernetes API), zde zatím nepřijatý.
- **Upstreamy přes `host.docker.internal`** — odděluje bránu od interních detailů `openbank-infra`, přitom dosáhne na služby vystavené na hostiteli.
