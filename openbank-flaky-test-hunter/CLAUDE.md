# openbank-flaky-test-hunter — agent notes

Charter: ADR-0168, `openbank-libs/governance/agents.yaml` id `flaky-test-hunter`.

## Where its input comes from (read this before touching the scan path)

- **Production input is the Admin UI Test Intelligence snapshot.** After every collection
  the admin BFF (`openbank-admin-ui/src/app/api/test-intelligence/agents/route.ts`) posts a
  privacy-bounded projection of each component's evidence to
  `POST /api/v1/flaky-test-hunter/evidence/analyze`. `FlakyTestHunterService.analyze` turns it
  into findings (current and historical failures, missing evidence kinds, drift). That is the
  single source of truth for the deployed agent.
- **The static source scan (`TestScanAdapter`) has no input in the deployed pod.** It reads
  `*/src/test/kotlin` and `*/build/test-results` under `openbank.flaky-test-hunter.repo-root`
  (default `.`), and the gitops manifest mounts no checkout and no artifacts. The weekly
  `@Scheduled` sweep that drove it was removed on 2026-10-17: it had scanned an empty tree every
  Sunday since the day it shipped, and an empty findings table reads as "nothing to act on".
  The scan stays reachable through `POST /check/trigger` (operator) and the CI-failure webhook
  for a checkout on a developer machine. Do not add the cron back without giving the pod a
  checkout and the test-results artifacts first.
- `RunTrigger.SCHEDULED` is kept but `@Deprecated` and never constructed: the report's trigger is
  part of the Temporal workflow input/result and closed histories are retained 168 h, so removing
  the value would break reading a past weekly run. Nothing is persisted in Postgres. Delete it (and
  its `KNOWN_UNEMITTED` entry in `check-scheduled-trigger-emitted.py`) after 2026-10-31.

## Dispositions

- `GitHubProposalAdapter.openProposalPr` — the one bounded mechanical repair (`: Unit` on an
  expression-body `runBlocking` test in this service's own test tree), behind the ADR-0031 phase-3
  policy gate. Fails closed without `FLAKY_TEST_HUNTER_GITHUB_TOKEN`.
- `GitHubProposalAdapter.openTicket` — everything else: opens a GitHub issue labelled
  `flaky-test` carrying the finding and the diagnosis, reusing the open issue that carries the same
  hidden `<!-- flaky-test-hunter:key=... -->` marker (hash of check type, raw component and file;
  all pages of open issues are read, and an unreadable page opens nothing). Distinct findings of one
  type in one module therefore each get their own issue. Until 2026-10-17 this only logged, so every ticket-only
  disposition recorded `proposalUrl = null` and the HITL queue never saw it. Same token, same
  fail-closed rule.

## Tests worth knowing

- `GitHubProposalAdapterTest` drives both writers against an in-JVM GitHub REST stub (real
  Contents / Refs / Pulls / Issues shapes), including the no-token and non-allow-listed-host
  refusals.
- `TestScanAdapterTest` uses an anonymous `FlakyTestHunterConfig` implementation, so every
  accessor added to the `@ConfigMapping` must be answered there (the mockk siblings would not
  notice).
