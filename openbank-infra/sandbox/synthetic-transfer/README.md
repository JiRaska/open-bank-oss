# Scheduled synthetic own-account transfer

The sandbox kustomization renders a suspended UTC CronJob. It uses a dedicated active SYNTHETIC
party, its two owned CZK accounts, and a read-only ledger proof identity. The account-overview
fixture has its own party and pinned balance.

The fixture requires a source balance of at least CZK 0.01. Match the Edge and Ledger account
configuration to the same two fixture IDs, and configure the trusted taint principals at the Edge,
Transaction and Ledger hops. Keep the schedule suspended until the compatible service versions and
fixture are available.

The `journey-synthetic-transfer-fixture` Secret supplies these values.

- `CUSTOMER_BASE`, `CUSTOMER_TOKEN_URL` and `CUSTOMER_CLIENT_SECRET` for the
  `openbank-synthetic-transfer` customer.
- `LEDGER_BASE`, `LEDGER_TOKEN_URL` and `LEDGER_CLIENT_SECRET` for the
  `openbank-synthetic-transfer-verifier` reader.
- `FIXTURE_SOURCE_ACCOUNT` and `FIXTURE_TARGET_ACCOUNT` for the pinned accounts.
- `FIXTURE_SOURCE_BOOKED_CZK` and `FIXTURE_TARGET_BOOKED_CZK` for their expected opening balances.

The outbound and return legs each have one server-derived key per UTC date. The journey observes
both customer histories and one scoped posted synthetic journal per leg. It checks the intermediate
balance deltas before the return and checks that both balances reach baseline. A same-day rerun
uses the visible legs and the same daily keys. Unexpected prior balances or ambiguous evidence
fail the run.

Render with `kubectl kustomize openbank-infra/sandbox/synthetic-transfer`. Run the local
loopback fixture with `python3 -m unittest discover -s openbank-infra/sandbox/synthetic-transfer`.
