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
  value throws `SRCFG00040` at boot. Use `Optional<String>` + `defaultValue`. **`defaultValue = ""`
  is NOT a way to make one optional** — measured 2026-08-21, SmallRye answers `SRCFG00014: required
  but it could not be found in any config source`, so an empty default leaves the property exactly
  as required as no default at all. Nor does bean scope help: validation happens once at STARTUP in
  `ConfigRecorder.validateConfigProperties`, over every injection point, so an `@ApplicationScoped`
  bean's laziness defers nothing and the service simply does not boot. This bullet had existed for
  months and audit-service shipped the shape anyway (#5844) — prose is not a control, so
  `check-configproperty-supplied.py` now enforces both halves, plus the sibling case where
  `application.yaml` DEFINES the value as empty (`key: ${VAR:}`), which no `defaultValue` rescues.
  Two things made #5844 cost days rather than minutes, and both generalise: a module CI never
  rebuilt stays red on `main` unseen, and **a service that cannot boot reports its tests as
  SKIPPED** — that module read `1 failed, 15 skipped` instead of 143 failures, and a skip count
  scans as a pass. Read the SKIPPED number, not just the failure count.
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
- **A missing row is TWO different defects, and the POOLED SEQUENCE tells them apart without any
  logging.** "The log says it happened and there is no row" is either an INSERT that was never
  attempted (an upstream branch returned early) or one that was attempted and lost (rolled back,
  or never reached the WAL) — opposite fixes, and no error line distinguishes them because neither
  path logs. Every table here carries a Hibernate `<table>_seq` with `increment_by 50`, and a JVM
  draws a block **only** when allocating an id for a `persist`, so the gaps in the surviving ids are
  a free record of how many inserts were attempted. On #4512 the ids either side of the missing
  window were 1351 and 1601 with exactly four blocks burned between them (1401/1451/1501/1551) —
  one per fan-out — which converts "no row" into "four inserts were attempted and lost" in a single
  `select last_value from pg_sequences` plus a `min/max(id)`. Read it *before* enabling SQL logging;
  it is retrospective (the sequence state survives long after logs and metrics age out) whereas
  `log_statement` only ever answers about the future. The companion query for the same class of
  defect is the orphan join — an outbox row whose aggregate id has no row in the entity table.
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
- **An entity property with no explicit `@Column(name = ...)` asks for a column no migration here
  ever creates — and it is wrong for every MULTI-WORD property while right for every single-word
  one, so the class reads as internally consistent.** Hibernate's implicit name is the property
  name verbatim and Postgres folds an unquoted identifier to lower case, so `createdAt` resolves to
  `createdat` while the migration wrote `created_at`. Only six services set
  `physical-naming-strategy: CamelCaseToUnderscoresNamingStrategy`; in the other ~46 the name must
  be spelled out. consent-service's `SuppressionEntity` had six of ten columns wrong, and
  `GET /api/v1/suppressions/party/{partyId}` answered **500 on every call from the day it shipped**
  (`SQLGrammarException: column se1_0.createdat does not exist (42703)`). Nothing could see it: the
  unit tests mock the repository so no SQL is issued, health probes never touch the table so the pod
  stays Ready, and the two sibling entities in the same package DO name their columns — so the file
  next door looked like the convention was being followed. It took schemathesis fuzzing the running
  service to find it. `check-entity-column-names.py` enforces it (gate `entity-column-names`).
  Note what that gate must NOT do: deriving "does this column exist" from the DDL needs real parsing
  (partitioned tables, custom enum types, ALTER/RENAME chains) and two attempts produced 12 and then
  ~40 false findings against correct code — including all of sanctions-service, whose columns are
  named explicitly in the Kotlin use-site form `@field:Column(name = ...)` that a `@Column`-only
  regex cannot match. A gate that cries wolf about correct code is worth less than nothing, so it
  checks the convention, which is fully decidable.
- **A NUL byte (U+0000) reaching Postgres is a 500, and it arrives ESCAPED — so a raw-byte scan of
  the request finds nothing and reports clean.** Postgres cannot store U+0000 in any `text`/`varchar`
  column (`invalid byte sequence for encoding "UTF8": 0x00`, SQLState 22021); Hibernate raises it at
  flush, far past every handler, so `GenericExceptionMapper` renders a well-formed `INTERNAL_ERROR`
  body — which is why "it did not crash" and "the response parsed" both pass against it. Rejected
  fleet-wide now by `libs-runtime`'s `NulByteGuards` (#5913), and two things about it generalise.
  First, the carrier: five services, **six** operations, and two of them carried the NUL in a QUERY
  PARAMETER, not a body — those requests have no entity at all, so a Jackson-only guard is
  structurally green about them. Enumerate the carriers from the fuzz artifacts before choosing where
  a guard goes. Second, the wire form: inside JSON the character is the six ASCII characters of a
  `\u0000` escape, legal JSON that Jackson decodes happily, so only the DECODED value answers the
  question — scanning the stream for byte `0x00` sees nothing, and scanning for the escape as text
  false-positives on a doubly-escaped backslash. Sibling of the `value too long` / duplicate-key
  cases in the same issue, which are deliberately NOT this: a length limit is per-column and a
  `ConstraintViolationException` is 409-or-400 depending on which constraint, so neither is decidable
  fleet-wide. U+0000 is, because no valid request can carry it and no column can accept it.
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
- **A "successful no-op" result and a real success must not share a boolean — an off-by-default
  adapter then reports as a working one, and no signal anywhere disagrees.** `PushResult.skipped()`
  (returned when `openbank.notification.push.apns.enabled=false`) carries `success = true`, and the
  fan-out asked `count { success }`. So every push in an environment with no APNs credentials was
  counted as delivered, the row committed `SENT` with `sentAt` set, and the outcome event announced
  a delivery that never left the process. Three things made it unrecoverable from telemetry: the
  channel emitted **no metric at all** (one class in that whole service touched `MeterRegistry`), a
  200 from APNs means *accepted*, not delivered — APNs issues no receipt, so delivery is not
  observable server-side at any effort — and the disabled path is the *quiet* one, so there was no
  error to find. It shipped that way and a customer reported it. Two rules: give a
  skipped/disabled/no-op outcome its **own enum value**, never a flag shared with success (`PushResult.outcome`
  is now `ACCEPTED | SKIPPED | FAILED`); and name the metric for what you can actually establish —
  `accepted`, never `delivered`. Then alert on the success state: "adapter skipping and accepting
  nothing" is the alert that was missing, not an error rate. The same shape is anywhere a stub, a
  dry-run or a feature-flagged-off adapter returns success — grep for `enabled` defaults of `false`
  next to a `success = true` return (ADR-0252 phase 0, #4348).
- **`Instant.EPOCH` as a data-class default is a lie every test agrees with, and a non-null
  assertion is not a check.** `AuditEvent.timestamp` and `FlagExposure.timestamp` both defaulted to
  it; 23 of the 25 fleet `AuditEvent(` sites take the default, and `FlagExposure.of` — the KDoc's
  "typical call site" — never passed one. Nothing caught it because `isNotNull()` passes against
  1970-01-01, and `FlagExposureTest` already asserted *every other* field of `of()`. Assert
  **recency** (`isBetween(before, now)`), never non-nullity, for any field meaning "when did this
  happen" (#3882). Grep the shape: `: Instant = Instant.EPOCH`.
- **A sentinel default that is right for a REPORT becomes a defect the day something ALERTS on it,
  and the alert's own comments will describe the value it wishes it had.**
  `DomainMetrics.registerWorkflowLiveness` seeded its age gauge from `Instant.EPOCH`, so
  `openbank_workflow_last_success_age_seconds` read ~1.8e9 seconds — decades — for any workflow not
  yet successful, including every workflow on a freshly started pod. Fine while the only consumer
  was the control-liveness-sentinel filing a daily finding ("never ran" is trivially over any
  threshold, no special-casing needed — the KDoc said exactly that). Then ADR-0237 added
  `WorkflowLivenessStale` (`age > 2 * expected_interval`, `for: 15m`) over the same gauge: for a
  daily job the threshold is 2 days and a fresh pod reported decades, so it fired **15 minutes after
  every deploy or restart, for every daily workflow**, until that workflow's next success — up to
  24h, across ~28 call sites, and no `for:` helps because the condition genuinely persists. Noise on
  the control that exists to make a dead scheduler visible is the one thing that hides a dead
  scheduler. Both the rule's comments and the ADR asserted the gauge was "seeded at registration —
  never as decades"; it was not, and the KDoc next to the code said so the whole time (#2239 Gap 2,
  fixed by #4208). **When you point an alert at an existing metric, re-derive its value at t=0 on a
  cold pod** — a boot-time reading is a fourth state next to healthy/degraded/absent, and prose in
  the rule is not evidence anyone did.
- **Fixing a magnitude-based defect makes every test that discriminated BY that magnitude vacuous —
  silently, and they stay green.** The fleet's liveness tests asserted `age > FIFTY_YEARS_SECONDS`,
  and that one assertion was doing two unrelated jobs: at a startup site it meant "registered, not
  yet succeeded", and in the `a failed X run records no success` tests it was the *only* thing
  separating a failed run from a successful one. Seeding the gauge at registration makes both read
  ~0, so 18 services' failure-path tests would have kept passing while testing nothing. Before
  changing a sentinel value, grep every assertion that mentions it and ask what each one is really
  discriminating; where the answer is "the defect's magnitude", the test needs a different
  observable, not a retuned bound (here `openbank_workflow_success_recorded`, 0/1). Same family as
  the `isNotNull()` trap above, one step later: there the assertion never could fail, here it stops
  being able to.
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
- **A consumer that rethrows does NOT dead-letter unless that channel configures it — SmallRye's
  default `failure-strategy` is `fail`, which STOPS the channel.** Measured 2026-08-19: 44 incoming
  channels fleet-wide, **4** had a DLQ. So the #5698 sweep, which converted ~30 consumers from
  catch-and-ack to retry-then-rethrow across a dozen services, was on its way to trading silent data
  loss for a halted consumer — the exact outcome the original swallow comments were written to avoid
  — while every KDoc in it said "the connector dead-letters". Wire the DLQ in the same change that
  introduces the rethrow: `failure-strategy`, an **explicit per-service topic**, the `KafkaTopic` CR
  (topics are not auto-created) and the KafkaUser Write ACL. All four, or the rethrow wedges on the
  DLQ send instead (#5745, #5751).
  Two traps inside that. **The topic key is dotted, and the sibling bullet above applies** — but the
  conclusion the fleet drew from it was wrong. Several services carried a comment asserting an
  explicit DLQ topic was impossible *because* SmallRye quotes dotted leaf keys; true premise, wrong
  conclusion. Measured through the real `YamlConfigSource`: the dotted one-liner
  (`dead-letter-queue.topic: x`) is inert, the **nested** form resolves fine —
  ```yaml
  dead-letter-queue:
    topic: openbank.dlq.<service>.<channel>
  ```
  the same idiom already used for `value: deserializer:`. That false constraint suppressed the correct
  fix fleet-wide, and left transaction-service's money-path `payment-scheme-accepted` carrying the
  inert form — correct in the deployed pod only because its msg-override ConfigMap supplied the value,
  wrong locally and in every test. And **the implicit topic name is derived from the CHANNEL name,
  which repeats across services**: account-service and card-issuance-service both consume
  `delegation-events-in` with no override, so they already dead-letter into one shared topic and any
  alert scoped to one counts the other's records (#5752).
- **A comment asserting current CONFIGURATION goes stale exactly like a report asserting current
  remote state.** Two agents in one session measured "this channel has no DLQ", wrote it into a KDoc,
  and were falsified within the hour by the PR that wired it — the same shape as the `NotificationOutcomeConsumer`
  KDoc they had just corrected for claiming a failure was "bounded and visible" when `PENDING` is also
  what an in-flight outcome shows. Write the **mechanism the code controls** ("the record is nacked;
  the connector's configured `failure-strategy` decides what follows") and let `application.yaml` answer
  what the value is today. A comment that names a config value is a claim with a shelf life, and nothing
  re-checks it.

- **A fuzz/DAST job list showing a service is NOT evidence the service was tested — read what each
  job actually did.** The 2026-08-18 API-fuzz run reported 7 failures of 23 and exactly ONE was a
  finding about an HTTP surface; the other six never sent a request, and both failure kinds render
  identically in the job list. Two harness causes, both worth knowing: the datasource `username:`
  in `application.yaml` is often a config EXPRESSION (`${POSTGRES_USER:openbank}`), and taken
  verbatim into `docker run -e POSTGRES_USER=` / `pg_isready -U` it can never succeed — vop and
  settlement had therefore NEVER been fuzzed; and six services register a Temporal worker at
  `StartupEvent`, so with no Temporal present transaction-service failed to boot outright while
  lending, sepa-payment and domestic-payment retried past the deadline. Every registrar carries a
  disable switch but under a DIFFERENT name each (`openbank.transaction.worker.enabled`,
  `openbank.sepa.worker.enabled`, … and lending's `lending.origination.worker.enabled`, not even
  under `openbank.`), so the harness derives the property from each registrar's own
  `@ConfigProperty` rather than listing six names. This matters beyond the lane: the `pentest`
  attestation is earned by this workflow with its run URL as `ref`, so while a service could not be
  fuzzed, C7=Bank-grade was blocked on an event that could not happen for it.

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
- **An UNTRUSTED extension cannot be created by the role Flyway connects as — the migration is green
  on every laptop and crashloops in the cluster.** Postgres splits extensions into trusted
  (`uuid-ossp`, `pgcrypto`, `pg_trgm`, `unaccent` — a database OWNER may create them) and untrusted
  (`vector`, `postgis`, `postgres_fdw`, `plpython3u` — SUPERUSER only). Every local path connects as
  superuser (Dev Services, `PostgreSQLContainer`, compose); CNPG hands the deployed app a
  non-superuser role. Measured against the fleet's own image
  (`ghcr.io/cloudnative-pg/postgresql:18.1`, pgvector 0.8.1 already bundled): as owner
  `ERROR: permission denied to create extension "vector"`, as superuser `CREATE EXTENSION`, and as
  owner against an already-installed one `NOTICE: … already exists, skipping`. That last line is why
  the two halves compose — a CNPG `Database` resource (`spec.extensions[]`, applied by the operator
  over its superuser connection) creates it, and `CREATE EXTENSION IF NOT EXISTS` in the migration
  short-circuits before the permission check, which is what keeps local dev working. Both are
  load-bearing: drop the resource and the deployed migration dies at line 1; drop the migration line
  and every developer's database breaks. Enforced by `check-untrusted-pg-extension.py`
  (`rules.yaml: postgres_extensions`).
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
- **A line added to `rules.yaml: authz.role_action_matrix` is a grant to a MACHINE, and no policy
  can veto it.** `rest.rego`'s `matrix-allows` turns every entry into a permit for any HUMAN holding
  that role, and the Keycloak service-accounts the platform authenticates as are classified HUMAN
  and hold `ROLE_OPERATOR` in at least one realm. `shared_m2m_write_prohibition` cannot help: it is
  keyed by REASON NAME, and listing `matrix-allows` there would veto every legitimate matrix call;
  a per-service `*_rest_ext.rego` cannot help either, because `matrix-allows` lives in base
  `rest.rego` and consults no per-service exclusion. So the only gate is at build time — a write
  action must be declared in `rules.yaml: shared_m2m_matrix_write_grants.declared`
  (`check-matrix-write-grants.py`, enforced). Two things this cost: the "graduated ⇒ denied" column
  of the audits was never true, because `data.rules.shared_m2m_write_prohibition` **is not emitted
  into any bundle** (it is nested under `change_requirements:`, so `gen-rules-opa-data.py` cannot
  select it) and the veto has therefore never fired anywhere; and `ledger.approve`, documented as
  "NEVER reachable by any SERVICE principal", resolved `allow=true` for both service-accounts on the
  live bundle. **Measure a policy claim with `opa eval` against the bundle ConfigMap** — materialise
  it to the sidecar's own directory layout (`rules-data.yaml` → `rules/data.yaml`) and probe
  `data.openbank.rest.allow` per (principal, action), with a must-DENY and a must-ALLOW control in
  every run. Reading the rego cannot tell you which of several reasons actually fires (#3765/#3734).
- **The three realm JSONs in this tree disagree about which roles the M2M accounts hold** — the
  deployed gitops template gives `service-account-openbank-services` only `ROLE_API`, while the
  docker and CI realms also give it `ROLE_OPERATOR`. Any statement of the form "the shared client
  carries ROLE_OPERATOR" is environment-specific; take the union when reasoning about exposure, and
  say which realm you read.

- **A backup that has never been configured reports the same "success" as one that works — CNPG's
  archiver is a NO-OP with a success exit code when no `barmanObjectStore` exists, and both
  `ContinuousArchiving=True ContinuousArchivingSuccess` and `pg_stat_archiver` agree with it.**
  Measured on engagement-db 2026-08-19, before its backup landed: `archived_count=12`,
  `failed_count=0`, last success two days earlier — and **zero objects in the bucket**, because
  there was no bucket to write to. Every WAL sat `.done` in `archive_status/`. Nothing in
  Kubernetes, in Postgres or in the alert set could distinguish "archived to S3" from "archived
  to nowhere"; the only probe that separates them is `aws s3 ls <destinationPath>`. Same family as
  the push adapter whose `PushResult.skipped()` carried `success = true` — a silent no-op sharing
  a flag with real success — and the reason `cnpg-backup-declared-gate` exists at all: the enforced
  sibling only inspects clusters that ALREADY declare a destination, so a database that never asked
  is invisible to it. **Verify a backup by listing the objects, never by reading a condition.**
- **WAL archiving is not a backup, and the cluster looks green in between.** A recovery point needs
  a BASE backup plus WAL; with archiving freshly working and no base backup,
  `ContinuousArchiving=True` while `firstRecoverabilityPoint` is empty — restoring is impossible and
  the only field that says so is one nobody alerts on. After enabling backup on a cluster, force the
  first base backup (an unmanaged `kind: Backup`) instead of waiting for the ScheduledBackup, and
  assert `status.firstRecoverabilityPoint` is set.
- **EKS Pod Identity credentials are injected at ADMISSION, so adding an association does nothing
  for a pod that is already running — and CNPG's 30-minute grace period makes the fix cost minutes,
  not seconds.** engagement-db-1 had been up 7 days when its association was created; WAL archiving
  kept failing `barman-cloud-wal-archive: Unable to locate credentials, exit status 4` until the pod
  was deleted and re-admitted (failures stopped at 17:14:30, first success from the new pod at
  17:14:45). `terminationGracePeriodSeconds` is 1800 and the smart shutdown waits, so budget ~4
  minutes of downtime for that single-instance restart, not 30 seconds. Distinct from #1759, where
  the association existed and the agent merely missed injecting during a node roll.

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
  set, or any `.rego`, regenerate — the derived files FIRST, then every bundle:
  ```
  python3 .github/scripts/gen-rules-opa-data.py
  python3 .github/scripts/gen-agents-opa-data.py     # needs `opa` on PATH
  find openbank-infra/gitops/components -name 'gen-*opa-bundle*.sh' | sort | xargs -n1 bash
  ```
  **`agents.yaml` gets the same treatment (#3927), one level deeper.** The bundles embed
  `agents-opa-data.yaml`, and because `agents.rego` reads only `a.id`, `a.skills`,
  `a.tools.allow` and `a.tools.deny` off each charter, the subset projects *inside* each entry —
  50390 B becomes 5578 B. So editing a charter's `description`, `model`, `limits`, `schedule`,
  `compliance` or `audit` block now restamps **nothing**. A field projection cannot be argued from
  verbatim extraction, so `gen-agents-opa-data.py` proves it: it evaluates the real policy over an
  input matrix derived from the charters under both the full document and the subset and requires
  every MCP and REST decision to be identical before writing. That is why it needs `opa`.
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
- **A bare `@` in an unquoted mermaid node label is a syntax error, and the error points at the
  wrong text.** Mermaid 11 lexes `@` as the node-metadata shorthand (`id@{...}`), so
  `outbox[Outbox<br/>Dispatcher<br/>@Scheduled every 5s]` fails to parse — while the caret in the
  message lands on unrelated earlier text on the same line (`Hibernate Reactive / Panache`, which
  is valid), sending you after the wrong token. Quote the label. Sibling: a literal `;` in a
  `sequenceDiagram` message or `Note` is a statement separator — write `#59;`. Both render as a red
  "Mermaid render failed" box in the Service Docs page, which nothing in CI loads: 40 of 248 blocks
  across 21 services were broken that way, found only when someone opened one. Now enforced by the
  `mermaid-parses` gate, which parses every block with admin-ui's own mermaid.

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

**The tree-scoped detail lives in [`.github/CLAUDE.md`](.github/CLAUDE.md)** — workflow
mechanics, gate declaration, log-reading, bot signing, runner isolation. It loads when you
touch `.github/`. What stays here is what fires from OUTSIDE that tree: editing
`rules.yaml`, an `application.yaml`, or merging a PR from anywhere.

### Shell probes
- **A probe fails by reporting CLEAN — source `.github/scripts/lib/probe.sh` instead of
  re-deriving one.** BSD tools take GNU-ish input, do not error, and return a plausible nothing, so
  a broken probe is indistinguishable from a clean subject. The library carries the ones this repo
  has actually got wrong — `probe_utc_epoch` (BSD `date -j -f` ignores the trailing `Z` and parses
  as LOCAL time: 53 branches falsely flagged), `probe_files_modified_since` (`-newermt "-60 minutes"`
  is *always* empty on BSD, so a busy worktree reads as idle), `probe_remote_branch_exists`
  (`git rev-parse origin/<b>` reads the LOCAL tracking ref and a plain fetch never prunes),
  `probe_pr_failing_checks` (job names contain spaces, so an `awk` column is a word of the NAME),
  `probe_lint_findings` (a newline-joined file list arrives as ONE argument; post-filtering a
  linter's output hides the failures that are not findings), and `probe_zombie_runs`.
  Each is held to a known-positive **and** a known-negative by the enforced
  `probe-lib-known-positive` gate — `bash .github/scripts/lib/probe.sh --selftest`.
- **`status=in_progress` is not a measure of CI load: 189 of those runs are wedged** — the run
  record never transitioned while every one of its jobs is `completed`, the oldest from
  2026-08-09. They are **not reapable**: both `POST /actions/runs/{id}/cancel` and `.../force-cancel`
  answer HTTP 500 on every one. Subtract them with `probe_zombie_runs` before any statement about
  saturation. Three automation workflows account for 163 of the 189.

### Reviewing a diff
- **Use 3-dot diff for pre-merge review:** `git diff origin/main...origin/<branch>` is the actual
  squash delta; 2-dot includes main's post-divergence commits and makes stale branches look like
  regressions.

### ADR registry
- **Order is `gen-index.sh` → COMMIT → `check-adr-registry.sh`, never regen → check → commit.** A
  failing check restores the three derived files (`README.md`, `DIGEST.md`, `index.json`) to HEAD
  on exit, so committing after a failed check commits the *restored* content — and the next regen
  then disagrees with what you committed, failing the gate on content you never wrote (#3983).
  **Same trap in the sibling derived-file checks:** `check-eu-ai-act.sh` restores
  `docs/compliance/eu-ai-act.md` to HEAD on a failing run (its line 25 `git checkout -- "$DOC"`),
  so a regen you ran *before* the check is silently reverted and your later `git add` stages the
  old version (#4002). Universal order for every derived file: regenerate → stage+commit → run
  the check.
- **`git mv` + a follow-up edit = a PURE RENAME commit — the edit stays unstaged.** When
  renumbering an ADR after a number collision with main, `git mv` the file, edit the H1, `git add`
  the file AGAIN, then commit — or the registry gate fails with "H1 says number N but filename is
  M" on a file that looks correct in your working tree (#3983).
- **An `agents.yaml` change has THREE derived tails, not one.** Regenerate all of them or the
  enforced gates fail on main: `docs/compliance/eu-ai-act.md` (`gen-eu-ai-act.py`), the 40 OPA
  bundle ConfigMaps + pod-roll `policy-checksum` annotations (every
  `gen-*opa-bundle*.sh` under `openbank-infra/gitops/components`), and
  `openbank-admin-ui/ai-governance-snapshot.json` (`gen-ai-governance-snapshot.py`; also stale
  after a `prompts/registry.yaml` change). #3771 regenerated none and red-gated main; #4002 is the
  regeneration template.
- **Don't regenerate by hand — `bash .github/scripts/regen-derived.sh`** runs every generator, in
  dependency order (derived data before the bundles that embed it), for the sources your branch
  actually changed; `--all` does the lot unconditionally. It deliberately runs **no checker**, so
  the regenerate → commit → check order above is structural rather than something you have to
  remember. Its inventory is held to the generators that exist by the `regen-derived-inventory`
  gate, in both directions, so a generator added later cannot be silently left out of it.

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
- **Two racing spec PRs can both claim the same `info.version`.** After any competing
  `openapi.yaml` change merges — including when you resolve a merge conflict against `main` —
  re-check `info.version` against the *current* `main` and re-bump; whoever lands second takes
  the next version. A matching version line merging "cleanly" is the trap: git sees identical
  text, not a taken version.
  **Guard against the right mechanism — this bullet named a stale one until 2026-08-13.** The
  original cause was the gate classifying against the PR's *creation-time* base
  (`github.event.pull_request.base.sha`), which froze at PR creation and could not see a
  competing bump (#481 vs #524 on the ledger spec). That was **fixed by #534**: `ci.yml`'s
  `resolve PR diff base` step now resolves the merge-base as it is right now, and says so in
  its own comment. What remains is a *different* and narrower race — a PR whose last CI run
  predates a competing spec merge, and which then merges with no later run, is invisible to any
  run-time base, because the classification is only as fresh as the run. No base resolution can
  close that; only a merge queue or up-to-date-branch enforcement would, and the repo has
  deliberately chosen detection over prevention. So the re-check above is still required — but
  a branch that has been sitting is the risk, not a branch that was created early.
- **`oasdiff` compares a spec to its own PREVIOUS version, never to the implementation — so a spec
  enum that was wrong from the first commit is wrong forever, and every gate stays green.** The
  version axis is watched from both ends and the *truth* axis from neither.
  `openbank-kyc-service` published `checkType` as
  `[IDENTITY, SANCTIONS, PEP, ADVERSE_MEDIA, SOURCE_OF_FUNDS]` against a domain
  `CheckType { IDENTITY, ADDRESS, PEP_SCREENING, SANCTIONS_SCREENING, ADVERSE_MEDIA }`: three
  names misspelled, `ADDRESS` (a check `createCase` creates on every case) unpublished, and
  `SOURCE_OF_FUNDS` a value that has never existed in the code. Its `UpdateCheckRequest` was
  fiction in the same way — `required: [result]` with `result` carrying the status enum, against a
  DTO of `(status: String, result: String?)`. So no client generated from that document could call
  the endpoint at all, and nothing anywhere said so (#5895). It is not one service:
  `check-openapi-enum-vs-domain.py` (gate `openapi-enum-domain-drift`, pairs a spec enum with the
  Kotlin enum it serves by value overlap) found **27 more across 16 services** (#5962), several
  advertising values the code lacks — which a generated client will send and the service will 400.
  **Two consequences worth carrying.** Correcting one is *breaking* to `oasdiff` (removed enum
  values) while being unbreakable in fact — the server is byte-identical — so it takes the
  `correction` class in `check-api-contract.py`: MINOR, no URL major. That reclassification is
  **mechanical, not declared**, and it only applies while the PR touches *nothing else* in that
  service, so **put the drift test outside the service directory** or the gate demands a MAJOR it
  also forbids. And when a downstream spec republishes the same vocabulary, it is evidence about
  which side is canonical: `openbank-customer-edge` already carried the domain spelling verbatim,
  which settled all five kyc names before any judgement call was needed.
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
