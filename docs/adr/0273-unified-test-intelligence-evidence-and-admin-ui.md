---
date: 2026-08-22
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [testing, admin-ui, governance, observability]
summary: "OpenBank publishes one provenance-aware test-intelligence snapshot and one consolidated admin UI, separating execution, code coverage, scenario coverage and freshness so missing evidence cannot look healthy."
followup: "#4348 — domestic-payment and device-acknowledged push journeys remain blocked on synthetic identities, taint propagation and the canary device fleet"
---

# ADR-0273 — Unified test intelligence evidence and admin UI

## Context

OpenBank already produces JUnit, Kover, Pitest, Pact, Playwright, deterministic-simulation and k6
evidence. It also has a governed synthetic-journey catalogue. The admin UI does not present that
estate truthfully or completely:

- the JUnit collector and its API each carry the same hand-maintained 26-service list while the
  released-component inventory is more than twice that size;
- a result-directory name is used to infer only `unit` or `integration`, so other test kinds are
  either absent or misclassified;
- `test-results.json` and `quality-report.json` are separate image-build snapshots assembled from
  independently retained artifacts, sometimes from different commits;
- a missing or expired artifact becomes zero tests, which is indistinguishable from a suite that
  ran and discovered zero tests;
- Kover is enforced and uploaded by service CI, but the displayed quality report leaves coverage
  unset;
- performance results and the governed synthetic journeys are not part of the test UI; and
- the composite quality score averages available inputs, allowing an absent money-path control to
  disappear rather than fail visibly.

Four different questions were being collapsed into "test coverage":

1. **execution evidence** — which suites actually ran and what happened;
2. **code coverage** — which lines and branches those runs exercised;
3. **capability and journey coverage** — which independently inventoried banking behaviours have a
   falsifying scenario; and
4. **freshness and provenance** — whether evidence applies to the relevant commit and environment.

They are independent. A high Kover percentage does not establish an end-to-end payment journey,
and a passing journey does not establish broad branch coverage. The UI must keep them separate.

## Decision

We will publish one versioned `test-intelligence.json` snapshot and make `/system/tests` the single
test-intelligence surface in the admin UI.

### D1 — Authoritative inventory, independent denominators

The collector derives released components from repository metadata (`version.txt`) and tooling
modules from explicit configuration. A test source never defines the inventory against which its
own coverage is measured. Capability and money-path denominators remain owned by governance data,
including `rules.yaml` and `journeys.yaml`.

### D2 — One evidence vocabulary

The snapshot schema carries:

- `schemaVersion`, `collectedAt` and source-level collection warnings;
- every component, including components with no evidence;
- explicit evidence kinds: unit, integration, contract, end-to-end, executed trace contract,
  performance, synthetic, mutation and deterministic simulation;
- `passed`, `failed`, `skipped`, `not-run`, `stale`, `blocked` and `unknown` states;
- source provenance: tool, task or artifact, observation time, commit when the producer supplies
  one, and environment; and
- separate code-coverage, performance, contract, mutation and synthetic-journey observations.

Failed browser E2E attempts may also carry metadata for a retained Playwright diagnostic report.
The envelope records only its type, exact run/attempt name, authenticated run URL, seven-day
retention and an explicit sensitive-data warning. Trace, screenshot, video, DOM and request content
never enter the canonical JSON. A diagnostic bundle explains a verdict; its presence is not a
passing verdict and its absence is not rewritten as success. Producer validation and deployment
projection both require the diagnostic URL to equal the envelope's own run URL plus `#artifacts`;
an arbitrary HTTPS link is rejected rather than rendered to an operator.

Absence is not zero. `0 tests executed` is a valid observed result; `not-run` means no applicable
artifact was collected. Missing and stale required evidence are attention states, never silently
excluded from an average.

The fleet totals and history count both `unknown` and unresolved (`unknown`, `not-run`, `blocked`)
observations explicitly. A component may have other passing suites and still contribute unresolved
evidence; the assurance map remains neutral rather than turning an unresolved contract, performance
run or control verdict green.

History discovery asks the owning Admin UI deployment workflow's last 100 successful `main` runs
for artifacts and stops after 30 unique snapshots. It never scans the noisy repository-wide
artifact stream, where fleet builds make snapshot density arbitrarily sparse. The run ceiling
bounds API cost, and any shorter history stays visibly shorter rather than being backfilled or
inferred.

Every reusable service build also emits `test-intelligence-run.schema.json` v1. Its run identity is
the GitHub run id plus attempt, and it carries commit, branch, workflow, URL and observation time.
The envelope is the producer contract owned in `openbank-libs/governance`; raw JUnit and Kover are
compatibility inputs, not the long-term API.

### D2a — Test infrastructure is observed runtime evidence

