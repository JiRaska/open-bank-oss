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

A row can also name an id in its mitigation, in backticks: `` `verifies:admin-ui/E2E-ledger` ``. A
named id must exist and be green, so deleting or skipping the test fails it.

The gate is a ratchet. A finding fails a PR only if it is missing from
`.github/gates/baselines/threat-model-test-evidence.txt` and the PR touches that threat model. On a
push to `main`, on an unrelated PR, or if evidence cannot be read, it stays a warning or notice, so
it catches a claim being written against a test that does not run. It does not catch an existing
green citation going red later: that surfaces as a warning.

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

## Admin UI authenticated deployed lane (#13171)

`openbank-admin-ui/e2e/deployed-session.spec.ts` (job `session-smoke` in
`admin-ui-deployed-auth-gate.yml`) signs in through the real Keycloak login form and declares one id
per money-path screen, `@verifies:admin-ui/E2E-<screen>`, plus `admin-ui/E2E-SSO`. It signs in twice:
as the least-privilege e2e operator, and as the synthetic operator for screens the first cannot see
(`/approvals`, `/delegations`, `/settlements`). `/treasury` admits neither, so its verdict is only
that it refuses them. Any non-GET to the app's origin outside `/api/auth/` and the browser trace export is aborted and fails the
test, and a 5xx from a same-origin API call fails it too.

The result reaches Test Intelligence as journey `admin-money-path-screens`, and
`docs/threat-models/openbank-admin-ui.md` cites these ids, so the gate judges them from the latest
scheduled run of that workflow.
