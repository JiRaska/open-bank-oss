# Synthetic payment histories — SDK 1.25.1

Captured on 2026-10-25 by `PaymentHistoryExportTest`, before the Temporal SDK upgrade.
The exporter assertion for SDK 1.25.1 and all scenario assertions passed (1 test,
0 failures, 0 skips). No production server, activity implementation, identity or
payload was used. The initial test clock is 2026-01-01T00:00:00Z.

- Baseline source commit: `694b1ede98b6e3ba10834694e736788ce8440e0e` (Quarkus upgrade branch; payment workflow unchanged).
- `PaymentWorkflowImpl.kt` SHA-256: `3e46a0c39742ce37962f86fff99f0d9119b94ff3626ae3ef8afa2251bd11ec2e`.
- Workflow implementation: the actual pre-upgrade `PaymentWorkflowImpl`.
- Converter: `TemporalClientProducer.workflowClientOptions()`.
- Client and worker identity: `synthetic-payment-history`.
- Queue/namespace: `synthetic-payment-replay`.
- Input/hold/journal UUIDs: fixed synthetic values ending 001/002/003; incoming credit uses the existing zero sentinel.

The embedded service generates synthetic run IDs and event timestamps. Activity
retries are represented by the final activity attempt in history; exporter assertions
also establish the actual invocation counts. Histories are immutable compatibility
inputs. Do not regenerate them with the new workflow or SDK to make a replay pass.

Explicit capture on the baseline revision:

```sh
OPENBANK_EXPORT_PAYMENT_HISTORIES=true ./gradlew :openbank-transaction-service:test --tests '*PaymentHistoryExportTest' --no-build-cache
```

Output goes to `build/payment-history-export/` and refuses overwrite. Inspect decoded
payloads and identities before copying a newly captured corpus into source control.

## SHA-256

- `credit-journal-failure.json`: `9f1d0eaa0e46eafc3d9f5297448d1065622d59784dea22ea9ba644a53913e69d`
- `credit-success.json`: `61e28507e8dd654a41244fbcf3ff93601239ba9bfab6c9387d85f7faba365678`
- `debit-success.json`: `9b69bd3e38f79d1268f61972f3560621db2137066ad10674010dea5ea39e14e8`
- `hold-failure.json`: `3eee86c573b467a901921a308963ada15294ad8a847f06b586a9f7c5d1ff67ec`
- `journal-failure.json`: `402c0cca829b90ea146c507005e6440d3fcafee996d6d6b493ffc7199b438eb6`
- `journal-retry-success.json`: `4f413f26b337d03103bac02aaf3308e6b078c2ed1711e2b269c9a4cb15aa5093`
- `terminal-failure.json`: `a612cb27ffc40b030051e9095581a03b45fa2d7b9177024bf5b6d5ea5983f1de`