An integration suite declaring PostgreSQL, Redpanda or Valkey is not considered runtime-proven merely
because a dependency or `*TestResource` source file exists. `openbank-libs-testing` resources append
secret-free `started` and `stopped` observations from the test JVM. In addition, each isolated service
CI job observes start/die events on its own Docker daemon, which proves the lifecycle of existing
service-local TestResource variants without requiring an unsafe fleet-wide rewrite. The envelope keeps
declared and observed infrastructure separately so unavailable Docker, an aborted Quarkus boot, and a
completed container-backed integration run cannot collapse into the same state.

Mapped ports, hosts, credentials and container ids are forbidden from this evidence. During the
expand phase, service-local TestResource implementations remain compatible and receive job-scoped
runtime proof from CI; migrating them to shared resources remains desirable because it removes
duplicated lifecycle/configuration logic and also records proof outside that CI observer. New shared
topologies belong in `openbank-libs-testing`, not another service-local copy.

### D3 — Immutable bounded history, not a premature service

The delivery remains a build-time, read-only snapshot because that is the existing
operational ownership and deployment seam. The collector composes staged CI artifacts and governed
repository data before the admin UI image is built. The BFF serves the new report without running
tests or reaching GitHub at request time.

Every service attempt uploads its run envelope under a run-and-attempt-unique artifact name with
30-day retention; a stable per-service artifact remains the compatibility projection used to build
the latest fleet view. Each successful admin deployment also uploads its completed fleet snapshot under a run-unique artifact
name. The next deployment composes up to 30 prior immutable fleet snapshots into a bounded trend and the
artifacts expire after 30 days. This is deployment history, not a claim of permanent per-test-run
storage. It has an owner, explicit retention and no mutation endpoint. A database and live ingestion
API remain unwarranted until per-run retention, backup/restore and producer authentication have a
named operational owner.

### D4 — Expand and migrate without a destructive contraction

Migration follows these stages:

1. **Expand:** generate and serve `test-intelligence.json`; keep `/api/test-results` and
   `/api/quality-report` working.
2. **Migrate:** make `/system/tests` consume only `/api/test-intelligence`, remove the hidden legacy
   testing implementation from the DevOps page and keep legacy APIs for external compatibility.
3. **Verify:** compare legacy totals against the corresponding new JUnit projection and test empty,
   malformed, partial and stale inputs.
4. **Contract later:** remove legacy files and APIs only in a separate change after all readers are
   migrated and at least one deployed compatibility window has elapsed.

Rollback is configuration-only at the UI reader: restore the legacy fetches while the old reports
remain bundled. No data is deleted in this ADR's delivery.

### D5 — Consolidated operator experience

The consolidated page exposes:

- posture: inventory coverage, failing evidence, missing evidence and freshness;
- service matrix: services by evidence kind, with no-evidence cells visible;
- execution: unit and integration totals with skipped and error counts;
- code coverage: Kover line and branch observations, never labelled scenario coverage;
- contracts and mutation results;
- performance observations and their measured denominator;
- synthetic journeys with status, active or target schedule, severity, covered services,
  falsification statement and blocker, enriched at request time from the already-governed
  kube-state-metrics Prometheus signals; planned cadence is never represented as a running scheduler;
- a 30-snapshot evidence trend plus immutable service run/attempt history which keeps missing,
  failing and runtime-proof denominators visible;
- declared Testcontainers topology alongside observed start/stop lifecycle proof; and
- explicit collection warnings and observation timestamps.

The existing composite quality score is retired from the primary UI. No single score may average
away a missing required control.

### D6 — Acceptance and stop condition

The UI slice is complete when:

- all released components are inventoried without a duplicated source list;
- the report distinguishes missing evidence from an observed zero;
- JUnit, Kover, Pact, Pitest, performance and governed synthetic-journey inputs are represented;
- the active and planned synthetic journeys render with schedules and blockers;
- `/system/tests` uses one API and legacy APIs still pass their compatibility tests;
- focused script, route, component, type, lint and build checks pass; and
- immutable deployment history is retained without a scheduler, database or new microservice.

The ecosystem delivery is complete only when:

- every reusable service CI invocation emits a schema-valid per-attempt run envelope even on failure;
- Testcontainers-backed suites expose declared topology and runtime lifecycle proof;
- service-local TestResource debt is either migrated to `openbank-libs-testing` or explicitly
  instrumented, and governance prevents new unobserved variants;
- CI, performance workflows and sandbox journeys publish compatible provenance rather than being
  reverse-engineered only at admin-image build time;
- an enforced, falsifiable fleet gate protects schema → producer → runtime → projection wiring; and
- the admin UI renders both test outcomes and the runtime on which integration claims depend.

