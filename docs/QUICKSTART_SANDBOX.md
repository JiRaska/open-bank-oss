# OpenBank Sandbox Quickstart

Start at the [admin console](https://admin.open-bank.tech/). Request an appropriate
sandbox identity through [SUPPORT.md](../SUPPORT.md), then sign in using the browser
login flow. Available screens and operations depend on the roles assigned to that identity.

The sandbox is a best-effort development demonstration: no SLA, periodic data resets,
and synthetic data only. The source review on **2026-10-03** did not test live availability
or provision demo credentials. Do not assume a shared demo password or password-grant client.

## Explore with the operator UI

Use an existing synthetic party/account supplied for your access scope. Start with account
and transaction reads, then inspect the available approvals and evidence views. Creating
accounts or initiating payments needs the appropriate authorisation and business prerequisites;
an account in one currency is not automatically a funded account for another currency.

## Read an account through the API

Obtain an API bearer token using the authentication flow and client approved for your
sandbox access. Browser login sessions and API access tokens are not interchangeable.
The following Bash example prompts without echoing the token or placing it in shell history:

```bash
read -rsp 'API bearer token: ' TOKEN; printf '\n'
read -rp 'Authorised synthetic account ID: ' ACCOUNT_ID
curl --fail-with-body --silent --show-error \
  "https://api.open-bank.tech/api/v1/accounts/$ACCOUNT_ID" \
  -H "Authorization: Bearer $TOKEN" | jq .
unset TOKEN
```

The route is declared by the [account API contract](../openbank-account-service/src/main/resources/openapi.yaml).
Actual access still depends on token audience, roles, policy and record scope.

## Build a payment exercise from the current contract

Use the [SEPA API specification](../openbank-sepa-payment/src/main/resources/openapi.yaml)
and the [balance API specification](../openbank-balance-service/src/main/resources/openapi.yaml)
for exact request fields, currency constraints, idempotency headers, error responses and
approval flow. Select funded synthetic debtor data and an approved simulator counterparty.
Follow the returned payment identifiers and status; do not assume a payment ID is a transaction ID.

For repeatable automated scenarios, inspect the
[synthetic journey workflow](../.github/workflows/synthetic-journeys.yml) and
[performance fixtures](../perf/). Their execution requires the configured test identity and environment.

## Troubleshooting

- **401:** verify token expiry, issuer, audience and the configured authentication flow.
- **403:** verify roles, policy and record scope; obtaining a fresh token does not grant access.
- **400/409:** compare the request and business state with the current OpenAPI contract.
- **503 or timeout:** inspect service availability before retrying; preserve idempotency on writes.

Use the [deployment guide](../DEPLOYMENT.md) and service runbooks for operator diagnostics.
Public API documentation is checked into the repository; management endpoints and Swagger UI
availability depend on service configuration and are not promised as public sandbox endpoints.
