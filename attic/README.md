# Attic — Planned but not yet implemented

This directory contains **placeholder service directories** that were scaffolded
but contain no implementation yet. They are kept here (rather than deleted) to
preserve the intended service catalogue and to make it obvious where future
work goes.

## Contents

- `planned-services/openbank-authorization-service/` — Fine-grained authorization (OPA / Cedar)
- `planned-services/openbank-card-product-service/` — Card product catalogue (BIN ranges, fees)
- `planned-services/openbank-iam-service/` — Customer identity & lifecycle (separate from Keycloak admin)
- `planned-services/openbank-mobile/` — Flutter mobile app
- `planned-services/openbank-payment-gateway/` — Merchant payment gateway
- `planned-services/openbank-product-catalog-service/` — Banking product catalogue (accounts, loans, savings)
- `planned-services/openbank-regulatory-service/` — Regulatory reporting (CNB, AnaCredit, FATCA/CRS)

When work on one of these starts, move it back to the repository root and add
it to `settings.gradle.kts`.

## Why keep empty dirs?

1. **Catalogue clarity** — readers see the intended scope.
2. **Naming reservation** — prevents accidental name collisions.
3. **Excluded from build** — Gradle does not try to compile empty modules.
