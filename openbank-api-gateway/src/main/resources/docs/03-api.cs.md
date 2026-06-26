# API

> **Žádný `openapi.yaml`.** Brána **nevystavuje vlastní byznysové API** — přeposílá na navazující služby, z nichž každá publikuje svůj vlastní OpenAPI kontrakt. „API" této komponenty je její **proxy povrch** (tabulka rout) plus **Kong Admin API**. Tato sekce dokumentuje obojí tak, jak je nalezeno v `kong/kong.yml`, `docker-compose.yml`, `Makefile` a `README.md`; kontrakt není formalizován jako OpenAPI, protože není co specifického formalizovat.

## Povrchy

| Povrch | Listener | Účel |
|---|---|---|
| **Proxy** | `http://localhost:8000` (`KONG_PROXY_PORT`) | Veřejný provoz; směruje na upstream služby |
| **Admin API** | `http://localhost:8001` (`KONG_ADMIN_PORT`) | Provozní API Kongu (čtení stavu, inspekce konfigurace) |

## Proxy kontrakt

Proxy je pass-through. **Skutečný kontrakt pro `/api/v1/<resource>`** vlastní upstream služba (viz `03-api` dané služby). Brána garantuje pouze:

- **Směrování cest** dle [tabulky rout](./02-architecture.md#úplná-tabulka-rout). Byznysové routy používají `strip_path: false`, takže celá cesta (např. `/api/v1/accounts/{id}`) dorazí na upstream nezměněná.
- **Předávání hlaviček**: `Authorization`, `X-Request-Id`, `X-Correlation-Id` jsou předány beze změny (passthrough autentizace).
- **Verzování**: prefix `/api/v1` je součástí kontraktu upstreamu (URL `major == openbank.api.version`, ADR-0048). Brána verzovací segment nevlastní ani nepřepisuje; předává jej doslovně.

### Health pass-through

Každá služba má health routu pod `/health/<service>` s `strip_path: true`:

```
GET http://localhost:8000/health/account/ready   → upstream :8100 /q/health/ready
GET http://localhost:8000/health/sepa/ready        → upstream :8115 /q/health/ready
GET http://localhost:8000/health/aml/ready          → upstream :8117 /q/health/ready
```

Příklady proxy volání (z `README.md`):

```bash
curl -i http://localhost:8000/api/v1/accounts/q/health/ready

curl -i \
  -H "Authorization: Bearer <keycloak-access-token>" \
  -H "X-Request-Id: demo-request-001" \
  http://localhost:8000/api/v1/psd2/q/health/ready
```

## Admin API

Kong OSS Admin API na `:8001`. Používá se provozně, ne klienty:

```bash
curl -s http://localhost:8001/status | python3 -m json.tool
```

`make admin` / `make status` to obalují. V zabezpečeném nasazení **nesmí** být admin listener veřejně dostupný (viz [05 — Provoz](./05-operations.md) a [06 — Compliance](./06-compliance.md)).

## Idempotence

**Brána ji neřeší.** Idempotence (`Idempotency-Key`) je zodpovědnost navazujících služeb, které vlastní mutace. Brána takovou hlavičku transparentně předá.

## Chybový model

Chyby na vrstvě brány jsou **vlastní chyby Kongu**, ne doménová chybová obálka OpenBank:

| Podmínka | Odpověď | Význam |
|---|---|---|
| Žádná routa neodpovídá cestě | `404` (Kong `{"message":"no Route matched..."}`) | Cesta není v tabulce rout |
| Upstream nedostupný | `502 Bad Gateway` | Backendová služba neběží (očekávané při částečném lokálním stacku) |
| Timeout upstreamu | `504 Gateway Timeout` | Překročení `connect`/`read`/`write` timeoutů (5s / 30s / 30s) po `retries: 2` |
| Selhání autentizace | (passthrough) `401`/`403` vrací **upstream**, ne Kong | Token validován dále |

U byznysových chyb (validace, konflikt, idempotentní replay) se vrací doménová chybová obálka upstreamu beze změny.

## Verzování samotné brány

Brána **není uvolňovaná komponenta** — nemá `version.txt`, není v `release-please-config.json` a neslouží `/api/v1/info` ani `X-Service-Version` (to jsou funkce openbank-libs pro JVM služby). Její verzí je připnutý tag image Kongu `kong:3.7.1` a git historie `kong/kong.yml`.
