## Summary

Adds a k6 read baseline for `openbank-settlement-service` (evidence-matrix performance lane, #14366):

- **`openbank-settlement-service/src/test/k6/settlement-read-baseline.js`** — k6 read probes against the settlement read endpoints (health-scoped where the stack requires it).
- **`fixtures.sql`** — synthetic seed rows the read probes need (per-service shape).
- **`perf/scenarios.yaml`** entry declaring the lane's safety boundary, blocker and assertions.
- **`.github/workflows/perf-gate.yml`** — wires the subject into the scheduled perf gate discovery.

The lane is **read-only by construction** (GETs only); no service state is created, mutated or deleted, so it is safe against any booted stack. Endpoints behind a named-caller guard or a cross-service dependency are deliberately not probed — a 401/403 measures a rejection, not a handler.

Discovered as an artifact by `openbank-admin-ui/scripts/collect-test-intelligence.mjs` (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`. Thresholds are advisory tripwires (ADR-0243 / issue #334): a breach is recorded as failed evidence in Test Intelligence, calibrated generous until the first stable baseline exists.

## Run

```
BASE_URL=http://localhost:8138 PERF_READ_TOKEN=<token> k6 run openbank-settlement-service/src/test/k6/settlement-read-baseline.js
```

## Test plan

- [x] Scenario wired the same way as the already-merged perf subjects (perf-gate discovery + scenarios.yaml declaration)
- [x] Read-only by construction — safe for the scheduled lane

## Security checklist

- [x] No secrets, tokens, passwords, or PII in code, config, tests, logs. (fixtures.sql holds synthetic data only; the read token comes from env `PERF_READ_TOKEN`)
- [x] No new `@SuppressWarnings`, `as Any`, `@Suppress("...")` without justification (none added).
- [x] No new third-party dependency, OR: dependency review attached (no dependency manifests touched).
- [x] Auth / crypto / payment code changed? → tag `security-review-required`. (none — read-only k6 lane under `src/test/k6/`)
- [x] PII path changed? → tag `gdpr-review-required`. (none)
- [x] Cardholder data path changed? → tag `pci-review-required`. (none — fixtures are synthetic, no PAN processing added)

Refs #14366
