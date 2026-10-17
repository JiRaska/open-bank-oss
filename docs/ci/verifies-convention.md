# `verifies:` — linking a test to the requirement it proves

A threat-model mitigation, an ADR decision or an API contract claim is only as true as the test
behind it. "The test class exists" (what `threat-model-claims-resolve` checks) is weaker than "the
test ran and passed in the latest run". `verifies:` makes the link machine-readable so the Test
Intelligence envelope can carry it.

## Id shape

`verifies:<scope>/<ref>` — `<scope>` is the component without the `openbank-` prefix and `-service`
suffix (`clearing`, `sepa-payment`); `<ref>` is the row id in that component's threat model
(`T1`, `R2`). Example: `verifies:clearing/T1`.

## Kotlin (JUnit 5)

Use JUnit's own `@Tag` — no new dependency, nothing in `openbank-libs`:

```kotlin
@Tag("verifies:clearing/T1")
class ClearingSecurityContractTest {
    @Test
    @Tag("verifies:clearing/R2")
    fun `settle is guarded`() { /* ... */ }
}
```

A class-level tag applies to every test in the class; a method-level tag to that test.

## Playwright

Put the token in the test title (the JUnit reporter writes the title into the case `name`):

```ts
test('approval inbox rejects a self-approval @verifies:approvals/A1', async ({ page }) => { /* ... */ })
```

## What reads it

- `.github/scripts/collect-test-run-evidence.py` adds `verifies: [...]` to each `testCases` entry
  (at most 8 ids per case; a malformed id is ignored by the reader and rejected by the validator).
- `.github/scripts/check-threat-model-test-evidence.py` (gate `threat-model-test-evidence`) reads
  the newest scheduled main envelopes and reports `UNVERIFIED` for a threat-model row whose cited
  test class — or whose `verifies:<scope>/<row-id>` tests — is absent from the latest run or not
  green. A component with no envelope is *unknown*, never a verdict.

The gate is advisory until `rules.yaml: threat_model_test_evidence.target_enforce_date` (ADR-0144).
