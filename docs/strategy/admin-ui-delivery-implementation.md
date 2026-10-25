# Admin UI quality, modularity and delivery implementation

This implements the delivery and evidence proposal incrementally across operational
screens, shared resource handling, feature boundaries and the release workflow.
The acceptance criteria below distinguish executable regression protection from
production rollout proof and measured latency. The target latency budgets remain
targets until a post-rollout sample establishes them.

## UI correctness and regression prevention

Operational views must distinguish observed success, observed failure, unavailable
or malformed evidence, and a verified empty result. A failed refresh must not make an
old observation look current. These are shared correctness requirements, not just a
System Health presentation rule.

| Surface | Change | Regression evidence |
| --- | --- | --- |
| System Health and Dashboard | Namespace-qualified workload identity and explicit unknown observations | Identity, aggregation and live rollout checks from the preceding release |
| FinOps | Six independent validated sources; one failed or slow source cannot hide the others; per-source retry; incomplete metrics labeled | HTTP, transport, JSON, shape, stale refresh and recovery tests; English/Czech and light/dark browser checks |
| DevOps | Unavailable findings are not a healthy pipeline; verified empty findings have neutral copy; git commits are labeled as a proxy rather than deployments | Findings availability and recovery browser tests; DORA route tests |
| BCP | Partially unknown tiers remain unknown; payment readiness is unverified when compliance evidence is missing; invalid refresh cannot preserve a clear state | Rendered-page tests for missing members, known failure precedence and recovery |
| Shared resource hook | Invalid JSON is a terminal contract error; a body transport failure can still retry | Hook tests distinguish SyntaxError from network/body failure |
| Accessibility | FinOps retry controls use semantic contrast; horizontal tables are keyboard focusable | Axe checks and narrow-viewport browser scenarios |

These checks supplement the existing render-smoke, RBAC, route, contract and browser
suites. They are not a claim that every route or business transaction has been manually
audited. Backend financial authorization remains authoritative; BCP is a read-only
readiness view, not a payment execution gate.

## Readiness and BCP component scope

The Production Readiness matrix and BCP roster use the shared
`openbank-admin-ui/scripts/service-inventory.mjs` detector: root Gradle modules with
`src/main` and the Quarkus service convention plus shared runtime dependency, or the
direct Quarkus plugin. The detector is structural; naming suffix, governance metadata,
release registration and a GitOps workload do not decide whether an application is in
scope. Thus runnable agents and analytics/product/risk components remain visible, and a
runnable component without a workload remains an explicit `NOT-DEPLOYED` row. Catalog
evidence gaps such as missing `version.txt`, release registration or `governance.yaml`
remain visible; they do not imply production readiness.

Python readiness collection invokes this helper through `node`, so Node.js is required
for both collector implementations. The helper and collectors fail closed on execution
or roster errors. In particular, a declared money-path module missing from the runnable
inventory stops collection rather than disappearing from the matrix. Distinct module
names that normalize to one short identity also stop inventory generation. The readiness
report retains its existing `services[]` and `service_count` schema. BCP uses the same
`runnable` catalog marker; it applies only explicit policy tiers, including the authored
“all other services” tier when present. Without that policy row, unclassified components
remain unknown and coverage is incomplete.

## Delivery baseline

Run `python3 .github/scripts/admin-ui-delivery-baseline.py --repo OWNER/REPO --target 30
--max-runs 600 --output /tmp/admin-ui-delivery-baseline.json` from the repository root.
The report samples started image-build steps by run and attempt, separates unsuccessful
builds and skipped attempts, and includes the denominator for each percentile. Incomplete
samples explicitly remain incomplete. Job-boundary queue measurements are proxies, not
runner scheduling telemetry. Historical build, push and signing share one recorded step;
human review and completed rollout require separate evidence and are not inferred.

The initial 2026-10-19 sample contains 30 started build attempts: 21 successful,
8 failed and 1 incomplete. It required inspecting 311 attempts; unstarted/skipped
builds were excluded. These are attempt timings, not 30 successful releases.

| Observed interval | n | p50 | p95 |
| --- | ---: | ---: | ---: |
| Run created to source job start (queue proxy) | 30 | 15 s | 374 s |
| Evidence coalescing contribution | 30 | 0 s | 600 s |
| Exact-source validation wait | 30 | 669 s | 1390 s |
| Validation finish to build job start (handoff proxy) | 30 | 24 s | 667 s |
| Build job preparation | 30 | 298 s | 357 s |
| Collection, image build, push and attestation combined | 29 | 341 s | 361 s |

