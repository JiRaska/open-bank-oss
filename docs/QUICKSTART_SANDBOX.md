# OpenBank Sandbox Quickstart

Live sandbox at **open-bank.tech**. All APIs require a Bearer token from Keycloak.

> ⚠️ This is a **development sandbox** — data resets periodically, SLAs don't apply.

## 1. Get a Bearer token

```bash
# Use the demo user credentials (read-only for most endpoints)
TOKEN=$(curl -s -X POST \
  https://kc.open-bank.tech/realms/openbank/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=openbank-admin-ui" \
  -d "username=demo" \
  -d "password=<sandbox demo password — open a GitHub Discussion or ping @JiRaska to request access>" \
  | jq -r '.access_token')

echo $TOKEN | cut -c1-20  # should print a JWT prefix
```

## 2. Create an account

```bash
curl -s -X POST https://api.open-bank.tech/api/v1/accounts \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-$(date +%s)" \
  -d '{
    "ownerId": "550e8400-e29b-41d4-a716-446655440000",
    "currency": "CZK",
    "accountType": "CURRENT"
  }' | jq .
# Response: { "id": "...", "iban": "CZ...", "currency": "CZK", ... }
```

Save the `iban` and `id` for the next steps.

## 3. Check balance

```bash
ACCOUNT_ID="<id from step 2>"
curl -s https://api.open-bank.tech/api/v1/balances/$ACCOUNT_ID \
  -H "Authorization: Bearer $TOKEN" | jq .
```

## 4. Initiate a SEPA payment

```bash
curl -s -X POST https://api.open-bank.tech/api/v1/sepa-payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: pay-$(date +%s)" \
  -d '{
    "debtorIban": "<iban from step 2>",
    "creditorIban": "DE89370400440532013000",
    "creditorName": "Max Mustermann",
    "amount": "10.00",
    "currency": "EUR",
    "reference": "Invoice 2026-001"
  }' | jq .
# Triggers: sanctions check → AML check → transaction saga → ledger posting
```

## 5. View the transaction

```bash
TX_ID="<id from payment response>"
curl -s https://api.open-bank.tech/api/v1/transactions/$TX_ID \
  -H "Authorization: Bearer $TOKEN" | jq .
```

## 6. Admin UI

Open https://admin.open-bank.tech — log in with the same Keycloak credentials.
You can browse accounts, ledger entries, payment history, and observe DORA/FinOps metrics.

## Sandbox endpoints

| Service | URL |
|---|---|
| Admin UI | https://admin.open-bank.tech |
| Keycloak | https://kc.open-bank.tech |
| Accounts API | https://api.open-bank.tech/api/v1/accounts |
| Balances API | https://api.open-bank.tech/api/v1/balances |
| SEPA Payments | https://api.open-bank.tech/api/v1/sepa-payments |
| Domestic Payments | https://api.open-bank.tech/api/v1/domestic-payments |
| SEPA Instant | https://api.open-bank.tech/api/v1/sepa-instant-payments |
| Transactions | https://api.open-bank.tech/api/v1/transactions |

## OpenAPI specs

Each deployed service exposes `/q/openapi` and `/q/swagger-ui` (internal cluster only).
Run `kubectl port-forward svc/sepa-payment 8115:8115 -n payments` to browse locally.

## Troubleshooting

- **401 Unauthorized** — token expired; repeat step 1.
- **503 Service Unavailable** — payment services may be cold-starting (KEDA scale-to-zero); retry in 10s.
- **Payment stuck in PENDING** — check Loki logs: `{namespace="payments"} |= "saga"` in Grafana.