Per-test observations use the same immutable, bounded run envelopes. The producer hashes
`component + kind + class + definition name` into a stable fingerprint, strips parameter values and
never exports failure messages. The deployment projection can therefore show same-commit pass/fail
transitions, failure rate, duration waste and CODEOWNERS ownership without introducing a database or
publishing customer-like fixtures. Expiry means "no longer retained", not "stable". Automated issue
creation and automated test skipping are not claimed.

### D7 — Animated evidence map and bounded AI agents

The page visualises the testing system as a seven-stage animated evidence journey: change intent,
deterministic CI proof, Testcontainers runtime reality, performance and sandbox challenge, runtime
observation including consent-gated mobile RUM, bounded AI reasoning, and accountable human
decision. Each keyboard-accessible stage teaches both what its evidence proves and the claim it must
never be stretched to support. Live fleet coverage, observed container starts, active synthetics,
mobile-RUM arrival and HITL mode are projected into the same canvas without collapsing them into one
score. Motion is disabled when the operator requests reduced motion; the mobile layout becomes a
scrollable narrative rather than shrinking the system into an unreadable diagram.

AI is an interpretation layer, never an evidence producer. The existing flaky-test-hunter reads its
own authorised sources, detects silent or unstable-test patterns, uses the governed LLM gateway to
diagnose a finding and may prepare a reviewable proposal. For runtime snapshot analysis, an admin
explicitly starts analysis in the UI; the BFF reads the server-side snapshot and relays only component,
money-path, evidence-state and test-infrastructure proof fields under that operator's bearer token.
It never forwards source paths or accepts a browser-supplied verdict. Finding ids are deterministic per
snapshot/component/check, repeated requests are idempotent, and this path persists diagnosis without
opening a ticket or PR. The UI renders the agent as unavailable without changing measured facts. Future DevOps
and root-cause agents may consume the same report, but they cannot rewrite a run verdict, lower a
gate, approve their own proposal or turn missing evidence into a prediction.

### D8 — Competitive benchmark and banking safety boundary

The 2026-08 benchmark found no single product that closes this whole loop. Datadog provides
per-test history, ownership, flaky management and coverage-based impact analysis; Buildkite combines
framework-neutral results, parallelisation, ownership and quarantine; Launchable predicts subsets
from change and execution history; Checkly schedules Playwright as monitoring-as-code; and Grafana
correlates probes and k6/browser checks with metrics, logs and traces. Datadog documents tracked and
unskippable tests plus full default-branch runs, and Launchable recommends a later full-suite run,
because selection cannot observe every dependency.

OpenBank combines the useful parts with a bank-specific invariant: flaky classification is triage
metadata, never permission to make a failing money-path or control test green. Predictive selection
may become an advisory ordering or parallelism input only after full-suite preservation is proven;
it cannot be the sole required gate. Synthetic traffic must retain trusted identity and taint across
HTTP, Kafka, traces and regulatory projections; an untrusted HTTP header is never sufficient.

Test-to-trace correlation uses the existing privacy-preserving `TraceContract`. A successful test
emits a bounded marker only after at least one trace assertion has passed; the run collector turns
that JUnit marker into `trace` evidence with the same commit and workflow provenance. Trace ids,
attribute values and fixtures never enter the marker or snapshot. Source presence alone remains no
evidence, and the ordinary suite verdict stays authoritative if the enclosing JUnit suite fails.

The admin route is a primary platform destination, first in Platform navigation and pinned in the
platform persona workspace. Its E2E test navigates from the dashboard through the visible link;
opening `/system/tests` directly is not evidence of discoverability.

Browser failure diagnostics remain GitHub-authenticated, expire after seven days and are linked from
the exact E2E observation in the execution and client-experience views. Test Intelligence does not
proxy or render their potentially sensitive contents. This preserves the existing access boundary
while removing manual run-to-artifact rediscovery for an authorized operator.

### D9 — Client experience evidence and RUM boundary

Client quality is part of Test Intelligence, but CI and real-user telemetry remain distinct
observations. The Admin UI contributes its Playwright and visual-regression evidence; browser RUM
remains rejected for this internal operator surface by ADR-0088. The separate customer-app repository
publishes a bounded, immutable envelope containing unit and committed-golden verdicts plus run
provenance. Missing private-repository access or an expired artifact renders `not-run` with a blocker;
source presence is never promoted to a completed test.

Mobile RUM is consent-gated runtime evidence. The deploy-time projection records Android/iOS exporter
capability from a read-only checkout that is excluded from the Docker build context. The BFF counts
unique `openbank-app` trace IDs from a bounded seven-day Tempo search (maximum 1,000 results), marks a
full result page as a lower bound, and uses Tempo span-metrics through Prometheus only for the error
signal and as a degraded fallback. A non-zero observation proves that telemetry crossed the hardened
gateway; it does not prove traffic volume, a particular OS deployment, or test success. Zero is an
explicit absent observation, not a failure, because consent is opt-in. Operator-triggered AI analysis
receives only bounded client CI kind/state pairs; it does not receive RUM counts, details or source
paths. The agent may diagnose a missing mobile execution envelope, but cannot synthesize a client
verdict or alter CI/RUM state.

