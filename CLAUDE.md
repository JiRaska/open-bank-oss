# OpenBank — agent & contributor guide

A banking platform reference implementation: a Quarkus/Kotlin monorepo of ~34 `openbank-*`
microservices, a Next.js `openbank-admin-ui`, and a shared `openbank-libs`. Hexagonal
architecture per service (ADR-0002).

**Authoritative rules live in [`openbank-libs/governance/rules.yaml`](openbank-libs/governance/rules.yaml).**
This file is the human-readable summary; `rules.yaml` is what CI enforces — when they disagree,
`rules.yaml` wins. [`CONTRIBUTING.md`](CONTRIBUTING.md) is the long-form narrative.

## The non-negotiables

Before a change is "done", run the ship-checklist (`/ship-check` runs the same checks CI gates on,
ADR-0029):

1. **Open a PR.** No direct commits to `main`. Branch `<type>/<scope>-<summary>`; squash-merge via PR.
2. **Versioning is automatic, and needs BOTH axes.** release-please cuts a release only when the
   commit **type** releases (`feat`/`fix`/`perf`/`security`, or breaking) **and** it touches a file
   under `<service>/` outside that package's `exclude-paths` (today `src/test`, plus `e2e` for
   admin-ui). The commit message *is* the changelog. Neither axis alone is enough:
   - A hidden type (`refactor` `docs` `test` `build` `ci` `chore`) does not release at any path —
     unless it carries a breaking marker, which renders a ⚠ section and so releases a major. That
     is the lever when a PR doesn't change shipped code (`rules.yaml: release_scope_mismatch`).
   - The path axis is broader than `src/main`: `governance.yaml`, `Dockerfile`, `build.gradle.kts`
     and the lint baselines all count. There is no include/allow-list — only `exclude-paths`,
     matching **directories** only, so a single file cannot be excluded.

   Do **not** hand-edit `version.txt`, `CHANGELOG.md`, or `quarkus.application.version` (it derives
   from `version.txt`).
   A module is a released component **iff** it has a `version.txt` (registered in
   [`release-please-config.json`](release-please-config.json) + [`.release-please-manifest.json`](.release-please-manifest.json)).
3. **API change ⇒ `openapi.yaml` updated + contract test.** Two independent version axes (ADR-0048):
   the **release** version (`version.txt`) and the **API-contract** version
   (`openapi.yaml:info.version`, whose major == URL `/api/v{N}`). An API change classifies its own bump
   from the OpenAPI diff (`oasdiff`), never forced equal to the release version.
4. **DB change ⇒ Flyway migration + rollback note. Event change ⇒ schema versioned backward-compatibly.**
   **Config change ⇒ no duplicate YAML keys** in `application.yaml` — SmallRye/SnakeYAML keep only the
   *last* of a repeated mapping key and silently drop the rest (CI enforces this).
5. **Test the new behavior.** Coverage is ratchet-only (never lower); money-path services aim higher.
6. **Derived data is never hand-edited.** Catalog, coverage, and the governance manifest are
   CI-generated — edit the source, not the artifact.
7. **Money-path services** (`rules.yaml: money_path_services`) need 2 approvals + a threat model
   (`docs/threat-models/<service>.md`, ADR-0030).

## Commit format

```
<type>(<scope>): <imperative summary>
```
`type` ∈ feat|fix|perf|refactor|docs|test|chore|build|ci|security. `scope` = service without the
`openbank-` prefix (e.g. `ledger`, `sepa-payment`) — **the scope selects the released component**.
Sign every commit: `git commit -s -S`. Full vocabulary in `rules.yaml: commits`.

## Issues — the actionable backlog (ADR-0052)

Issues track *what needs doing*; they don't duplicate ADRs (decisions) or release-please (changelog).
Open one for a **fleet sweep**, a **governance follow-up** (the actionable tail of an ADR), a **bug**,
or an **enhancement** — not for architectural decisions (→ `docs/adr`), questions (→ Discussions), or
security holes (→ private Security Advisories). Every PR links its issue (`Closes #<n>` / `Refs #<n>`).
Labels are code (`.github/labels.yml`, applied by the Label-sync workflow) — don't create them by hand.

## Build

```
./gradlew :<module>:build                          # one service
./gradlew detekt ktlintCheck koverVerify build     # the local gate before a PR
```
CI is path-scoped (only changed services build). The domain layer has **zero** framework imports.

**Gradle stops at the first failing task, so a green-looking task list is not a list that ran.** In
that one-liner `test` precedes `ktlintCheck`; a failing `test` means ktlint never executes, and a
`test` failure can be environmental (a second Gradle build on the same machine takes the port, and
the run dies with `QuarkusBindException`) — so a genuine lint violation reaches CI while the local
gate looked like it had merely flaked. Read which tasks actually appear in the output, and re-run
the cheap checks (`ktlintCheck detekt`, seconds) on their own after fixing a test failure.

## Skills

- `/ship-check` — authoritative pre-merge preflight; mirrors the CI gates.
- `/bump <service>` — raise `version.txt` + `openapi.yaml` info.version per change type.
- `/open-pr` — branch, PR from template, verify bump + changelog present.
- `/release <service>` — assemble release notes (release-please).

## Engineering notes (common pitfalls)

These are real, repeatable gotchas — worth knowing before they cost you a debugging session.

### Kotlin / Quarkus
- **Always fast-jar, never uber-jar.** Service Dockerfiles use `-Dquarkus.package.jar.type=fast-jar`
  and COPY `quarkus-app/`; an uber-jar leaves `quarkus-app/` empty → crashlooping pod.
- **`@ConfigProperty` optional fields must be `Optional<String>`,** not plain `String`, or a missing
  value throws `SRCFG00040` at boot. Use `Optional<String>` + `defaultValue`.
- **Kotlin JUnit5 + `runBlocking` silent drop.** `fun foo() = runBlocking { }` infers a non-`Unit`
  return type and JUnit5 ignores the method. Write `fun foo(): Unit = runBlocking { }` or use a
  coroutine test runner.
- **`openbank.outbox.dispatch-enabled` defaults to `false`.** Any service with an outbox entity must
  set it `true` in `application.yaml`, or events never dispatch (no error, `attempt_count` stays 0).
- **CDI wiring isn't validated by `ktlintCheck` + unit tests.** Add `:svc:quarkusBuild` to your
  pre-push gate; ArC/CDI failures only surface there.
- **Panache reactive `persist()` on an application-assigned `@Id` is INSERT-only — use `merge` for
  updates.** An aggregate whose `@Id` is set by the app (not `@GeneratedValue`) fails *every*
  lifecycle transition with `duplicate key value violates ... _pkey` at flush if the repo `save`
  calls `persist`/`persistAndFlush`: Hibernate schedules an INSERT and never an UPDATE, because a
  non-null assigned id can't distinguish transient from detached. Use
  `Panache.getSession().flatMap { it.merge(entity) }` (the upsert SDD's `SddMandateRepositoryImpl`
  already documents). Invisible to unit tests that mock the repository — consent-service shipped this
  way and every revoke/reject/activate 500'd, caught only by a real-DB IT (ADR-0126 D3, #1521).
- **A `Panache.withTransaction`/`withSession` reactive repo can't be called from a bare
  `@QuarkusTest` thread** — `runBlocking { repo.save(...) }` throws `No current Vertx context found`.
  Only a real HTTP request carries a Vert.x context: drive the flow through the REST endpoint
  (RestAssured + `@TestSecurity`) and assert the row with a plain JDBC read. This is also the only
  way to prove transactional-outbox atomicity (status change + outbox row commit together) — a mocked
  repo can't. Pattern: `LendingOutboxWriteIT`, `ConsentRevocationOutboxIT`.
