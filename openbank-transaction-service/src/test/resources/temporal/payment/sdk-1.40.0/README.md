# Synthetic payment histories — SDK 1.40.0

Captured on 2026-10-25 with `PaymentHistoryExportTest`, using only synthetic activities,
fixed UUIDs and the shared production converter. The actual SDK version is asserted
before capture. This corpus was produced before adding the workflow versioning annotation;
the workflow command sequence is unchanged from the SDK 1.25.1 baseline.

- Baseline source: `bbea252635` plus the SDK 1.40.0 dependency and explicit capture selector.
- Workflow source SHA-256 at capture: `3e46a0c39742ce37962f86fff99f0d9119b94ff3626ae3ef8afa2251bd11ec2e`.
- Initial test clock: 2026-01-01T00:00:00Z.
- Client/worker identity: `synthetic-payment-history`.
- Queue/namespace: `synthetic-payment-replay`.
- Every scenario validates its result, compensation and terminal-write invocation counts.

Capture explicitly with `OPENBANK_EXPORT_PAYMENT_HISTORIES=true` and
`OPENBANK_PAYMENT_CAPTURE_SDK=1.40.0`. Output is exclusively new files under
`build/payment-history-export/sdk-1.40.0/`; never regenerate committed replay fixtures.

The old SDK rejects all seven histories with `Unknown SDK flag:3`. The
`temporalLegacyReplay` task asserts this exact boundary and continues to require
successful replay of the original SDK 1.25.1 corpus. It does not establish SDK rollback
safety, versioned routing, or compatibility of other services' workflows.

## SHA-256

- `credit-journal-failure.json`: `9dea5fba89750af7f5f51ae8ec2c2e62e21a5dc9a23dbda6a5cc121ee8c60c07`
- `credit-success.json`: `103fb78b74490a82938ef42018ce402a415261535b4c55ccd1ac823ee18852d2`
- `debit-success.json`: `d83f7fffd3d3ded80c86d612a83a4bdc4a7b97026492a014aa705056759b52f9`
- `hold-failure.json`: `6a757afa35522371bb4211da23e2422fe87bc65da49a66363a9051968329d903`
- `journal-failure.json`: `9d85e92f50d2e1376061a584d9b337b3a1b76a1b4d67b8e3b8f628c4b09ff675`
- `journal-retry-success.json`: `a23fea4eac05b83ff3e04754c1e1c29d6d67671a9619607f3a96225b829f7374`
- `terminal-failure.json`: `673d61f1dd82ee16a02d801cf199f01437bdbdc3bc83725b210761d6a226441f`
