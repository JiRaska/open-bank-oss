# OpenBank API Gateway

Local Kong OSS gateway for the OpenBank dockerized stack.

## What it provides

- Kong OSS 3.x in DB-less mode
- Single proxy endpoint on `http://localhost:8000`
- Admin API on `http://localhost:8001`
- Explicit upstream routing for local OpenBank services
- Health pass-through routes for quick smoke checks
- Auth passthrough by default, with JWT/OIDC-compatible guidance for local Keycloak-based flows

## Routed services

| Service | Upstream | Public path prefix | Health path |
|---|---|---|---|
| account | `http://host.docker.internal:8100` | `/api/v1/accounts` | `/health/account/*` |
| ledger | `http://host.docker.internal:8101` | `/api/v1/ledger` | `/health/ledger/*` |
| transaction | `http://host.docker.internal:8102` | `/api/v1/transactions` | `/health/transaction/*` |
| balance | `http://host.docker.internal:8103` | `/api/v1/balances` | `/health/balance/*` |
| consent | `http://host.docker.internal:8106` | `/api/v1/consents` | `/health/consent/*` |
| psd2 | `http://host.docker.internal:8107` | `/api/v1/psd2` | `/health/psd2/*` |
| agent | `http://host.docker.internal:8109` | `/api/v1/agent`, `/api/v1/agents` | `/health/agent/*` |
| party | `http://host.docker.internal:8111` | `/api/v1/parties` | `/health/party/*` |
| notification | `http://host.docker.internal:8112` | `/api/v1/notifications` | `/health/notification/*` |
| audit | `http://host.docker.internal:8113` | `/api/v1/audit` | `/health/audit/*` |
| kyc | `http://host.docker.internal:8114` | `/api/v1/kyc` | `/health/kyc/*` |
| sepa | `http://host.docker.internal:8115` | `/api/v1/sepa` | `/health/sepa/*` |
| domestic | `http://host.docker.internal:8116` | `/api/v1/domestic` | `/health/domestic/*` |
| aml | `http://host.docker.internal:8117` | `/api/v1/aml` | `/health/aml/*` |

`host.docker.internal` keeps the gateway decoupled from `openbank-infra` internals while still reaching services already exposed on the host. The compose file also adds a `host-gateway` mapping for Linux Docker hosts that support it.

## Auth strategy

Default mode is **passthrough**:

- Kong forwards `Authorization`, `X-Request-Id`, and `X-Correlation-Id` headers unchanged.
- Downstream Quarkus services continue to validate Keycloak-issued bearer tokens via OIDC.
- This keeps local behavior close to production without depending on Kong Enterprise-only OIDC features.

Optional JWT placeholders are provided in `.env.example`:

```env
OPENBANK_AUTH_MODE=passthrough
OPENBANK_JWT_ISSUER=http://localhost:8080/realms/openbank
OPENBANK_JWT_AUDIENCE=openbank-services
OPENBANK_JWT_CLAIMS=exp,nbf
```

Use them when you later introduce gateway-level JWT validation with Kong OSS `jwt` plugin plus static consumers/keys, or an external forward-auth service. Until then, passthrough + downstream OIDC validation is the intended local setup.

## Prerequisites

1. Start the OpenBank platform services first.
2. Ensure the upstream ports from `openbank-infra/docker-compose.yml` are reachable on the host.

Typical startup order:

```bash
cd ../openbank-infra
make up-all
```

## Run locally

```bash
cp .env.example .env
make config
make up
```

Useful commands:

| Command | Description |
|---|---|
| `make config` | Validate rendered compose configuration |
| `make up` | Start Kong gateway |
| `make down` | Stop Kong gateway |
| `make logs` | Follow Kong logs |
| `make admin` | Query Kong admin status |
| `make smoke` | Fail on Kong admin issues, warn-only if account downstream is unavailable |

## Smoke checks

Kong admin status:

```bash
curl -s http://localhost:8001/status | python3 -m json.tool
```

Downstream health pass-through:

```bash
curl -i http://localhost:8000/health/account/ready
curl -i http://localhost:8000/health/sepa/ready
curl -i http://localhost:8000/health/aml/ready
```

`make smoke` is intentionally strict only for the Kong Admin API. The account health probe is soft-checked: HTTP `200` prints success, while non-`200` responses such as `502 Bad Gateway` print a warning so partial-stack local runs do not fail just because the downstream account service is stopped.

Proxy API examples:

```bash
curl -i http://localhost:8000/api/v1/accounts/q/health/ready

curl -i \
  -H "Authorization: Bearer <keycloak-access-token>" \
  -H "X-Request-Id: demo-request-001" \
  http://localhost:8000/api/v1/psd2/q/health/ready

curl -i \
  -H "Authorization: Bearer <keycloak-access-token>" \
  http://localhost:8000/api/v1/domestic/q/health/ready
```

If a downstream service is not running, Kong will return `502 Bad Gateway`, which is expected for local partial-stack work. `make smoke` reports this as a warning for the account route instead of failing the target.
