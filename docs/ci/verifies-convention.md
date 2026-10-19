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

## Evidence provenance and limits

The gate selects the newest scheduled `services-ci.yml` run on `main`, regardless of its
conclusion. A pending run is `UNRESOLVED`; it never falls back to an older successful run.
Completed failed runs can still provide per-test results. Missing artifacts, malformed envelopes
and components without attributable results remain unknown, and do not establish a green claim.

Each accepted envelope passes the producer's validator and matches the selected API run's id,
attempt, commit, branch, workflow name and URL. The API selection also checks repository, head
repository, workflow path and scheduled event; those fields are absent from the envelope schema.
For offline use, supply `--evidence PATH --run-metadata RUN.json`, where `RUN.json` is the
independently obtained trusted GitHub workflow-run API response. The metadata file is an explicit
caller trust input, not proof of authenticity on its own. Evidence alone cannot establish provenance.

Results describe that scheduled source commit, which may precede the PR under review. There is no
contractual maximum age and no arbitrary expiry interval: the gate does not establish that changes
after the scheduled source passed. It reports the selected source and validates the producer's
existing five-minute future-clock-skew bound. A current-source enforcement policy remains separate.

A passing `verifies:` test supplements explicit citations; it does not erase a skipped, failed or
missing explicitly cited test. Simple class citations resolve to a unique declaration and package
in the component's test source; ambiguous classes remain unverified. A fully qualified citation can
disambiguate them. Kotlin comments and string examples do not supply declaration annotations. Method tags must
belong to the executed JUnit class, including its nested class path; class tags apply to that
class and its nested tests.
The annotation reader remains a conservative source convention, not a Kotlin compiler or proof
that a test's assertions substantiate the threat-model mitigation.