One attempt has no build completion timestamp. Percentiles use nearest rank; phase
percentiles must not be added to estimate an end-to-end percentile. This is a baseline,
not evidence of improvement from these unmerged changes.

## Event-driven source readiness

The deploy workflow no longer starts directly on source-path pushes or holds a runner
while exact-source CI and contract validation complete. Every main push starts both
CI and Services CI; their trusted workflow_run completions each make a bounded,
read-only readiness check. A completion that arrives before another required run is
visible exits without requesting OIDC or starting an image build. The later completion
retries the check. Pact drift check completion also retries readiness for sources
that trigger its path-scoped workflow. Main changes that do not trigger Pact may use
the newest successful Pact run for the identical Admin UI tree; when a changed path
matches Pact's trigger set but its exact-source run is not visible yet, readiness
stays pending rather than borrowing older evidence.

The check requires exact-source CI success and its Admin UI build job, plus exact
Services CI success and its all-green aggregate (which validates the selected
service scope; individual unselected matrix jobs may legitimately be skipped). When
the source triggers Pact, both the regeneration/diff gate and main-only verified
publication job must succeed. Manual and scheduled runs retain the bounded waiting
path. The daily run is recovery if a completion event is delayed or suppressed.
Readiness still checks out trusted default-branch code, rejects superseded sources,
and checks for an already-open deploy PR before OIDC. Exact-source validation does
not authorize reusing a build artifact; CI and Docker builds still differ in generated
inputs and build configuration, so image construction remains separate and signed.

This removes runner occupancy during prerequisite validation and the former ten-minute
coalescing sleep. It does not reduce CI/Pact completion time or claim a measured
end-to-end latency improvement. The existing timing baseline above remains the
pre-change comparison until a post-rollout sample is collected.

## Data delivery without an image build

Gate Health is the first extracted dataset. `admin-ui-evidence.yml` uses the existing
collector, validates the schema and producer timestamp, writes an immutable object keyed
by run, attempt and SHA-256, and compare-and-swaps `current.json` only afterwards. The
30-minute schedule bounds collector API cost; manual dispatch supports an immediate refresh.
This cadence is intentionally not the proposed two-minute CI-evidence target.

The authenticated BFF reads the store without credentials. It verifies the manifest,
same-origin path, byte digest, payload shape and observation time, with a bounded body,
timeout and single-flight cache. Bad publication cannot replace the last verified data.
The UI distinguishes unavailable, stale and bundled data and shows observation time.
The browser never receives GitHub or object-store credentials.

The publisher has a separate role limited to this dataset. It can list only the selector
key and read/write its selector and snapshots. Existing service-evidence bodies retain
write-once protection. The selector alone permits conditional replacement; CAS conflicts
fail instead of retrying with a blind overwrite. Bucket versioning remains enabled.
OIDC trust requires this repository's main ref. The workflow-ref condition follows the
existing `StringEqualsIfExists` provider convention: it narrows claims when supplied;
it is not proof of workflow identity when the issuer omits that claim.

Rollout sequence, through the normal reviewed infrastructure and GitOps paths:

1. Review the infrastructure preview, then dispatch **Platform OpenTofu** on `main`
   with scope `admin-ui-evidence`. This fixed scope targets the publisher role, its
   inline policy and the evidence bucket policy. A saved-plan check rejects any other
   managed-resource mutation or any deletion/replacement before apply. OIDC, state
   locking and applying the same saved plan remain in the existing workflow. This
   permits activating the dataset without applying unrelated platform changes.
2. Configure repository variables `ADMIN_UI_EVIDENCE_WRITER_ROLE_ARN` (output
   `admin_ui_evidence_writer_role_arn`), `ADMIN_UI_EVIDENCE_BUCKET` (existing
   `test_evidence_bucket` output), and `ADMIN_UI_EVIDENCE_REGION` for that deployment.
3. Dispatch **Admin UI evidence** and verify its successful publication. The job fails
   explicitly if configuration is missing or collection is unavailable.
