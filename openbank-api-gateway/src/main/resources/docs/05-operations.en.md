# Operations

## Build & run

There is **no Gradle build** — the gateway is a pinned upstream image (`kong:3.7.1`), not a compiled artifact. Operate it via the `Makefile` (which wraps `docker compose`):

| Command | Action |
|---|---|
| `make init-env` | Create `.env` from `.env.example` if missing |
| `make config` | Validate the rendered compose configuration |
| `make up` | Start the Kong gateway (`docker compose up -d`) |
| `make down` | Stop the gateway |
| `make restart` | `down` then `up` |
| `make logs` | Follow Kong logs |
| `make ps` | Show container status |
| `make admin` / `make status` | Query Kong Admin API `/status` |
| `make smoke` | Strict on Kong Admin; soft-checks the account health route |

Typical first run:

```bash
cd ../openbank-infra && make up-all     # start the platform services first
cp .env.example .env
make config
make up
```

Prerequisite: the upstream ports (`8100–8117`) from `openbank-infra` must be reachable on the host.

## Configuration knobs (`.env`)

| Var | Default | Meaning |
|---|---|---|
| `KONG_PROXY_PORT` | `8000` | Published proxy port |
| `KONG_ADMIN_PORT` | `8001` | Published admin-API port |
| `KONG_LOG_LEVEL` | `info` | Kong log level |
| `OPENBANK_AUTH_MODE` | `passthrough` | Auth strategy (passthrough today) |
| `OPENBANK_JWT_ISSUER` | `http://localhost:8080/realms/openbank` | Issuer for the optional future `jwt` plugin |
| `OPENBANK_JWT_AUDIENCE` | `openbank-services` | Audience for the optional `jwt` plugin |
| `OPENBANK_JWT_CLAIMS` | `exp,nbf` | Claims to verify for the optional `jwt` plugin |

## FinOps workload tier (ADR-0057)

Per [ADR 0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md), an **edge/north-south gateway is effectively T0 (Always-on)**: it is the synchronous front door for every inbound request, so a scaled-to-zero gap or cold-start is unacceptable — there is no upstream that can wake it, and KEDA HTTP scale-from-zero would itself need something in front. The local compose definition reflects this with `restart: unless-stopped` (single always-running container). In the Kubernetes substrate this maps to `minReplicas ≥ 1`, complemented by a PodDisruptionBudget. The tier is **derived from the gateway's role**, not hand-assigned, consistent with the ADR's declared-vs-measured principle.

## Health & probes

The gateway exposes **Kong's own** health surface, not Quarkus `/q/health`:

- **Liveness/readiness of the gateway:** `GET :8001/status` (Admin API). `make smoke` fails hard if this is not `200`.
- **Upstream pass-through probes:** `GET :8000/health/<service>/ready` → upstream `/q/health/ready`. `make smoke` soft-checks the account route: non-`200` (e.g. `502`) prints a warning rather than failing, so partial-stack local runs are tolerated.

```bash
curl -s http://localhost:8001/status | python3 -m json.tool   # gateway itself
curl -i http://localhost:8000/health/account/ready             # via gateway → account
```

## SLO (target)

| Indicator | Target |
|---|---|
| Gateway availability | 99.9% (front door; T0 always-on) |
| Added proxy latency (p95) | < 5 ms over the upstream's own latency |
| Routing correctness | 100% of route table entries reachable when upstream is up |

Note: `read_timeout`/`write_timeout` are 30 s and `connect_timeout` 5 s with `retries: 2`, so a slow upstream surfaces as `504` after retries rather than a hung client.

## Runbooks

**`502 Bad Gateway` on a route**
1. Confirm the upstream is running and listening on its host port (`8100–8117`).
2. From the gateway container, check `host.docker.internal` resolves (Linux needs the `host-gateway` mapping — present in `docker-compose.yml`).
3. `make logs` to inspect Kong proxy/error logs.

**`404 no Route matched`**
1. The requested path is not in `kong/kong.yml`. Verify the prefix matches an entry in the [route table](./02-architecture.md#full-route-table).
2. Remember business routes use `strip_path: false` (full path forwarded), health routes `strip_path: true`.

**Config change**
1. Edit `kong/kong.yml` (it is mounted read-only at runtime).
2. `make config` to validate, then `make restart` to reload — DB-less Kong reloads config on restart.

**Admin API hardening (prod)**
- The Admin API (`:8001`) must not be publicly reachable in production; restrict it to the operations network. See [06 — Compliance](./06-compliance.md).

## Release & versioning

Not a release-please component (no `version.txt`, not in the release manifest). "Version" = the pinned `kong:3.7.1` image tag plus the git SHA of `kong/kong.yml`. Upgrades are a deliberate image-tag bump reviewed via PR, subject to the FinOps managed-version lifecycle policy (`rules.yaml: finops`) for pinned managed-service versions.
