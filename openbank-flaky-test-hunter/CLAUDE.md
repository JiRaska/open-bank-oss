# openbank-flaky-test-hunter — agent notes

Charter: ADR-0168, `openbank-libs/governance/agents.yaml` id `flaky-test-hunter`.

## Where its input comes from (read this before touching the scan path)

- **Production input is the Admin UI Test Intelligence snapshot.** After every collection
  the admin BFF (`openbank-admin-ui/src/app/api/test-intelligence/agents/route.ts`) posts a
  privacy-bounded projection of each component's evidence to
  `POST /api/v1/flaky-test-hunter/evidence/analyze`. `FlakyTestHunterService.analyze` turns it
  into findings (current and historical failures, missing evidence kinds, drift). That is the
  source of truth for fleet execution evidence.
- **The static source scan (`TestScanAdapter`) has a bounded deployed input.** It reads
  `*/src/test/kotlin` and `*/build/test-results` under `openbank.flaky-test-hunter.repo-root`
  (default `.`). GitOps supplies only this module's test sources as a read-only sparse public-main
  snapshot taken at pod startup, with its exact revision logged. It supplies no execution reports;
  missing reports remain unknown, never zero executed tests. The operator and CI-failure webhook
  can scan this snapshot; local checkouts can supply a wider source tree and JUnit reports.
  No periodic fleet sweep is enabled. A source snapshot is not live fleet execution evidence.
- `RunTrigger` has no scheduled value. The deprecated `SCHEDULED` was deleted on 2026-11-01,
  once the 168 h Temporal retention after the sweep removal had passed.

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