4. Deploy the UI through its signed image and GitOps PR. The new URL is derived from
   the existing evidence-store environment variable. When the independent publisher is
   configured, image builds stop collecting Gate Health themselves.
5. Refresh data with another publication and confirm the displayed observation changes
   while `/api/build-info` remains unchanged. A cloud round trip is a rollout acceptance
   check, distinct from the local publisher/reader/route regression suite.

For rollback, dispatch **Admin UI evidence** with a prior
`snapshots/<run>-<attempt>-<sha256>.json` key. The publisher verifies the prior object's
digest and producer metadata before switching the selector. It preserves the original
observation time; selecting old evidence never makes it fresh. No app build is involved.

## Image identity and build cache

The image build can reuse a scoped GitHub Actions cache v2, including intermediate
stages. Cache credentials are short-lived and cleared before subsequent steps. Cache
export failure does not turn an otherwise valid build into a deployment failure.
Cache reuse does not bypass source checks, signing or browser validation.

After signing, delivery starts the exact image by registry digest on loopback and runs
an authenticated Playwright probe against its actual standalone server. The probe checks
unauthenticated rejection, source revision, protected page rendering, static assets and
a client-side theme interaction that proves hydration.
There is no second Next.js build or mocked BFF in that probe. Only success allows the
GitOps PR to be opened. The PR records the tested digest, and the workflow retains image
metadata and browser proof. The existing immutable deployment tag continues to identify
that digest. This proves packaged behavior, not live Keycloak or backend connectivity;
the deployed environment still needs its separate authenticated read-only smoke checks.

`build-push-admin-ui.sh --collect-only --no-sbom` prepares real image inputs for local
validation without an image push or GitOps change. `test-admin-ui-image.sh` accepts a
registry digest or local immutable image ID and creates only disposable local auth.

If a run fails after pushing its immutable tag, start a new workflow dispatch rather
than rerunning that run's build step. The existing tag includes the run ID, so a new
dispatch has a new tag; retrying the same pushed tag is not an artifact-resume mechanism.

## Incremental module boundaries

The single shared shell remains responsible for authentication, navigation, localization
and design primitives. A curated ownership map covers payments, ledger, balance sheet,
treasury, pension, approvals, risk, lending, party, customer 360, loyalty and KYC.
Cross-owner dependencies among their component/library implementations must use an
explicit public contract entrypoint. The allowlist is consumed by ESLint; it is not
merely documentation. Shared infrastructure folders are outside domain ownership.

App routes remain the composition layer. The narrower financial-domain bans also apply
there: payments cannot reach private ledger/balance-sheet/treasury code, and pension,
treasury and approvals cannot depend on balance-sheet internals. Lower-level libraries
and components cannot import API route modules. Alias and relative imports, re-exports,
type imports and literal dynamic imports are checked.

The change extracts reusable resource fetching, bank-date and principal helpers and
moves health, loyalty and DevOps types into shared contracts. Narrow public facades
expose existing ledger, risk, party, customer-360 and lending contracts without moving
business logic. This is an enforceable boundary for the mapped domains, not independent
frontend deployment or a claim that every directory already has an owner.

## Build once: compatibility requirements

The existing CI output cannot yet be used as the production image payload. CI builds
on x64/glibc, while the deployed image uses ARM64/musl. The image also enables standalone
output and supplies public version/source variables that are compiled into client assets.
Copying an arbitrary `.next` directory would break the artifact identity guarantee.

The next artifact handoff must use the same pinned builder platform, standalone mode,
lockfile and public build environment. Its manifest must bind the source revision,
lock/config hashes, generated compilation inputs, target platform and payload hashes.
Only a successful trusted main run for the admitted exact source may supply it. The
image assembly can then attach runtime evidence snapshots without recompiling Next.js,
retain signing and source-map handling, and run the existing exact-image browser probe.
Missing or expired artifacts need an explicit fallback to the current compile path.

Static imports such as platform versions and the origination graph belong to compilation
inputs. Files read by the BFF at runtime belong to separately assembled evidence. That
classification must be proven per dataset before moving a collector: filesystem reads
alone do not prove the file is copied into the final runtime image.

## Route inventory and read-only navigation acceptance

