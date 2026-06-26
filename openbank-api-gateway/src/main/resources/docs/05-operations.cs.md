# Provoz

## Build a běh

Neexistuje **žádný Gradle build** — brána je připnutý upstream image (`kong:3.7.1`), ne kompilovaný artefakt. Obsluhuje se přes `Makefile` (který obaluje `docker compose`):

| Příkaz | Akce |
|---|---|
| `make init-env` | Vytvoří `.env` z `.env.example`, pokud chybí |
| `make config` | Zvaliduje vyrenderovanou compose konfiguraci |
| `make up` | Spustí Kong bránu (`docker compose up -d`) |
| `make down` | Zastaví bránu |
| `make restart` | `down` a poté `up` |
| `make logs` | Sleduje logy Kongu |
| `make ps` | Zobrazí stav kontejneru |
| `make admin` / `make status` | Dotaz na Kong Admin API `/status` |
| `make smoke` | Striktní na Kong Admin; měkce kontroluje account health routu |

Typický první běh:

```bash
cd ../openbank-infra && make up-all     # nejdřív spusť platformní služby
cp .env.example .env
make config
make up
```

Předpoklad: upstream porty (`8100–8117`) z `openbank-infra` musejí být dostupné na hostiteli.

## Konfigurační přepínače (`.env`)

| Proměnná | Výchozí | Význam |
|---|---|---|
| `KONG_PROXY_PORT` | `8000` | Publikovaný proxy port |
| `KONG_ADMIN_PORT` | `8001` | Publikovaný port admin API |
| `KONG_LOG_LEVEL` | `info` | Log level Kongu |
| `OPENBANK_AUTH_MODE` | `passthrough` | Strategie autentizace (dnes passthrough) |
| `OPENBANK_JWT_ISSUER` | `http://localhost:8080/realms/openbank` | Issuer pro volitelný budoucí `jwt` plugin |
| `OPENBANK_JWT_AUDIENCE` | `openbank-services` | Audience pro volitelný `jwt` plugin |
| `OPENBANK_JWT_CLAIMS` | `exp,nbf` | Claimy k ověření pro volitelný `jwt` plugin |

## FinOps workload tier (ADR-0057)

Dle [ADR 0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md) je **edge/north-south brána fakticky T0 (Always-on)**: je synchronními vstupními dveřmi pro každý příchozí request, takže výpadek po scale-to-zero nebo cold-start je nepřijatelný — neexistuje žádný upstream, který by ji probudil, a KEDA HTTP scale-from-zero by sama potřebovala něco před sebou. Lokální compose definice to odráží přes `restart: unless-stopped` (jeden stále běžící kontejner). V Kubernetes substrátu to mapuje na `minReplicas ≥ 1`, doplněné PodDisruptionBudgetem. Tier je **odvozen z role brány**, ne ručně přiřazen, v souladu s principem deklarováno-vs-naměřeno z ADR.

## Health a proby

Brána vystavuje **vlastní** health povrch Kongu, ne Quarkus `/q/health`:

- **Liveness/readiness brány:** `GET :8001/status` (Admin API). `make smoke` tvrdě selže, pokud to není `200`.
- **Pass-through proby upstreamu:** `GET :8000/health/<service>/ready` → upstream `/q/health/ready`. `make smoke` měkce kontroluje account routu: non-`200` (např. `502`) vypíše varování místo selhání, takže částečně běžící lokální stack je tolerován.

```bash
curl -s http://localhost:8001/status | python3 -m json.tool   # samotná brána
curl -i http://localhost:8000/health/account/ready             # přes bránu → account
```

## SLO (cíl)

| Indikátor | Cíl |
|---|---|
| Dostupnost brány | 99,9 % (vstupní dveře; T0 always-on) |
| Přidaná proxy latence (p95) | < 5 ms nad vlastní latencí upstreamu |
| Korektnost směrování | 100 % položek tabulky rout dosažitelných, když upstream běží |

Pozn.: `read_timeout`/`write_timeout` jsou 30 s a `connect_timeout` 5 s s `retries: 2`, takže pomalý upstream se po retries projeví jako `504`, ne jako zaseknutý klient.

## Runbooky

**`502 Bad Gateway` na routě**
1. Ověř, že upstream běží a poslouchá na svém host portu (`8100–8117`).
2. Z kontejneru brány zkontroluj, že se `host.docker.internal` rozlišuje (Linux potřebuje mapping `host-gateway` — přítomen v `docker-compose.yml`).
3. `make logs` pro inspekci proxy/error logů Kongu.

**`404 no Route matched`**
1. Požadovaná cesta není v `kong/kong.yml`. Ověř, že prefix odpovídá položce v [tabulce rout](./02-architecture.md#úplná-tabulka-rout).
2. Pamatuj, že byznysové routy používají `strip_path: false` (celá cesta předána), health routy `strip_path: true`.

**Změna konfigurace**
1. Uprav `kong/kong.yml` (za běhu je namountován read-only).
2. `make config` pro validaci, poté `make restart` pro reload — DB-less Kong načítá konfiguraci při startu.

**Zabezpečení Admin API (prod)**
- Admin API (`:8001`) nesmí být v produkci veřejně dostupné; omez jej na provozní síť. Viz [06 — Compliance](./06-compliance.md).

## Release a verzování

Není to release-please komponenta (žádný `version.txt`, není v release manifestu). „Verze" = připnutý tag image `kong:3.7.1` plus git SHA souboru `kong/kong.yml`. Upgrady jsou záměrný bump tagu image recenzovaný přes PR, podléhající FinOps politice lifecyklu verzí managed služeb (`rules.yaml: finops`) pro připnuté verze managed služeb.