## Alternatives considered

- **Only fix the hard-coded service lists.** This repairs the most visible undercount but preserves
  misclassification, missing Kover/performance/synthetic evidence and ambiguous zeroes. Rejected as
  insufficient.
- **Make the admin UI query GitHub Actions and Prometheus directly.** This appears live but puts
  credentials, provider-specific pagination, artifact expiry and partial-source failure into a
  request path. Rejected because the UI is a read-only operator consumer and image-build
  collection is already the owned seam.
- **Create a test-intelligence microservice and database immediately.** This enables real history,
  but needs an ingestion trust model, retention, migrations, backup/restore, tenancy and operational
  ownership. Rejected for the first slice; the versioned envelope preserves that future path.
- **Keep separate pages and reports for each tool.** It preserves local simplicity but leaves the
  operator unable to see known gaps across a service or business capability. Rejected because
  consolidation is the requested outcome.
- **One weighted quality score.** It is easy to rank, but weights are arbitrary and missing inputs
  can improve the result. Rejected for decisions; independent evidence states remain visible.

<!-- Required (enforced). Reconstruct, never invent: if no alternative was genuinely
     considered, say exactly that instead of manufacturing a plausible rejected option. -->

## Consequences

**Positive**
- Missing, expired and stale evidence becomes visible rather than reading as healthy zeroes.
- New services appear automatically and start with honest `not-run` evidence.
- Code, scenario and operational coverage can no longer be confused in the UI.
- Existing CI and deployment ownership is reused, keeping the first rollout reversible.
- The common schema provides a migration seam for future durable run ingestion.
- Operators can distinguish live, failed, stale and never-successful synthetic journeys without
  teaching the UI unverified k6 metric names.

**Negative**
- Deployment history is bounded to 30 snapshots and can contain observations from different commits;
  provenance and warnings expose but do not remove that limitation.
- Collectors must understand several tool formats and degrade individual sources independently.
- Inventorying all released components will initially make the estate look worse because previously
  invisible gaps become visible.

**Neutral**
- Existing test verdicts and schedules do not change; a new enforced wiring gate protects the
  evidence chain but does not reinterpret a test result.
- Legacy APIs remain during the compatibility window and temporarily duplicate projections.

## Compliance impact

<!-- Required (enforced). Do NOT write an article, clause or requirement number unless
     that exact citation appears in this ADR's own text — auditors read these rows as
     claims about the platform. Otherwise name the engagement in plain words, or write
     "not applicable — <specific reason>". For most internal engineering decisions,
     four or five rows are honestly "not applicable". That is the right answer. -->

- PCI DSS: improves visibility of software test evidence; no cardholder-data processing changes
- DORA: improves ICT testing evidence and known-gap visibility; no regulatory control is claimed
  solely from dashboard presence
- GDPR: not applicable — no personal data is introduced into the report
- PSD2: not applicable — payment API behaviour and authorization do not change
- CNB: improves inspectability of engineering controls; no reporting obligation changes

## References

- ADR-0020 — test coverage ratchet
- ADR-0029 — versioning, release and governance as code
- ADR-0063 — consumer-driven contract and mutation testing
- ADR-0076 — admin UI integration and end-to-end testing
- ADR-0100 — deterministic simulation testing
- `openbank-libs/governance/journeys.yaml`
- `.github/workflows/_service-ci.yml`
- `.github/workflows/perf-gate.yml`
- [Datadog Test Optimization](https://docs.datadoghq.com/tests/)
- [Datadog Test Impact Analysis](https://docs.datadoghq.com/tests/test_impact_analysis/)
- [Datadog Flaky Test Management](https://docs.datadoghq.com/tests/flaky_management/)
- [Buildkite Test Engine](https://buildkite.com/platform/test-engine/)
- [Launchable predictive test selection](https://help.launchableinc.com/features/predictive-test-selection/how-launchable-selects-tests/)
- [Checkly Playwright checks](https://www.checklyhq.com/docs/detect/synthetic-monitoring/playwright-checks/overview/)
- [Grafana Synthetic Monitoring](https://grafana.com/docs/grafana-cloud/observe-and-act/testing/synthetic-monitoring/introduction/)
- [BrowserStack AI agents](https://www.browserstack.com/docs/test-management/browserstack-ai)
- [BrowserStack Smart Test Selection](https://www.browserstack.com/docs/automate/selenium/smart-test-selection)