A source inventory on 2026-10-19 found 170 App Router pages: 137 static paths and 33 dynamic templates. Sidebar source contains 106 destinations: 103 in-app pages and three proxied tools. The admin role exposes 105 destinations: 102 in-app pages and the same three tools. The Playwright sidebar smoke derives its destination list from `Sidebar.tsx`, so new sidebar entries join automatically. On the prior production-prebuilt target, the sidebar smoke passed 2/2 and the static direct-load run passed 35/35. A targeted dynamic run passed 36/36 checks while excluding `/accounts/[id]` ahead of the account-page update and new build; these counts are specific to that run, not proof that all 170 routes have populated data or correct business behavior.

| Existing route evidence | Count | What it establishes |
| --- | ---: | --- |
| Routes with a Playwright direct-load assertion | 96 / 170 | Source contains a `page.goto` for that route template; this does not establish every page's data or controls work. |
| Distinct destinations with click/press plus cross-route URL assertion | 4 / 170 | `/campaigns/[id]`, `/parties/[id]`, `/pension/contracts/[id]`, and `/system/tests`. |
| Destinations with redirect assertions | 2 | Includes the role-denied communication editor landing at `/auth/forbidden`. |
| Routes with only a page-module render reference | 35 / 170 | Component rendering is covered, but a browser navigation is not. |
| Routes with no route-specific test reference | 39 / 170 | No browser or page-module test reference was found by the source inventory. |

The counts overlap where a route has both a direct load and an interaction assertion. In the exclusive classification, 92 routes are direct-load-only, 4 have an interaction transition, 35 are render-only and 39 have no route-specific test. The earlier scan reported 93 direct-load, 37 render-only and 40 unreferenced routes; this refreshed per-test AST inventory and route-template matching changed classification, not application behavior or proven coverage. These are source-analysis counts, not a claim that all tests pass.

`e2e/route-inventory-classification.spec.ts` requires every static page to have a safe direct-GET path and an explicit disposition; every dynamic template must have a concrete fixture path and evidence record in `dynamic-route-inventory.ts`. `e2e/static-route-direct-load.spec.ts` directly loads 34 static routes outside the sidebar: 29 operator pages, four public entries (`/auth/error`, `/auth/forbidden`, `/auth/login`, `/privacy`) and the root redirect. It signs in with a local test role set for protected routes, asserts the root redirects to `/dashboard`, checks page content and error states, and records/fails on same-origin methods other than GET, HEAD or OPTIONS during those loads. This is a bounded assertion for that test run, not a general guarantee that every app route is free of write requests. The sidebar smoke separately exercises its discovered in-app destinations. The prior production-prebuilt static run passed 35/35 checks; a later integrated run remains pending.

`e2e/dynamic-route-direct-load.spec.ts` defines one safe concrete URL for each of the 33 dynamic templates. It stubs browser API reads with 404 and rejects writes, so it checks route resolution and settled failure/unavailable UI without changing data or depending on a populated environment. A stubbed 404 is not evidence that the corresponding backend record is absent. The inventory names existing fixture sources where available and marks synthetic paths separately; it also records which routes have populated E2E assertions and which have render-only unit fixtures. Three true unknown-record behaviors are asserted by focused tests: missing Communication persona, missing flaky-test finding, and malformed Pension contract UUID. The targeted prior production-prebuilt run passed 36/36 checks with `/accounts/[id]` excluded before its page update and the new build. Report this as navigation and terminal error/unavailable-state coverage, not populated-record rendering or business correctness across all 33 templates.

A read-only production clickthrough on revision `c332985aa` rendered the main content for all 102 admin-visible in-app sidebar destinations after retry. Most captures showed initial data loading, so they establish navigation and shell rendering only. `/iaops` rendered on retry after an earlier browser block.

The three tool links exposed a confirmed deployed bug. Envoy appends the original tool path to its authorization-check URL, so a trailing-slash request reaches Next as `/api/gate/<tool>/tools/<tool>/`. Deployed revision `c332985aa` did not set `skipTrailingSlashRedirect`; Next's default normalization returned a 308 to the slashless internal gate URL, which Envoy exposed to the browser. Root curl and a Pyrra port-forward reproduced the 308. The current change sets `skipTrailingSlashRedirect: true` and keeps the normal public console redirect in `src/proxy.ts`, while `/api/gate/**` bypasses that redirect so Envoy receives the original path. `src/test/proxy-trailing-slash.test.ts` covers gate pass-through, console canonicalization and the Next setting. The fix is not yet verified in production: after rollout, repeat authenticated browser navigation for Grafana, Alertmanager and Pyrra and confirm the public URL never exposes `/api/gate/` and the tool content loads.