- **A non-nullable `@QueryParam`/`@HeaderParam` is a 500, and `requireNotNull` in the body is DEAD
  CODE — the parameter must be declared nullable.** JAX-RS injects `null` for an absent parameter;
  Kotlin's null-safety is compile-time only, so the declared type only decides *where* the failure
  lands, and every landing is a 500 (`GenericExceptionMapper`). Measured with `javap`: a plain `fun`
  emits `Intrinsics.checkNotNullParameter` at **offset 0**, so a guard written in the body compiles
  to nothing — the NPE already threw. A **`suspend fun` emits no intrinsic at all**, so the null
  flows into the body and fails at the first dereference; where nothing dereferences it, the request
  proceeds with a null the signature promised could not exist, which is worse than the 500 and
  invisible. That is how three services shipped
  `require(idempotencyKey.isNotBlank()) { "Idempotency-Key header is required" }` that answered
  **500 for the absent header** — the exact case it was written for — and 400 only for a blank one.
  Write `@QueryParam("x") x: String?` + `requireNotNull(x) { "query parameter 'x' is required" }`;
  libs-runtime maps `IllegalArgumentException` to 400 (never add a service-local mapper, #526). The
  primitive case differs and is out of scope: JAX-RS supplies `0`, not null. `@DefaultValue` is the
  other correct answer where one exists. Enforced by `check-nonnull-jaxrs-params.py` (gate
  `nonnull-jaxrs-param-ratchet`) — money-path fixed, the tail baselined (#3104, #3624). Counting
  trap: an outbound REST-client **`interface`** carries the identical annotation and is NOT a defect
  (the caller supplies the argument, compile-time checked), which is half of every naive grep.
- **A Kotlin annotation binds to the NEXT declaration — a top-level function between `@Path` and its
  class silently steals it.** `McpEndpoint` had `@Path("/mcp")`, then a top-level
  `private fun String?.sanitizeForLog()`, then `class McpEndpoint`. The `@Path` bound to the
  *function*; the class carried none, RESTEasy never registered the resource, and **every POST /mcp
  answered 404 on a running pod** for the whole life of the endpoint. Nothing else changed shape: it
  compiled, it was still a CDI bean, `McpEndpointIdentityTest` (which calls the class directly) stayed
  green, and its siblings `/agent/chat` and `/api/v1/proposals` served normally. Downstream, admin-ui
  maps any error to "not deployed", so a healthy service rendered as *"Agent-service (MCP) is not
  deployed in this environment"*. **Ask the running app what it serves** — `/q/openapi` on the
  management port lists the registered paths, and 404-vs-405-vs-401 discriminates "unregistered" from
  "wrong method" / "needs a token". A unit test that calls a resource class cannot tell a served route
  from an unserved one; only a `@QuarkusTest` driving real HTTP can (`McpEndpointRoutingIT`, #3371).
- **An `Error` thrown in a FIELD INITIALIZER fails bean construction, and CDI then fails every
  injection point of that bean — including endpoints that never use it.** `OnnxFraudModel` documented
  "a load failure leaves `session` null, so `scoreShadow` returns null", and could not do it: the
  native library surfaces as `ExceptionInInitializerError` wrapping `UnsatisfiedLinkError` — both
  `Error`s, not `Exception` — thrown by `OrtEnvironment.getEnvironment()` in a field initializer,
  outside the `try` that catches `Exception`. So `GET /api/v1/fraud/review-queue`, a read-only analyst
  query that never touches a model, answered **500 on every call** because the bean is in its graph
  (#3376). Two rules: anything crossing into native code (`System.load`, JNI, `OrtEnvironment`) must be
  caught as `Throwable`, and a bean whose construction can fail belongs behind a nullable field, never
  a direct initializer. Grep for the shape: `private val x: T = SomethingNative...()` in an
  `@ApplicationScoped` class.
- **`@ApplicationScoped` is LAZY — an `init {}` guard or warning does not run at boot.** Quarkus
  creates the bean via a client proxy on first use, so a constructor that logs "this config is
  DEV-ONLY" or `check()`s a go-live flag stays silent until the first request that touches it — which
  for a rarely-called path can be never, or worse, the exact moment it's too late. Found live in
  `PdfBoxPadesSealAdapter` (#1299): it warns that every PAdES seal is "worthless as evidence" without
  a real keystore, and that warning had never once appeared in a pod log, while
  `require-trusted-issuer` — documented as "refuses to **start**" — would have thrown on a *request*,
  long after the deploy went green. Add `@Startup` (`io.quarkus.runtime.Startup`) to any bean whose
  `init` is a boot-time gate or a config-sanity warning.
- **A plain (non-`suspend`) `@Scheduled` method carries NO Vert.x context** — Quarkus invokes it on a
  bare `executor-thread`, so a body of `runBlocking { … }` around a reactive Panache call throws
  `HR000068` and the job aborts having done nothing, silently (the throw lands before the per-item
  try/catch). Make it a `suspend fun` — the unanimous fleet convention.
  `VertxContextSupport.subscribeAndAwait` is not the fix: it swaps one blocking bridge for another
  and *throws* if ever called from an event loop, so it breaks the day the method gains
  `@NonBlocking` or a virtual thread. Five schedulers, three of them money-path, had **never** run
  (#2148, #2187). Now a hard rule:
  `rules.yaml: scheduled_methods` + `.github/scripts/check-no-runblocking-in-scheduled.py`.
- **A test that calls a `@Scheduled` method directly cannot see that bug** — the direct call supplies
  the very Vert.x context the scheduler does not, so it passes against broken code (and where
  `%test.quarkus.scheduler.enabled` is `false`, as in ledger and billing, the scheduler class is
  structurally invisible to every test). Drive the REAL cron from a `@TestProfile` that re-enables
  the scheduler and shrinks the cron expression: `StandingOrderExecutionSweepIT`,
  `LedgerSchedulerVertxContextIT`, `CnbIngestionSchedulerVertxContextIT`. Footgun inside that
  profile: a `QuarkusTestProfile` loads in a **different classloader** from the test class, so a
  companion object initializes twice — a randomized id in `getConfigOverrides()` hands the scheduler
  one value and the assertion another. Use literals.
- **A Kafka `group.id` / `auto.offset.reset` written as a YAML key never reaches the connector.**
  SmallRye Config's YAML source quotes any leaf map key containing a literal dot, so
  `group.id: foo` under `mp.messaging.incoming.<channel>:` registers as the property
  `…<channel>."group.id"` — quotes included — and `KafkaConnectorIncomingConfiguration`'s plain
  `getOptionalValue("group.id", …)` never finds it. **Nothing errors**; the connector silently uses
  its own default. Set it from a real config source instead: a `<svc>-msg-override.yaml` ConfigMap
  with `override.properties` + `config_ordinal=500` (`openbank-transaction-service` is the worked
  example, and documents it in place). Do NOT just delete the YAML key — local dev and tests read it
  fine, and it is only the deployed path that breaks. **The trap is that six services look correct
  and are not the same kind of correct:** their `group.id` happens to equal
  `quarkus.application.name`, which is what the fallback produces, so the broken key is a no-op —
  until someone renames a service or gives a channel its own group. Those same six declare
  `auto.offset.reset: earliest`, where **no such coincidence is available**, so the effective value
  is the connector default and not the one in the file. Fixing that is not a config tidy-up: forcing
  `earliest` onto a consumer group running as the default re-reads the topic from the beginning, a
  mass replay on money-path channels. `check-kafka-dotted-keys.py` ratchets it (enforced in
  `Validate manifests`) — new occurrences fail, today's six are baselined against #2945, and a
  baseline entry that becomes covered is reported too (#686, #2945).
- **`Instant.EPOCH` as a data-class default is a lie every test agrees with, and a non-null
  assertion is not a check.** `AuditEvent.timestamp` and `FlagExposure.timestamp` both defaulted to
  it; 23 of the 25 fleet `AuditEvent(` sites take the default, and `FlagExposure.of` — the KDoc's
  "typical call site" — never passed one. Nothing caught it because `isNotNull()` passes against
  1970-01-01, and `FlagExposureTest` already asserted *every other* field of `of()`. Assert
  **recency** (`isBetween(before, now)`), never non-nullity, for any field meaning "when did this
  happen" (#3882). Grep the shape: `: Instant = Instant.EPOCH`.
- **Before calling a wrong-looking value a live defect, find out whether anything READS it — a
  field no code path consumes is a latent trap, not corruption, and the two need different fixes.**
  The audit envelope above looked like the worst case (evidentiary record, append-only store), and
  was not stored at all: the only `AuditEventPublisher` implementation is the logging fallback,
  which did not reference `timestamp`; there is no entity, mapper or outbox for the type; and
  `FlagExposure` has zero production consumers. Cheap to establish — enumerate the interface's
  implementations, then grep the field name across `src/main` — and it changes the whole PR:
  urgency, blast radius, and whether the honest fix is the value or the wiring. It also surfaces
  the real bug next door — here, that `AuditConsumer` substitutes ingest time whenever a producer
  sends no event time, which 7 of its 21 topics do, so the row asserts business time it never
  measured (#3883, fixed by #3907). Note what the same rule then did to my own framing: I filed
  that as "the consumer reads `occurredAt`, the canonical envelope says `timestamp`", and the
  envelope reaches that topic never — `DomainEvent` declares `occurredAt`, so the consumer was
  right and only the missing-time case was real.
- **Event payloads are built two ways here, and grepping for the JSON key finds only one of
  them.** Hand-built maps spell the key literally (`"settledAt" to batch.settledAt` in
  `ClearingEventPublisherImpl`) and a string grep sees them; a serialised data class
  (`objectMapper.writeValueAsString(DocumentGenerated(..., at = now))`) has no literal anywhere,
  the key existing only at runtime as a Kotlin property name. So `grep '"at"'` over
  `openbank-document-service` returns **nothing** while all three of its event types put `at` on
  the wire — a clean-looking no-hits answer about producers the probe structurally cannot observe
  (#3883). Enumerate event-time keys by reading the serialised TYPE, or a real message's payload,
  never by grepping quoted field names; and treat "no hits" over a codebase with two idioms as a
  fact about the probe. Same shape as the Pact bullet below on grepping `src/test` for the word
  "contract".

### ktlint
- Path-scoped CI only lints changed files, so a pre-existing wildcard import or a latent
  `function-signature` violation surfaces the first time you touch an older file. Let `ktlintFormat`
  collapse multi-line signatures; expand wildcard imports rather than hand-wrapping.

### detekt
- **`MagicNumber` fires on the fleet-standard percentile triple.** `publishPercentiles(0.5, 0.95, 0.99)`
  is 3 violations per call site — `DomainMetrics` only escapes via
  `openbank-libs-runtime/detekt-baseline.xml`, a new per-service adapter has no such cover. Declare
  `private const val P50/P95/P99` in the adapter's `companion object` (`ignoreConstantDeclaration` is
  on by default). The `Timer.builder` and `DistributionSummary.builder` call sites usually differ in
  indentation — a single find-and-replace fixes only one.
- **`LongParameterList` fires AT the threshold, not above it.** `config/detekt/detekt.yml` sets
  `constructorThreshold: 9`, so a 9-parameter constructor is reported — adding a metrics port to an
  8-param endpoint fails the gate. Use field injection
  (`@Inject lateinit var metrics: XMetricsPort`) as `LoanStageEventConsumer`, `VopRateLimitFilter`
  and `McpEndpoint` do.

### Flyway
- **Never change a migration after it has been applied to a live DB** — Flyway checksums the whole
  file (comments included), so any edit triggers a `checksum mismatch` startup failure.
- **Committing migrations does not run them: `migrate-at-start` defaults to FALSE.**
  security-scanner shipped `db/migration/V2`+`V3` with `quarkus-flyway` on the classpath and the
  switch set nowhere — not in `application.yaml`, not in its gitops env — so the deployed database
  held **zero tables and no `flyway_schema_history` at all**, and every write answered
  `relation "security_outbox" does not exist (42P01)` as a 500. Invisible from every angle you would
  normally check: the pod is Ready (health probes never touch the schema) and the migrations *are*
  present in the repo (#3350). `check-flyway-default-datasource.py` now enforces both directions —
  migrations ⇒ something runs them (the config key, or `QUARKUS_FLYWAY_MIGRATE_AT_START` in that
  service's own gitops manifest, which is psd2's deliberate shape), and `migrate-at-start` ⇒ a
  literal `quarkus.datasource.jdbc.url` in the file, since Flyway migrates the DEFAULT (Agroal/JDBC)
  datasource and a service that only boots because the env supplied one cannot start from this repo
  alone (#3080).
- **Two branches can claim the SAME migration version and git will never say so.** The version is
  the *filename prefix*, so `V10__delegated_action_index.sql` on a branch and `V10__hash_version.sql`
  on main are different files: the merge is textually clean, nothing conflicts, and the service then
  refuses to boot with `FlywayException: Found more than one migration with version 10`. Renumber to
  the next free version — safe only while the migration has never been applied, since after that the
  checksum rule above forbids touching it. The trap is what the failure HIDES: Quarkus cannot start,
  so every `@QuarkusTest` integration test in the module reports as **SKIPPED**, and the module reads
  `73 tests / 10 skipped / 0 failures` — which scans as a pass, because the number anyone looks at is
  the failure count. After the fix it was `73 / 0 / 0`: ten integration tests had silently stopped
  running. Generalize past Flyway: **after merging main into a branch, build and test the merged
  result and read the SKIPPED count, not just failures.** The collisions to expect are shared
  *namespaces* rather than shared lines — migration versions, enum and `@Id` values, JSON/YAML map
  keys, and the constructor or field shape of any class a test instantiates by hand (a `lateinit`
  added upstream fails every such test at once). Sibling of the multi-agent note that a clean merge
  can silently DELETE a list entry: same cause, git merges text and not meaning, opposite direction.

### Contract tests (Pact)
- **One BROKER-sourced `@Provider` test per provider.** Two verification classes with the same
  `@Provider` that both pull from the broker collide — each fetches every pact the broker holds; use
  a single test that picks the target per interaction in `@BeforeEach`. This does NOT forbid a second
  class with a different *source*: the sanctioned pair is a `@PactFolder` class (always runs, no
  infra) plus a `@PactBroker` + `@EnabledIfSystemProperty(pactbroker.url)` one (main-push only), as
  `openbank-ledger-service` carries. Do not read the older, shorter form of this rule ("one test per
  provider, gated on `pactbroker.url`") as licence to have only the broker class — see the next
  bullet for what that costs.
- **A consumer pact alone CANNOT catch a wrong request path — only the provider replay can.** The
  Pact mock server answers whatever path the client asks for, so pointing a client at a route that
  does not exist leaves the consumer test green; the real provider has no such route, so only
  verification goes red. Both halves were measured on #2283: renaming a response field in the client
  DTO reddens the *consumer*, changing the path reddens only the *provider*. A committed pact nobody
  replays is therefore worthless against the likeliest defect — and that is exactly how
  finrep-service shipped a call to `/api/v1/ledger/trial-balance`, a ledger path that has never
  existed, breaking every FINREP/COREP render while its unit tests passed against a mocked port
  (#2269). Never add a consumer pact without wiring (or confirming) the provider's `@PactFolder`
  replay. **`@PactFolder` specifically, and confirm it runs on a PR** — a `@PactBroker` class does
  not count: `_service-ci.yml` puts the PR lane on `ubuntu-latest` and blanks `PACT_BROKER_URL` off
  main-push (the broker has no public ingress, ADR-0056), so its
  `@EnabledIfSystemProperty(pactbroker.url)` gate skips it and the contract is replayed only *after*
  the merge. Measured 2026-07-25: **16 of 27 committed pacts** were in that state, across eight
  providers, several money-path (#2327) — and the estate reached 33 pacts the same day, so an audit
  was never going to hold it. `check-pact-provider-replay.py` now enforces it (`Validate manifests`,
  #2338): it derives the covered set from `pacts/*.json` and the `@Provider`/`@PactFolder`
  annotations, and a class that exists but cannot run — broker-sourced, gated, or excluded by its
  `build.gradle.kts` — does not count. `KNOWN_UNCOVERED` in that script is now **empty** (#2327
  closed): every committed pact is replayed on a PR. An entry added there needs a reason, and the
  check fails on a stale declaration in either direction, so a new debt cannot quietly become
  permanent. What this buys you: a NEW pact for a provider with no `@PactFolder` replay is red at PR
  time, instead of being discovered by a later audit.
- **A test excluded from CI is a place where two artefacts drift with nothing to notice.** The last
  ungated pact was swift's, and it stayed ungated because the module excluded its own consumer tests
  when `CI=true` — so the pact was never regenerated, and the drift gate declared it out of scope for
  exactly that reason. Under those two facts the test and its committed pact were free to disagree,
  and did: the test asked for a `SETTLED` status `SwiftStatus` has never contained, while the pact and
  the provider's `@PactVerifyProvider` both said `COMPLETED` (#2319). **The stated reason for the
  exclusion was also false** — "PactConsumerTestExt auto-publishes and the broker hangs" does not
  reproduce (17 s with a broker URL set, no publish attempt in the log), and 26 other modules forward
  the same `pactbroker.*` properties while running their consumer tests in CI. Before honouring any
  "this test can't run in CI" comment, reproduce it; the exclusion costs a gate, and the comment is
  the only thing asserting it was ever needed.
- **In a consumer test the expected path must be a LITERAL; only the outgoing request may be
  reflected off the client's `@Path`.** Deriving *both* sides from the annotation feels DRY and is
  vacuous — expectation and request move together, so the test stays green when the client points
  at a route that does not exist. Measured on #2290: with the assertion removed, a pact whose path
  came from the annotation passed against the broken `/api/v1/ledger/trial-balance`; with the path
  written as a literal, both interactions went red. The asymmetry IS the test.
  (`ProductCatalogPactConsumerTest` from #2283 still has the symmetric shape, and its KDoc promises
  a redness it cannot deliver at the consumer layer — the provider replay is what backstops it;
  tracked as #2328. A 2026-07-25 audit of all 27 consumer tests found it is the only one.)
- **Read the pact verdict from the JUnit XML, not the console.** pact-jvm prints
  `Not all of the N were verified` even on a fully green run — it is an artifact, not a failure.
  (`./gradlew … | tail` also masks the exit code; see the Kotlin block-comment note.)
- **`grep`ping `src/test` for the word "contract" does not find contract tests.** The prod-readiness
  C3 scorer did exactly that and scored pid, psd2 and sanctions as Verified on comment lines like
  `// the mark-and-sweep reconciliation contract`, while ap2 flipped Declared→Verified the moment an
  unrelated PR added a comment containing the word. Detect the artifact — an `au.com.dius.pact`
  import, or the `*Pact*Test.kt` / `*ContractTest.kt` naming — never the prose (#2291).
- **A gate whose SCOPE is a hand-kept list of the thing it checks reads as *passing* when the list
  is short, never as *unchecked*.** `pact-drift-check.yml` regenerates consumer pacts and asserts
  `git diff --exit-code -- pacts/`; that diff can only see files the regeneration step rewrote, so
  a module missing from the list leaves its committed pact untouched, the diff finds nothing, and
  the gate is green about work it never did. `:openbank-interest-service` sat that way from the day
  its pact was committed, and `:openbank-fx-service` needed a hand-edit in #2284. The scope is now
  DERIVED from the `@Pact(consumer = .., provider = ..)` annotations by
  `.github/scripts/derive-pact-drift-scope.sh`, which also fails on an orphan pact, an uncommitted
  pact, and a module dropped out of scope without declaring what that strands. Generalize: never
  let a gate's coverage set be maintained separately from the artifacts it covers — enumerate the
  artifacts, and make the exclusions the thing a human has to justify.

### OPA / authorization
- **`input.principal.type == "SERVICE"` can never fire — don't write it.** `AuthorizeInterceptor`
  only ever emits `ANONYMOUS`/`AI_AGENT`/`HUMAN`; M2M callers authenticate with a Keycloak
  client_credentials JWT, which the interceptor classifies as `HUMAN`, and no realm client is ever
  granted `ROLE_SERVICE`. A rego rule gated on a `SERVICE` principal type is structurally
  unreachable dead code that silently denies its intended M2M caller once `AUTHZ_ENFORCE` flips to
  `true` (found live in the shared `rest.rego` `edge-service-notification` rule, ADR-0034 Phase 5,
  issue #266). Identify a specific M2M caller by `input.principal.id` (Keycloak's
  `service-account-<clientId>` convention) instead — gating on `HUMAN` + `ROLE_OPERATOR` alone is
  NOT equivalent, since real staff also carry `ROLE_OPERATOR` and would over-grant. Enforced by
  `.github/scripts/check-no-service-principal-type.sh` (`rules.yaml: authz_policy`).

### GitOps / Kubernetes / OPA policy bundles
Full pitfalls — node livelock, `optional: true` secret refs, Argo Rollout dead-`stable` deadlock,
Kyverno admission-vs-runtime, `cosign attest` being additive — live in
[`openbank-infra/CLAUDE.md`](openbank-infra/CLAUDE.md); they load when you touch that tree. Two
fire from *outside* it, so they stay here:
- **Editing any `.rego`, or a `rules.yaml` key the policy READS, restamps every service's OPA
  bundle + pod-roll annotation.** The bundles no longer embed `rules.yaml` itself (#3357) — they
  embed `openbank-libs/governance/rules-opa-data.yaml`, the derived subset of top-level keys some
  `.rego` reads as `data.rules.<key>`, and hash that. So an edit to one of the ~30 keys OPA never
  reads now changes **nothing** under `gitops/` (16 of the last 80 `rules.yaml` revisions touched
  the read set; the other 64 used to restamp ~78 files for nothing). When you DO touch the read
  set, or any `.rego`, regenerate — the derived file FIRST, then every bundle:
  ```
  python3 .github/scripts/gen-rules-opa-data.py
  find openbank-infra/gitops/components -name 'gen-*opa-bundle*.sh' | sort | xargs -n1 bash
  ```
  Commit the lot; never hand-edit a bundle or an annotation to dodge the diff. Such a PR has a
  short shelf life — merge with `--auto` (not `--admin`), or a competing governance PR conflicts it.
  Adding `data.rules.<newkey>` to a policy needs no list edit anywhere: the key set is derived from
  the `.rego` sources, and the gate fails until the subset is regenerated.
- **Never hand-roll `trivy` + `cosign attest`/`verify` in a workflow** — source
  `openbank-infra/scripts/lib/cosign-attest.sh`. It passes `--platform` (remote scans default to
  amd64 and silently miss an arm64-only image) and checks the SBOM *before* attesting; `cosign
  attest` is additive, so a green `verify-attestation` can be about an earlier build's envelope.
- **Verify realm-template changes against a local Keycloak BEFORE the PR — import runs on cold
  start only, so a broken template ships silently.** The realm-JSON shapes are version-specific
  traps: authorization policies want `config` maps (not first-class fields), a scope permission is
  `type: "scope"` (not `scope-permission`), the `token-exchange` authorization scope must be
  declared before a permission references it, the requesting client needs
  `standard.token.exchange.enabled=true`, and client descriptions cap at 255 chars (a longer one
  fails the whole import). Recipe: substitute the `__PLACEHOLDER__`s, then
  `docker run -v <file>:/opt/keycloak/data/import/realm.json quay.io/keycloak/keycloak:<version>
  start-dev --import-realm` and exercise the flow end-to-end (mint + exchange + inspect the JWT).
  Also: a test user needs `firstName`/`lastName`/`email` or KC 26's user profile answers
  "Account is not fully set up" with no hint why.

### admin-ui
- **Run vitest via `npm test`, never bare `npx vitest run`.** The `pretest` hook bakes the
  CI-generated artifacts (`governance.json`, `catalog.json`); without them the governance/finops
  taxonomy and registry-guard suites fail on an "empty manifest" that reads exactly like a
  main-branch regression but is only a missing artifact. Full green = pretest + suite, not the
  suite alone.

### Multi-agent / parallel work
- **Commit and push early — a `/private/tmp` worktree can vanish mid-edit.** Several agent
  sessions share this machine and a worktree directory is one `worktree remove`/cleanup away from
  gone; the branch ref survives, your uncommitted diff does not. Stage in small commits and push
  the branch before the PR exists.
- **Stacked PRs: merge the ADR/parent branch INTO the child instead of waiting.** When a child PR
  references files living only in an unmerged parent PR (e.g. ADR numbers the registry gate
  requires on-branch), merging the parent branch into the child makes the child's CI green
  independently; the shared files drop out of the diff as the parent lands on main.
- **A PR-queue drain has a CEILING, and "queue empty" is the wrong success measure.** Measured
  2026-08-01: 21 PRs merged in one session and the queue still went 2 -> 8, because parallel
  agents open roughly one PR every two minutes. What survives a drain is structural, not
  incidental — money-path scopes, `governance(...)`/`security(...)` types, auth changes and whole
  new services all need a human by rule, and the rest is usually someone else's live worktree.
  Stop when nothing SAFELY mergeable remains and say what is left and why; a drain that keeps
  going until the list is empty is one that started merging things it should not have.
- **A green PR can still be the wrong one to merge — classify by category before reading checks.**
  #3009 had all five required contexts green and duplicated a guard merged an hour earlier
  (#2984): same inputs, same comparison, same cited defect. Merging it would have put two copies
  of one rule in `Validate manifests`, on every PR, to drift apart later. CI cannot see that;
  only looking at what else landed recently can.
- **Parallel agents land on the SAME artifact routinely — "what else touched this file today" is
  as load-bearing a question as "are the checks green".** Three instances on 2026-08-01 alone:
  `check-probe-port-listener.py` vs `check-probe-port-has-listener.py` (#2984/#3009),
  `security.yml` (#3103/#3079), `api-fuzz-authenticated.yml` (#3024/#3079). None was visible to
  CI — #3009 was fully green — and each was found only by comparing CONTENT:
  `git diff origin/main origin/<branch> -- <file>`, or a shasum of the file on both sides.
  The three outcomes differ, so measure before deciding: identical content (#3103's `security.yml`
  matched `main` byte for byte, so merging was a no-op), a true duplicate (#3009, close one),
  or genuine divergence (#3024 differed by 101 lines — an authoring decision, not a merge).
  Note the file list `gh pr view --json files` shows is computed against the MERGE-BASE, so after
  a competing PR squash-merges it still lists the overlap as a diff even when the content already
  agrees. Read the content, not the diff.
- **A merge git calls CLEAN can still DELETE content — it reports no conflict when two sides add
  neighbouring entries to the same list, and keeps only one.** Not a conflict resolved badly:
  nothing to resolve, nothing printed, exit 0. Twice on 2026-08-02, both while merging `main` into
  a branch. `.release-please-manifest.json` went **56 entries -> 55** because #3007 added
  `openbank-tax-reporting-service` next to the branch's `openbank-delegation-service` and git kept
  one; that would have put a service with a `version.txt` on `main` that release-please does not
  know, breaking its first release. Caught only by the `release-registration consistency` gate
  (rule 3, ADR-0029: `version.txt`, `release-please-config.json` and the manifest must stay in
  lockstep) — no test and no review would have seen a line quietly not being there. The same day
  #3241's two files differed by 77 and 81 lines yet were **identical once comments were stripped**
  (40/40 and 44/44 code lines), the harmless end of the same phenomenon. **After any merge, diff the
  RESULT against what you merged into and check the scope is what you expected** —
  `git diff --name-only origin/main` and, for a structured file, count the entries before and
  after. One command; it is knowing to run it that costs. Most exposed: JSON/YAML maps every
  service registers itself in — the release manifest, `gates.yaml`, `rules.yaml` lists,
  `event-contract-baseline.txt`.
- **A finding from a CI run goes stale in MINUTES while a parallel agent is active — re-check
  before acting on it.** Three times in one session a ktlint/test failure was already fixed by the
  time the fix was written: the branch had moved (`db25c9ac8` -> `629aff176`,
  `a9381b256` -> `13335a859`) and the reported line no longer existed. Reporting a defect that is
  already gone sends the reader hunting for nothing. Diff the file at the CURRENT head, not the
  head the run used.
- **A version bump computed against a STALE base can collide with a release-please version that
  already shipped — read the version on fresh `origin/main`, never on the local base.** A branch in
  a shallow worktree saw the release manifest at admin-ui `0.91.3`, so the manual bump went to
  `0.91.4` — a version #3721 had already released the day before (tag `admin-ui-v0.91.4` existed).
  It survived only because the numbers happened to agree on merge; release-please then correctly
  proposed `0.91.5` (#3753) for the unreleased fixes, and a slightly different timeline yields a
  skipped number or a released version with no tag. The version-sync gate checks
  `version.txt == package.json`, not "is this number still free". Before `/bump`: `git fetch` (a
  shallow worktree's `origin/main` ref does not move on its own), then read `version.txt` and the
  manifest AS OF `origin/main` — same rule as acting on CI findings: the current head, not the
  base you branched from.
- **Omitting `--delete-branch` does NOT protect someone else's worktree — the repo sets
  `delete_branch_on_merge: true`.** That setting deletes the remote ref regardless of the merge
  flag, so the precaution is theatre. The local branch and working tree survive (verified on
  #2960), so nothing is lost, but if the branch had unpushed commits their only off-machine copy
  would be gone. Check the repo setting before promising the protection, not after.
- **A GitHub-hosted job wedged at RUN level reads as "queued" forever — patience fixes nothing;
  cancel the run and retrigger.** A PR sat with 16 checks pending for 2.5 h: every queued job on
  `ubuntu-latest`, GitHub status "operational", the self-hosted fleet verifiably healthy (27 runs
  in_progress on main). Diagnostic order that avoids the wrong rabbit hole: `gh run list --branch
  <b>` (RUN queued ≠ any job started), the jobs' `labels` (ubuntu-latest ⇒ the self-hosted pool
  is irrelevant — do not debug runners), then repo-wide `actions/runs?status=in_progress` to see
  whether anything moves at all. The three wedged runs were cancelled and an empty commit pushed;
  the fresh runs started in seconds and finished green in two minutes. Related, and silent: **a
  push whose delivery GitHub dropped never acquires runs retroactively** — `head_sha` stays at
  `total_count: 0` forever, so if a merge "should have" triggered CI and did not, it never will
  on its own; retrigger or push again instead of waiting.

### CI gates — exercise the failure path before trusting the green
- **Gates are DECLARED in [`.github/gates/gates.yaml`](.github/gates/gates.yaml), not written as
  workflow steps.** Add an entry (`id`/`group`/`mode`/`selftest`/`run`) and it runs; there is
  nothing to edit in `ci.yml`. Run one locally with
  `python3 .github/scripts/run-gates.py --only <id>`, a whole shard with `--group <g>`, and see
  the set with `--list`. Two things the manifest fixes that are easy to re-break: `mode:` states
  advisory-vs-enforced **outright** (inferring it from a step name is how the registration gate
  once flagged itself, #2450), and `selftest_expect:` states which exit code proves falsifiability
  — `pass` for a checker's own `--self-test` harness (every one in this repo today), `fail` when
  the command *is* the known-positive. Guessing that is silent in the safe-looking direction.
  Shards are wall-time buckets, not a taxonomy — rebalance `group:` when one gets slow, and note
  that the gate count no longer costs wall time linearly, which is the point (79 serial steps
  took `ci.yml`'s median 0.7 -> 2.4 min in four weeks on a REQUIRED check every PR pays).
- **A gate that has only ever passed is unfalsified.** Its failure path is code nobody has run, and
  it fails in ways a green/red signal cannot express. Three independent instances in one week: the
  ADR-0071 governance reporter crashed with a `TypeError` on *every* failure, so it had never once
  printed a gap (#2165); a `Trivy fs scan` step exited 1 while printing no finding, so the actual CVE
  had to be read out of the code-scanning API instead (PR #2154); an OOM'd `fleet-lint` reported a
  plain red while having silently left half the fleet unlinted — 455 actionable findings against a
  true 920 (#2177). Feed every new gate an input it MUST flag, and read what it *prints*, not just
  its exit code.
- **An advisory gate's "these findings are all benign" note is an unverified claim, and advisory
  mode is what removes the pressure to check it.** The repo already knows a gate that has only ever
  passed is unfalsified; the sharper form is that a gate can fire CORRECTLY and have its *triage* be
  the unfalsified artifact. `incluster-hostname-resolution` landed advisory with 6 findings and a
  note — repeated in `gates.yaml` and `rules.yaml` — saying all six were dead `openbank-<svc>`
  rest-client defaults "on services whose pods override them by env". Three were. The other three
  were live: settlement-service's Rollout and onboarding-service's Deployment carried no
  `*_SERVICE_URL` at all, so `openbank-balance-service:8080` / `openbank-ledger-service:8080` /
  `openbank-party-service:8090` were the values those pods actually dialled — names that resolve in
  no namespace, on ports wrong even for the right name (8103 / 8101 / 8111). Every settlement
  debit/credit and GL posting, and onboarding's abandoned-registration party lookup, went nowhere
  (#3931). The note was written from the SHAPE of the config line — a bare `openbank-` default, of a
  kind several peers DO override — instead of from the deployed manifest, and that heuristic gets
  the benign cases right and the live ones wrong. **Grade a finding against the deployed state, one
  workload at a time; "several of these are overridden" is not a fact about the rest.** The trap
  that makes it cheap to misread: `payments-services.yaml` DOES declare `LEDGER_SERVICE_URL` +
  `BALANCE_SERVICE_URL` — for transaction-service, ~2000 lines from settlement's Rollout, so a grep
  of the file confirms the wrong thing. And settlement is a `kind: Rollout`, so a Deployment-only
  parse reports it as having no workload rather than as having no override.
- **A red advisory check and a verified-benign one are indistinguishable — so an advisory finding
  needs a dated verification note or it decays into permanent background noise.** This is what makes
  the bullet above a structural problem rather than one bad call: nothing downstream of a triage note
  re-derives it, no gate covers it, and the longer it sits the more it reads as settled. If you write
  "known, benign", write what you checked and when; if you can't, leave the finding untriaged, which
  is at least honest.
- **A gate over `application.yaml` says nothing about the gitops env that overrides it — and moving
  a URL into gitops moves it OUT of scope.** `incluster-hostname-resolution` reads
  `openbank-*/src/main/resources/application.yaml` only. So the fleet-standard fix for a bad host
  (localhost dev default + real URL in the workload env) hands the checked claim to an unchecked
  file. Not hypothetical: vop-service's ADR-0171 payee-name hop declares
  `PARTY_SERVICE_URL: http://party-service.parties.svc:8100` in `payments-services.yaml` — namespace
  `parties` does not exist (it is `party`) and 8100 is account-service's port — alongside
  `ACCOUNT_SERVICE_URL: …accounts.svc:8101`, where 8101 is ledger's. Both ports transposed, live on
  the pod, invisible to the gate. When a gate's subject can be overridden, either extend it to the
  overriding layer or say in its own header which layer it does not cover.
- **Evidence cannot corroborate the layer it is DERIVED FROM — extending a gate to that layer
  silently makes it vacuous, and it still reads as green.** `check-incluster-hostnames.py` widens
  its known-good set with every host a gitops workload env dials ("if the deployment manifest
  dials it, the platform believes it exists"), which is sound while the CLAIM comes from
  `application.yaml` and the CORROBORATION from gitops — two independently-authored places. Point
  the same gate at the gitops env and that becomes the same statement twice: every host vouches
  for itself, nothing can ever be flagged, and the output still says OK. The fix is not more
  cleverness but a parameter — the caller passes what it accepts as existing, so the two layers
  cannot share a believed-set by accident, and a self-test asserts a corroborated-only host stays
  clean in one layer and IS flagged in the other (#3966). **Before reusing any "known-good" set on
  a new input, ask what that set is derived from**; the same shape appears wherever a baseline, an
  allow-list or a cache is built from the artifact it is about.
- **The derived alternative to a hand-kept list is not automatically better — measure it against
  the known cases before preferring it on principle.** This repo rightly distrusts hand-kept lists
  (a gate whose scope is one reads as PASSING when the list is short). So the instinct for the
  above was a derived rule: believe a host that ≥2 distinct workloads dial. Measured against the
  real tree it was wrong in BOTH directions at once — it would have believed two live defects
  (`tpp-registry-service.tpp.svc` and `sepa-payment-service.payments.svc`, each dialled twice) and
  flagged three real Helm-provisioned Services dialled only once. A 10-entry list, each entry
  verified with `kubectl` and **checked both ways** so a stale one fails, beat it outright. The
  rule that survives is narrower than "never hand-keep a list": never let a gate's SCOPE be
  hand-kept, because a short list then reads as full coverage — but a hand-kept list of external
  FACTS is fine when the gate fails on a stale entry, since it can only shrink by being noticed.
- **A comment that explains away a symptom is worse than no comment — it retires the question.**
  psd2's manifest annotated its TPP-registry URL "Not yet deployed -> calls 503 until it lands".
  The service was deployed and serving; the URL named a namespace that has never existed. Anyone
  who noticed the failing lookup found it already accounted for, so the note survived as long as
  the bug did. Same family as the stale-prose rule under ktlint/detekt above, but the failure is
  worse: stale prose merely misinforms, whereas an explanation of a symptom suppresses the
  investigation. When you write one, name what you VERIFIED and when — and when you fix a defect
  whose comment predicted it, correct the comment in place rather than deleting it, so the next
  reader learns the note was wrong rather than that it was never there.
- **An advisory check over a *generated* artifact is a contradiction.** On a hand-written artifact a
  red advisory check means "someone should look"; on a generated one it means "the committed document
  does not match reality" — there is no judgement left to exercise, so advisory just makes the drift
  mergeable. `eu-ai-act-registry` went red twice on #2156 and the PR merged anyway, leaving the EU AI
  Act inventory omitting an AI system until it was regenerated (#2216).
- **A third-party URL in `application.yaml` is unfalsifiable by every test layer this repo has —
  only fetching it can be wrong out loud.** A unit test stubs the client, an IT serves a local
  fixture, and a consumer pact answers whatever path it is asked for (the #2283 asymmetry). So
  `openbank-fx-service`'s ČNB fixing URL was a 404 — one path segment short, `kurzy-devizoveho-trhu`
  appears TWICE — for the entire life of the service, and every layer stayed green: a 404 IS a valid
  HTTP response, so the rest-client succeeded and the circuit breaker never opened; ČNB serves it as
  a 58 KB HTML page, the parser rejected that, and the scheduler's `catch` swallowed it into one
  ERROR line. Downstream, `FxRevaluationService` found no valid rate, logged "skipping its
  revaluation leg" and returned `posted = false` — a successful-looking run of a job that revalued
  nothing. The only evidence anywhere was a table that stopped growing, and **nothing alerts on a
  table not growing**. Two transferable rules. (a) **Probe the payload's SHAPE, never the status
  code** — a 200 proves a server answered, not that the answer is the feed. (b) **The probe must
  read the URL out of the committed config, never keep its own copy** — a second copy moves with the
  first and keeps passing against a URL the service does not use. `check-external-feeds.py` +
  `external-feed-watch.yml` do both (drift half enforced in `Validate manifests`, liveness half
  daily and escalating, never merge-blocking). Its first run found a *second* dead ČNB URL,
  customer-edge's bank registry, silently masked by an embedded fallback (#2204).
- **The gate that never existed beats the unfalsified one: check whether anything reads the artifact
  at all before assuming a green covers it.** This repo is a MULTI-LICENSE tree — Apache-2.0 at the
  root, an AGPL-3.0-only open-core subset (ADR-0136 + ADR-0181/0193) — and *nothing* compared the
  per-file SPDX headers to that declaration: no `reuse-tool`/`licensee`/`license-eye`/`scancode`
  anywhere, and the OpenSSF Scorecard `License` check only asks whether a recognized licence file
  exists at the **root**, never what the files say. So four descriptions of the split drifted in
  silence: 12 AGPL modules in the tree, 10 in `rules.yaml`, **4** in the published `NOTICE`/`README` —
  which told every downstream adopter the other 8 were Apache-2.0 (#2280). Two transferable rules.
  (a) **A licence claim about a *distributed* artifact needs a gate, not prose** — all 12 carry a
  `version.txt`. (b) **Never let a published doc keep its own copy of a list that lives in
  `rules.yaml`** — enumerate once, point at it everywhere else; the second hand-maintained copy IS the
  drift. Root cause was `scripts/add-license-headers.sh` hardcoding Apache-2.0 for every path, the
  ADR-0136 follow-up nobody did — a stamping script that is not path-aware in a multi-license tree
  manufactures the violation. Now `rules.yaml: agpl_modules` is canonical and
  `.github/scripts/check-license-headers.py` enforces every declaration against it. Frozen headers
  (an applied Flyway migration — editing one breaks its checksum and the service dies at boot) go in
  `REUSE.toml` with `precedence = "override"`, never edited in place.
- **A text-matching guard flags the very text that explains the bug it exists to catch — decide that
  precedence before the first run.** Three in one session, each caught only by running the new guard
  against a case it must NOT flag: `check-roles-allowed-realm.py` reported finrep as still broken
  because #2403's fix quotes the old `@RolesAllowed("SERVICE", …)` in a KDoc (fixed by stripping
  comments — Kotlin's block comments NEST, so mirror that or a KDoc containing `/*` closes early);
  `check-advisory-gate-registration.py` flagged **itself**, its own step being named
  `advisory-gate registration (…, enforced)` (fixed by making `enforced` in a step name beat
  `advisory`); and the `platform-admin` prose in `DevOpsResource` survived the sweep that renamed
  the annotation, because comments are stripped *by design* (#2450). Generalize: a guard over source
  text needs an explicit rule for code-about-code, and stale prose naming a dead identifier is
  invisible to it forever — grep the prose separately after any vocabulary rename.
- **The same collision runs the other way, and that direction is silent: a check greps a file for the
  string it wants, and matches the COMMENT that explains why the string is there.** A false positive
  announces itself; this one reads as a pass. On #3072 a test asserted `middleware.ts` excludes
  `/api/gate` with a whole-file `toMatch(/api\/gate/)` — the exclusion is explained by a five-line
  comment directly above it that names the path three times, so deleting the exclusion itself left
  the test green. Fix: strip comments, then assert against the **construct**, not the file
  (`config.matcher`, the annotation value, the specific key) — a whole-file grep can never
  distinguish the thing from the prose about the thing. Same PR, same class, second instance: an
  Ingress/allow-list agreement check built its tool set with `[a-z0-9-]+`, so a typo'd
  `?tool=grafanaX` matched on the `grafana` prefix and it reported agreement with a tool the gate
  does not know. Both were found only by feeding the assertions the exact broken input they exist to
  reject — a new assertion that has only ever seen the correct file is unfalsified.
- **An "advisory" gate is usually advisory INSIDE the script, not via `continue-on-error`** — 11 of
  12 here print `::warning` and exit 0 unless passed `--enforce`. A sweep for `continue-on-error:
  true` therefore finds one and silently reports the other eleven as enforced. Check both forms
  before claiming anything about what blocks (#2392).
- **Regenerating the OPA bundles must be the LAST step before pushing a `rules.yaml` PR, and the
  "run twice, expect no diff" check does not prove you did that.** Idempotency only proves the
  generators are stable against whatever `rules.yaml` said at that moment: on #2457 the bundles were
  regenerated, verified idempotent, and *then* a duplicate-key fix landed in `rules.yaml` — all 65
  files embedded the superseded text and the OPA gate went red on every one. Re-run after the final
  edit, not after the first.
- **`rules.yaml` is never linted by CI** — the yamllint step's scope is `openbank-infra .github`. A
  duplicate key there is silently resolved by SnakeYAML keeping the LAST occurrence, so a new value
  added above an existing one is dropped with no error anywhere. Diff yamllint finding *sets*
  against `origin/main` when editing it (that is the only thing that caught it on #2457).
- **A `paths:`-filtered workflow can never be a required status check — so its red is advisory no
  matter how the job is written.** GitHub holds a required context that does not report as
  permanently "Expected — waiting for status", so requiring a path-filtered workflow blocks every PR
  that touches none of its paths. That is why all five required contexts (`all-green`,
  `Validate manifests`, `Gitleaks`, `issue-hygiene`, `OPA policy gate`) run unconditionally. The
  established fix is not to require the small workflow but to put the **binding copy of the script**
  in the unconditional `Validate manifests` job in `ci.yml` and keep the path-filtered workflow as a
  fast echo — what `adr-registry` already did, and what `agent-charter-registry` and `eu-ai-act` now
  do too. Corollary: reaching for the ruleset is usually the wrong instinct here, since the in-repo
  fix ships in the same PR as the gate and needs no GitHub-side config change.
- **A PR that is conflicted AT CREATION never gets `refs/pull/<n>/merge`, so NO `pull_request`
  workflow is ever created — and zero checks renders as "waiting", never as broken.** Not skipped:
  absent. Nothing reports, the required contexts can never be satisfied, and there is no run to
  re-run, so the PR is unmergeable forever and eventually gets closed by whoever supersedes it.
  `DIRTY` alone is NOT the predictor and that is what makes it hard to see — a PR that became
  conflicted *later* keeps the merge ref it was born with along with its full check set (#3058:
  DIRTY, 13 runs). Measured 2026-08-01: four admin-ui deploy PRs with 0 runs, three closed having
  deployed nothing, while every one that merged had 18. The fix on an affected PR is any new head —
  merging `main` in took #3183 from `merge ref 0 / 0 runs` to `1 / 12`, and it then merged. Root
  cause class: a bot that checks out `github.sha` and branches from it while `main` moves under it
  (#3194). Probe with `git ls-remote origin refs/pull/<n>/merge` and a run count per head SHA —
  a PR list, a check list and `gh pr view` all look normal.
- **`/actions/runs/<id>/jobs` returns the LATEST attempt — anything reacting to `workflow_run`
  must query `/actions/runs/<id>/attempts/<n>/jobs`.** The unscoped endpoint silently answers
  about a *different* run than the event fired for, so a guard that inspects job or step
  conclusions reads a re-run's green attempt and declines to act — no error, just a no-op that
  only happens once a human has touched the run first. Caught in #2892 by testing the real
  script against a fixture that had been re-run by hand: it reported "no spot-kill signature"
  for a run whose attempt 1 was unambiguously spot-killed. If the job's `if:` already pins
  `run_attempt`, pin the query to match.
- **A job log CONTAINS the step's own `run:` script, so grepping it matches strings that never
  executed.** GitHub prints the script into the log header, which means every `echo "…"` in it
  appears whether or not that branch ran — a naive `grep 'treating it as a real failure'` reports
  a hit on *every* run of the step, forever. Read only the output after the **LAST**
  `##[endgroup]` (there are several; the last one closes the `Run …` block):
  `awk '/##\[endgroup\]/{n=NR} {a[NR]=$0} END{for(i=n+1;i<=NR;i++) print a[i]}'`.
  Validated on a real pair: the naive grep reports 1 hit on a run where the clean pipeline
  reports 0, and the 0 is right. Cost two wrong conclusions on 2026-07-31, the second from a
  monitor built minutes after diagnosing the first — the fix is easy, noticing is not, because
  a false positive here looks exactly like the success you were hoping for. Prefer structured
  data outright where it exists: step conclusions from
  `gh api .../actions/jobs/<id> --jq '.steps[]'` cannot be spoofed by the script listing.
  In a MULTI-STEP job the last-`##[endgroup]` trick is not enough — it lands you after the final
  step, not inside the one that failed. There the discriminator is **substitution**: real output
  has the variable expanded, the echoed script still has the literal. `grep 'failed to boot'`
  matched an `echo "::error::[${svc}] failed to boot"` that never ran; `grep 'failed to boot' |
  grep -v '\${'` found the one line that did. Took three attempts on #3024 *after* the bullet
  above was already written, so treat "my grep found it" as a hypothesis until the match shows a
  value the script could not have contained.
- **A gate is only as reachable as the JOB it sits in — check the job's `if:`, not just the
  script.** `gates.yaml` exists so gates run unconditionally; a gate written as an inline `run:`
  step inherits its host job's conditions instead, and a conditional host silently narrows the
  gate's scope to something nobody declared. `check-dockerfile-no-build-stage.py` — the #3016
  gate that owns per-service Dockerfile shape — lived in `ci.yml`'s `ui-build`, whose
  `if: needs.changes-ui.outputs.changed == 'true'` fires only on `openbank-admin-ui/`,
  `*/governance.yaml` or the governance schema. **So it could not run on a Dockerfile-only PR**,
  the exact change it exists to catch. Not skipped-and-reported: the job is *absent*, the
  aggregate check is green, and nothing anywhere says a gate was not consulted. Measured on
  #3629 (53 Dockerfiles edited): `Validate manifests` SUCCESS with 3 steps — it is an aggregator
  over the `gates (...)` shards — and `Admin UI build` SKIPPED. Read the *steps* of the job that
  claims to have run your gate (`gh api .../actions/jobs/<id> --jq '.steps[]'`); a green job name
  is not evidence your step was in it. Fix is always the same: declare it in
  `.github/gates/gates.yaml` with a `group:`, never as an inline step in a conditional job.
- **"Nothing reads this file" is a claim about the whole repo, not about the pipeline — grep
  before you delete a declaration.** The per-service `openbank-*/Dockerfile` files are documented
  as declaration-only with `EXPOSE` the single live field (#3016), so the tidy fix for a stale
  `FROM` looks like deleting it. It is not:
  `openbank-admin-ui/scripts/generate-cluster-topology.mjs` parses
  `openbank-ledger-service/Dockerfile` in `imageFacts()` and renders the base image into the
  /docs/cluster dossier (ADR-0081) — and when it cannot parse one it falls back to a HARDCODED
  `eclipse-temurin:25-jre-alpine` literal, so removing the declaration would have resurrected the
  fiction in the UI instead of retiring it (#3354, #3630). A second reader that is a *generator
  in another tree* is invisible from the file, from the deploy pipeline, and from the gate that
  owns the file's shape.
- **An oversized `run:` script makes the WHOLE workflow unparseable — and GitHub says nothing.**
  Not a size error: the file stops being readable, every push yields a run with ZERO jobs titled
  after the file PATH, `name:` is never read, and it reads as an ordinary red run. It kills the
  workflow for everyone, not just the author — #3135 blocked every contributor's deploy until it
  was reverted (#3139). Ceiling measured by bisecting pushes against GitHub: **20054 chars
  accepted, 20654 rejected**, per STEP not per file (a 95295-byte control parsed fine while no
  single step crossed). The first attempt died of PROSE: 3240 characters of added comments took
  one step from 17414 to 20654; the same logic re-landed at +318 with the reasoning moved into
  script headers. Nothing here sees this class — PyYAML, a strict duplicate-key loader, actionlint
  and yamllint were all clean on the very file GitHub refused — so
  `check-workflow-run-step-size.py` (gate `workflow-run-step-size`) now enforces 19000. Rule:
  **prose belongs in `.github/scripts/*` headers, never in a `run:` block.**
- **Validate a workflow against GITHUB, not against a YAML parser — the oracle is free.** On a
  non-main branch a VALID workflow with `branches: [main]` produces NO run at all; an INVALID one
  still produces a failed run, because GitHub cannot apply a filter it could not parse. Push the
  candidate to a throwaway branch and count runs: zero means accepted. Validate the oracle both
  ways first (a known-bad file MUST produce a run), then bisect with it — that is how the 20054/
  20654 boundary above was found, in ten pushes, after local tooling had said "clean" three times.
  Re-run it after any rebase: "it parsed an hour ago on a different base" is a different claim.
- **`gitleaks` matches the SHAPE `curl -u "$USER:$PASS"`, and it scans the push RANGE, not the
  tip.** The rule `curl-auth-user` cannot tell a variable reference from a literal, so a NEW
  occurrence fails the required check even with no secret in it — the identical inline form
  already in auto-deploy.yml survives only because it predates the scanned diff. Assemble the pair
  into a variable first. And a fixup commit does NOT clear it: gitleaks reported "2 commits
  scanned, leaks found: 1" against the already-fixed tip, so the offending commit has to leave the
  branch history (squash to one commit, force-push with lease, re-sign).
- **A `concurrency.group` that interpolates a LIST silently stops the job being created once the
  list is big enough — and an absent job cannot honour its own `if:`.** `auto-deploy.yml`'s
  `gitops-pr` keyed its group on `needs.changes.outputs.services` verbatim. A change under
  `openbank-libs-*` rebuilds the whole fleet, so that expanded to ~1436 characters, and the job was
  then **never instantiated**: not skipped, absent — no job, no check-run on the commit, and
  `needs.gitops-pr.result` reading as a failure downstream. That job carries
  `if: always() && … != 'cancelled'` precisely so a partial `can-i-deploy` failure still deploys
  the
  subset that passed (#846), so the whole fleet build was built, pushed, signed, attested and then
  discarded — worst in the highest-stakes case. Correlation over 20 runs was exact: 53 services
  (~1436 chars) → job absent (three separate times); 12 (~322) and 1 (~30) → created. Key the
  group
  on a short digest of the **sorted** set (`jq -S -c 'sort' | sha256sum | cut -c1-12`), which keeps
  the same-set/disjoint-set semantics the list was there for and bounds the name at ~50 chars
  (#3082, fixed #3084). Generalize: anything interpolated into a `concurrency.group` must be O(1) in
  the size of the fleet — a group name is not a place to carry data.
- **`gh pr merge` refuses on `mergeStateStatus=UNSTABLE` even when every REQUIRED check is green.**
  A non-required check that is red (here `CodeQL (java-kotlin, manual)`, failing with "could not
  process any code written in Java/Kotlin" on a PR that touches only a workflow YAML — there is no
  Java to analyse) leaves the PR mergeable by the ruleset but unstable to `gh`, which then suggests
  `--admin`. Do NOT reach for it: `--squash --auto` is the documented non-override path and merges
  as soon as the required contexts pass. On this repo that is immediate, since
  `required_approving_review_count: 0` — it returns with `autoMergeRequest` null and the PR
  already
  MERGED, which reads like it did nothing.
- **A gate that calls a network API must be falsified with the CREDENTIAL CI will use, not the
  one on your laptop — and on GitHub a 404 does NOT mean "gone".** A job's `GITHUB_TOKEN` is
  scoped to this repository, so a **private** repo in the same account answers `404` byte for
  byte like a deleted one; `gh` on the owner's machine sees it fine. `check-stale-comment-
  references.py` shipped a rule reading 404 as "this repo no longer exists", passed every local
  run, and went red in CI on `JiRaska/openbank-app` — private, alive, correctly referenced. There
  is no API field that separates the two, so that half of the rule was **dropped** rather than
  tuned; only `archived` is claimed, since observing it requires read access and is therefore
  unambiguous. Generalize: before asserting anything from an API response, ask which identity the
  gate runs as and what that identity *cannot see* — a permission-shaped absence is
  indistinguishable from a real one, and it always fails in the direction of a confident wrong
  answer. The repo already knows "a gate that has only ever passed is unfalsified"; the sharper
  form is that a gate falsified under the **wrong identity** is unfalsified too.
- **Validate the PROBE, not the command inside it — in zsh a `for x in $VAR` loop runs ONCE.**
  zsh does not word-split an unquoted parameter (bash does), so a sweep written as
  `LIST="a b c"; for b in $LIST; do git show-ref --verify --quiet "refs/heads/$b" …` tests one
  ref literally named `a b c`, finds nothing, and reports the estate clean. Measured
  2026-07-31 on a leftover-branch sweep: it printed `0 of 17` while five refs were sitting
  there. Use `${=VAR}`, a real array `VAR=(a b c)`, or `while read` from a heredoc — the last
  is safest since it also survives names with spaces.
  The transferable half is the failure of the check on the check: `git show-ref` *was* validated
  against a known-positive and passed, because the bug was in the LOOP, not the command. A
  component test is not a probe test. Feed the whole construct a case it must flag — here, a
  branch you know exists — and only then trust its silence.
- **Test the script EXTRACTED from the workflow, not a retyped copy of it.** A transcription is
  a different program: the first harness for #2890 used `[ … ] && { … }` where the real step
  used `if` blocks, and under `set -e` those differ on exactly the passing case. Parse the YAML
  and dump `.jobs.<job>.steps[<n>].run` to a file, then run that. When the step calls something
  destructive (`gh run rerun`, a deploy, a delete), stub the binary on `PATH` — **and validate
  the stub against a known-positive first**, or a silent passthrough runs the real thing.

### CI / bot commit signing
- **What signs a bot commit is the *endpoint*, not the token — and `main-protection` enforces
  `required_signatures`.** GitHub auto-signs the GraphQL `createCommitOnBranch` mutation and the
  Contents API, and only for a GitHub **App** token — never for a user PAT, and never for the Git
  Data API (`POST /git/commits`), whose `signature` field is caller-supplied. So
  `peter-evans/create-pull-request`'s `sign-commits: true` signs *once given an App token* (#1276),
  while **release-please can never sign whatever token you give it**: it delegates to
  `code-suggester`, which calls `octokit.git.createCommit` (Git Data) with no signer (upstream
  release-please-action#1171/#1124, both open; #1289 re-signs the commit afterwards instead).
  Unsigned + `required_signatures` = a PR stranded with green checks and auto-merge armed, which
  reads as healthy from every angle except the one nobody checks. Verify the *branch* commit —
  never the squash commit on `main`, which GitHub signs itself and always reads `verified=true`,
  proving nothing: `gh api repos/<owner>/<repo>/pulls/<N>/commits --jq '.[].commit.verification'`.
- **A guard that `setFailed`s must go where a failure costs nothing downstream.** Fail the *last*
  step of a job with nothing after it (`always()`, since `setFailed` skips later non-`always()`
  steps); if the job has dependents (`needs:`), make the guard its own job instead — failing in
  place would skip them. In `release-please.yml` that would have stripped an already-cut tag of its
  evidence bundle.
- **`git rebase` drops the signature off EVERY rebased commit, and `--amend -S` only fixes the
  tip.** A 3-commit branch rebased and re-signed with `--amend` still strands on
  `required_signatures` with two unsigned commits behind the tip. Re-sign the whole range:
  `GIT_SEQUENCE_EDITOR=true git rebase -i --exec 'git commit --amend --no-edit -S' origin/main`,
  then verify via the API (`gh api .../pulls/<N>/commits --jq '.[].commit.verification'`) — not
  `gh pr view`. **Prevent it up front:** `git config --global commit.gpgsign true`, so every
  `rebase`/`amend`/`commit` signs automatically and the range never desyncs. **Diagnosing the
  stranded PR:** the symptom is green checks + `autoMerge=true` but `mergeStateStatus=BLOCKED` and an
  empty `reviewDecision`; `gh api repos/<owner>/<repo>/branches/main/protection` returns `404 Branch
  not protected` — that is expected (the gate is the *ruleset* `required_signatures`, not classic
  protection), not a reason to reach for `--admin`. Confirm the tip's signature with
  `git log -1 --format='%G?'` (`N` = unsigned) before pushing.
- **There is no `merge_group:` trigger anywhere in the required-check workflows — and there cannot
  be one.** GitHub merge queue requires an **organization-owned** repo; this one is owned by a
  personal account, so adding a `merge_queue` rule to the ruleset returns `422 Invalid rule
  'merge_queue'` (issue #1465 has the isolation proof). Support for it was built (#1467), then
  deliberately **reverted** (#1504): an unreachable trigger is not a harmless spare part — nothing
  exercises `services-ci`'s `merge_group` base-selection logic, so it would silently drift from the
  PR-path logic it must mirror, and the day someone enabled a queue it would report stale-but-green,
  the exact vacuous-green failure class this codebase works hard to avoid elsewhere. Don't re-add it
  without re-checking the 422 first. Consequence: the frozen-`base.sha` race (#481 × #524) stays
  open, and `strict_required_status_checks_policy` (require branches up to date) is the only
  remaining lever that works on a personal account.

### Self-hosted runners share the machine with a human
- **A CI job that leaves `GRADLE_USER_HOME` unset does not merely *share* the workstation's
  `~/.gradle` — it PRUNES it.** `gradle/actions/setup-gradle` runs `cache-cleanup: on-success` by
  default: "remove any stale/unused entries from the Gradle User Home", where *unused* means
  "unused by this one CI build". Pointed at a developer's home it deletes artifacts local builds
  depend on and truncates files a concurrent local build is reading, so the local build reports
  `Dependency verification failed … expected X but was Y` for an artifact whose cached bytes match
  `verification-metadata.xml` and Maven Central **exactly**. That reads as cache corruption, and no
  amount of cache repair fixes it because the damage recurs on the next CI job — the diagnosis only
  closes when you notice the failures track the runner being busy. Every Gradle job that can land on
  a self-hosted runner needs its own home; `_service-ci.yml` resolves one per service in a step
  (a `workflow_call` job cannot reference the `env` context in a job-level `env:` block).
  `.github/scripts/check-gradle-user-home-isolation.py` enforces it in `Validate manifests`.

### Dependency graph & PR-time CVE gating
Rationale + what does *not* cover it: `rules.yaml: dependencies.pr_time_cve_gate` (authoritative).
- **`dependency-submission.yml` MUST keep its `pull_request` trigger.** `dependency-review` only
  diffs *submitted* graphs and GitHub does not parse Gradle natively, so deleting that trigger as
  "redundant CI" leaves `block_on_cve_severity` green while checking nothing (#1421).
- **`retry-on-snapshot-warnings` retries even when no submission is coming** (GitHub always answers
  `No snapshots were found for the head SHA`) — armed unconditionally it burnt >11 min on PRs
  touching no manifest. Arm it only on dependency-touching PRs, and size the window from the
  measured ~24 min end-to-end, not the 734 s resolve (the documented 600 s cannot work here).
- **Touching ANY `build.gradle.kts` — even to add a comment — costs a ~11 min fleet resolution.**
  Both `dependency-submission.yml` and `dependency-review.yml` filter on `**/build.gradle.kts`,
  and a path filter cannot inspect intent: a comment-only edit changes no dependency yet arms the
  whole submit-and-wait path. So a drive-by comment in a service's build file is not free, and
  batching a real manifest change with unrelated formatting costs nothing extra — but doing the
  reverse (splitting a comment tweak into its own PR) buys an 11 min job for zero information.
- **A red push-triggered workflow on `main` is addressed to nobody.** `dependency-submission` died
  of `Java heap space` for three days, red every run, while Dependabot and the gate quietly read the
  stale graph — neither goes red when it is stale. Anything whose *output* others depend on needs an
  escalation path (raise/refresh an issue, as `fleet-attestation.yml` and this one now do). Its heap
  is a ratchet with nothing measuring it — expect to raise it again.
- **Fresh-cache builds fail dependency-verification on artifacts nobody has hashed yet — fix
  scoped, not by blanket regen.** `./gradlew <tasks> --write-verification-metadata sha256`
  rewrites the whole file: reorder noise, unrelated components, and (for test-classpath graphs)
  dozens of entries. The house norm (#2718, #2743) is additions-only — compute sha256 from the
  isolated cache and insert just the missing components with `origin="Maven Central"`. Symptom:
  `Dependency verification failed for configuration ...` on a cold `GRADLE_USER_HOME` (new runner,
  isolated cache, CodeQL's tracing build).

### Reviewing a diff
- **Use 3-dot diff for pre-merge review:** `git diff origin/main...origin/<branch>` is the actual
  squash delta; 2-dot includes main's post-divergence commits and makes stale branches look like
  regressions.

### ADR registry
- **Order is `gen-index.sh` → COMMIT → `check-adr-registry.sh`, never regen → check → commit.** A
  failing check restores the three derived files (`README.md`, `DIGEST.md`, `index.json`) to HEAD
  on exit, so committing after a failed check commits the *restored* content — and the next regen
  then disagrees with what you committed, failing the gate on content you never wrote (#3983).
- **`git mv` + a follow-up edit = a PURE RENAME commit — the edit stays unstaged.** When
  renumbering an ADR after a number collision with main, `git mv` the file, edit the H1, `git add`
  the file AGAIN, then commit — or the registry gate fails with "H1 says number N but filename is
  M" on a file that looks correct in your working tree (#3983).

### gh CLI
- **Always write a PR/issue body to a file and pass `--body-file`. Never `--body` with an
  inline string.** Not "when it contains backticks" — *always*, because you decide the flag
  before you know what the final text will contain. Backticks in a double-quoted string are
  command substitution, so zsh *executes* them and silently drops them from the text: on #2890
  `` `steps.deps.outputs.changed == 'true'` `` was run as a command and the published body read
  "PRs ()" — valid markdown, no error, nothing in `gh`'s output to notice. The failure is
  invisible unless you re-read the *published* body, which nobody does.
  This bullet was written after the first occurrence and the same mistake happened **twice more
  the same day** (#2919's correction comment lost a clause to `command not found:
  auto-deploy.yml`). Knowing the rule does not help, because the moment of choice is when the
  text is still an idea. Only the unconditional habit does. Same trap for `--comment`,
  `gh release create --notes` and `-f body=`; for an edit, `-F body=@file`.
  If you did use `--body`, re-read what was published (`gh pr view <n> --json body`) — grep it
  for `()` and for the phrases you meant to include.
- **`gh` needs a repo context: outside a checkout it fails with `failed to run git: fatal: not
  a git repository`,** which reads like a content or permissions problem rather than a cwd one.
  Pass `-R <owner>/<repo>` explicitly in any script whose working directory is not guaranteed —
  and note a verification command failing this way returns *nothing*, which is easy to misread
  as "the thing I was checking is absent".
- **`--delete-branch` on a stacked parent CLOSES the child PR, and that is not reversible.**
  Deleting the base branch makes GitHub close every PR targeting it; a closed PR whose base is gone
  can be neither reopened nor retargeted — `reopenPullRequest` answers `Could not open the pull
  request` and `updatePullRequest` answers `Cannot change the base branch of a closed pull request`.
  The only way back is a rebase onto the new `main` and a **new** PR, losing the number, the review
  history and the comment thread. The failure is silent at merge time: `gh pr merge` prints nothing
  about the child and the merge itself succeeds, so it reads as clean from every angle you would
  normally check. Either retarget each child to `main` *before* merging the parent, or merge without
  `--delete-branch` and clean up once the whole stack has landed. `--delete-branch` is only safe on a
  leaf — check `gh pr list --base <branch>` first (#3055 closed #3063, reopened as #3111).

### API contract (ADR-0048)
- **Two racing spec PRs can both claim the same `info.version` — and both pass the gate.** The
  api-contract gate classifies against the PR's *creation-time* base
  (`github.event.pull_request.base.sha`), so a competing bump that merges first is invisible to
  the second PR: it lands with new endpoints under an unchanged version (#481 vs #524 on the
  ledger spec, corrected by #534). After any competing `openapi.yaml` change merges — including
  when you resolve a merge conflict against `main` — re-check `info.version` against the *current*
  `main` and re-bump; whoever lands second takes the next version. A matching version line merging
  "cleanly" is the trap: git sees identical text, not a taken version.
- **The same trap fires from an ALREADY-MERGED PR, which is the direction that gets missed.**
  Anticipating it is not the same as checking for it: the instinct is "am I racing anyone?", and that
  scans *open* PRs — but the number is just as easily consumed by something that landed while your
  branch was open and is now invisible in the PR list. Measured on #3055, where the numbering had
  been guarded carefully between two stacked PRs and the gate still went
  `1.2.0 -> 1.2.0` because #3037 had merged. Read the version off live `main`, never off your
  branch's base, and re-read it after every rebase:
  `git fetch origin main && git show origin/main:<svc>/src/main/resources/openapi.yaml | grep -m1 '^  version:'`.
  In a stack each PR takes the next number in order — and a child's diff against `main` spans the
  whole stack, so point its PR base at the parent branch or the gate sees a multi-minor jump.

## Capturing what we learn

A corrected non-obvious mistake or a hard-won lesson belongs **in the repo**, where every contributor
reads it. Route each by kind (authoritative spec: `rules.yaml: knowledge_capture`):
- **Operational footgun** → a one-line bullet in the matching section above, or the relevant
  `<service>/CLAUDE.md`: symptom + fix, imperative.
- **A hard, checkable rule** → encode it in `rules.yaml` and, where feasible, a CI guard, so it is
  *enforced* not merely documented. Summarize the human-readable form here.
- **A recurring workflow** → a skill under `.claude/skills/`.

Always act on the authoritative source on `origin/main` — not a local snapshot or cached page. The
repo is the single source of truth.

## Where things are

- Rules (authoritative): `openbank-libs/governance/rules.yaml`
- Architecture decisions: `docs/adr/` (governance is 0029/0030).
  - **Reading them: start at `docs/adr/DIGEST.md`, not at the ADRs.** It is the whole
    decision history as one line per ADR (~16k tokens vs ~400k for the fleet). Read it,
    then open only the ADRs it points you at. Grepping the fleet finds whichever ADR
    matched a keyword, not the one that decided the thing.
  - **Writing one: `docs/adr/new.sh "Title"`.** Never hand-copy an existing ADR — the
    header is a validated YAML front-matter block (`docs/adr/SCHEMA.md`), with closed
    enums and a closed tag vocabulary (`docs/adr/tags.txt`), and `new.sh` also allocates
    a collision-free number. Fill in `tags` and `summary`; the scaffold's placeholders
    are rejected by CI on purpose.
  - Before pushing: `bash docs/adr/gen-index.sh && bash .github/scripts/check-adr-registry.sh`.
    `README.md`, `DIGEST.md` and `index.json` are DERIVED — never hand-edit them.
- Shared runtime plumbing (ADR-0122 domain/runtime split): pure domain logic —
  security, audit envelope, outbox ports, idempotency store — lives in
  `openbank-libs-domain/src/main/kotlin/com/openbank/libs/`; framework-touching
  code — `web/ServiceInfoResource`, audit publisher, outbox dispatchers,
  idempotency impl — lives in `openbank-libs-runtime/src/main/kotlin/com/openbank/libs/`.
- Per-service specifics: that service's own `CLAUDE.md`.