Acceptance for broad route coverage is:

1. Keep the inventory classification test green; every newly added static route gets a safe path/disposition and every new dynamic template gets an explicit fixture requirement.
2. Run the derived Sidebar smoke against a seeded admin role. It must click the in-app destinations present for that role, detect route HTTP errors, uncaught page errors, the not-found page and the shell error boundary, and wait for a route-specific terminal state before claiming a rendered-page pass.
3. Run the direct-load smoke for all 34 static non-sidebar routes and the dynamic direct-load smoke for all 33 templates. The current specs record/fail on same-origin methods other than GET, HEAD or OPTIONS during these test loads. Dynamic browser API reads are stubbed 404; report that as terminal error/unavailable-state coverage, not proof a record is absent or that populated data rendered.
4. Run the focused existing populated detail fixtures referenced by the dynamic inventory, plus the three unknown-record cases, before claiming those individual states. The inventory gate prevents a new template from escaping classification; it does not claim that every dynamic template has populated-state coverage.
5. Report redirects, unavailable API states and browser-blocked proxied tools separately. A loading shell or route URL alone is not functional readiness.

## Rollout acceptance and measurement

1. Run the changed behavior tests, complete lint/type checking and the existing broad
   UI suite. Review workflow trust and readiness independently.
2. Merge through the required reviews and CI. Build, sign and browser-test the exact
   image before the normal GitOps promotion.
3. Verify deployed revision/digest and authenticated read-only navigation across Health,
   FinOps, DevOps and BCP. Exercise source failures locally with fixtures; do not break
   live services merely to demonstrate fallback UI.
4. Collect the same delivery timing sample after rollout. Report build attempts,
   successes, failures and incomplete observations separately. Do not convert the removed
   wait into an asserted end-to-end speedup without measurement.
5. Expand independent evidence publication beyond Gate Health and implement compatible
   build-artifact reuse as separate reviewable changes. The conceptual destination is
   one validated application artifact, independently refreshed data, and explicit feature
   contracts; separate frontend deployments require a measured benefit first.


## Runtime evidence and content correctness

Navigation coverage is only one acceptance layer. A source audit of 170 page templates
separately classified API reads, service hooks, reference content and generated snapshots.
The audit found two distinct ages that must remain visible: when the browser last checked,
and when the producer observed the underlying fact. `cache: no-store` does not refresh a
producer, and a successful HTTP response does not establish a valid domain payload.

The independent evidence workflow now publishes four named datasets: Gate Health,
Production Readiness, Test Intelligence and Security KPIs. Each has a validated payload,
immutable content-addressed snapshot, source revision and conditional `current.json`
selector. Readers verify the digest, dataset schema, timestamps and bounds before showing
it. A one-hour selector expiry detects publisher failure independently of the dataset's
age threshold. Re-publication never changes the original observation time. Security KPI
collection remains weekly; its trusted workflow artifact reaches the runtime publisher
without waiting for the reference snapshot's refresh PR or an application image rebuild.

Readiness counts service modules from the canonical governance roster; Test Intelligence
counts released components and its explicitly included test components. Those populations
must be named, not forced to the same number. Readiness is repository/GitOps maturity and
TTL-bound attestations, not an assertion that pods are healthy. Test evidence is bound to
its producer run and component commit; a missing match cannot retain an unrelated green
verdict. Stale or unavailable evidence remains distinguishable from failed execution.

Reporting must validate the actual warehouse wire format. ClickHouse JSON DateTime64 values
need ISO output for the browser's datetime contract. Invalid payloads are contract errors,
not proof the warehouse is down. The report timestamp means query completion, not an
unmeasured ingestion watermark. Populated browser fixtures exercise the Run report action
as well as initial page navigation.

Rollout requires the approved, narrowly scoped publisher policy, valid initial snapshots,
and a verified application image. Then verify the live timestamp/source revision and a
subsequent publication without rebuilding the image. For proxied tools, retain OIDC session
and per-tool role checks, fail-closed edge policies and network isolation. Verify anonymous
denial and authenticated content/assets through the real edge; direct gate unit tests alone
do not establish that the complete browser journey works.
