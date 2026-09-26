# Context authenticated API smoke — 2026-09-26

## Scope and result

Initial runtime under test: a previously packaged Context JVM application, custom `perf` runtime profile. A later fresh build detected a V21 migration checksum mismatch against its disposable database, so attribution of this first smoke to head `a20da39c7d1794d6c30a51b18180341cedf20e5b` is withdrawn. Its figures describe that initial artifact only and do not validate the current source head. Authentication and OPA enforcement were enabled; neither `%test` nor `%dev` security overrides were used.

The existing `perf/k6/context-graph-read-baseline.js` complaint smoke profile completed successfully: two minutes, 1→5→0 virtual users, 13,162 requests, 52,648 successful checks, zero failed requests/checks. Mean graph latency was 35.12 ms, median 24.56 ms, p95 91.65 ms, maximum 676.62 ms. Both configured latency thresholds (p95 <300 ms and p99 <1000 ms) passed; p99 was not separately exported as a percentile. Observed throughput averaged 109.64 requests/s in this closed-loop smoke; this is **not** a constant-arrival-rate capacity result.

Every measured request used a signed Keycloak user token and a live root-scoped PAYMENT_COMPLAINT assignment, called the real OPA policy, and returned a nonempty bounded graph with three nodes and two edges. A synthetic source event was published through Kafka and processed by the normal complaint projector. All test identities, case references and domain identifiers were synthetic.

## Authorization and persistence proof

Before measurement, one ROLE_ADMIN user proposed the assignment through HTTP (201), and a distinct ROLE_ADMIN user approved it through HTTP (200, APPROVED). The assigned ROLE_COMPLIANCE reader received 200. A valid token without the graph role received 403; the compliance reader with an unassigned case received 403; an anonymous request received 401. Negative responses contained no graph evidence.

The application database role was explicitly NOSUPERUSER and NOBYPASSRLS. The run used the repository's forced bank RLS and explicit bank/generation predicates. After the completed run, database inspection found 13,163 ALLOWED decisions, 13,163 completed disclosure records, 13,164 audit commitments and 13,163 disclosure commitments. This includes one authorized preflight and one audited assignment denial, alongside the 13,162 measured successful reads. The role-level denial and anonymous rejection happen before application graph querying.

## Environment and limitations

Disposable local dependencies: PostgreSQL 16.3, Kafka native 4.2.0, Keycloak 26.6.3 and OPA 1.17.0, with all published ports restricted to loopback. OPA loaded shared agent/REST policies, their governed data and the Context extension. Keycloak development startup was used only for the disposable synthetic realm; this does not certify production identity deployment. The Context JVM ran on Java 25, matching the packaged class-file version.

The fixture is deliberately small and proves the authenticated API path, not annual-volume headroom. It does not establish 1x/10x stored-volume performance, five-minute constant 100 RPS capacity, other graph lenses, source-service mTLS integration, sandbox deployment, or the money-path workload impact budget. Those acceptance conditions remain open.

Commitment relay export was not enabled: local decisions, disclosures and outbox commits were verified, but delivery into the Audit service was not. Two startup emitter-wiring errors preceded application readiness and must be investigated separately before claiming enabled relay delivery. A malformed initial synthetic event with the wrong source-service literal was rejected before the corrected valid event was projected; it was not a successful ingestion sample.

Credentials and tokens stayed in restricted temporary files and are excluded from this report and the repository.

## Fresh-build relay correction and repeat smoke

The startup race was traced to scheduled construction before messaging channel initialization. Both relays now skip execution until ApplicationNotRunning permits it and resolve their emitter lazily after the enabled guard. The audit integration test drives the real scheduler and waits for both publication and persisted SENT status; the disclosure integration test retains direct idempotency coverage. Both focused tests, ktlint, Detekt and quarkusBuild passed.

The disposable schema was recreated rather than repairing migration checksums. The fresh package applied all migrations through V21 and started with both commitment exports enabled, without the earlier emitter startup errors. The repeat two-minute authenticated smoke completed with 14,902 requests, 59,608 successful checks, no failed requests/checks, mean 31.00 ms, median 21.09 ms, p95 85.74 ms and maximum 624.89 ms. Configured p95/p99 thresholds passed. Closed-loop observed throughput was 124.15 requests/s. Both export relays were enabled during this run.

A separate real Kafka consumer inspected 500 commitment messages and verified the exact eight-field allowlist, SHA-256-shaped commitments and presence of both CONTEXT_READ_AUDIT_COMMITTED and CONTEXT_DISCLOSURE_COMMITTED. This proves publication into the isolated Kafka topic, not ingestion by Audit service.

The repeat exposed a capacity limitation: each relay claims at most 100 rows per five-second invocation (at most 20 commitments/s before processing overhead). Both outbox queues grow under the smoke workload. The next implementation must provide bounded dispatch throughput and drain verification sufficient for the planned 100 RPS, rather than claiming the read-latency success proves sustainable end-to-end audit capacity.

## Fixed-arrival 100 RPS failure — not accepted

After introducing a bounded shared claim size of 200 and one-second relay polls (source head `29ba1d4d5f`), the existing k6 capacity profile was run against the same small synthetic complaint fixture with both exports enabled. Both prior outbox queues were observed fully SENT before load. A fresh synthetic signed token had a 15-minute lifetime, longer than the run; runtime authentication, OPA and mandatory local audit stayed enabled.

The five-minute run failed: 29,897 attempted requests, zero successful graph checks, 100% failed requests, 104 dropped iterations, k6 exit 99. Failed response latency p95 was 507.08 ms; it is **not** a successful graph latency statistic. No 1x/10x annual-volume capacity claim follows from this run.

At the initial failure, 72 additional ALLOWED audit decisions accumulated without completed disclosures; commitment dispatch also stopped progressing. PostgreSQL inspection showed 22 idle connections and no application query executing or waiting on a database lock. JVM event-loop threads were idle rather than blocked on application work. The logs showed caller timeouts followed by Mutiny-dropped HR000090 live-transaction-on-close errors. The reactive SQL metrics had a duplicate gauge registration warning, so their apparent zero pending count cannot certify availability of every pool.

These observations prioritize managed transaction/session cancellation and connection lifecycle for investigation; they do not yet prove the exact defect. The current query and audit timeouts wrap the managed lifecycle externally. A pool-size-two recovery regression is being validated before changing that lifecycle. Enlarging pool size or loosening thresholds would not establish recovery correctness. Sustainable 100 RPS, complete drain and Audit-service ingestion remain unproven.


## Session ownership and SQL cancellation investigation

The repeat at source head `4485aba396` still failed the fixed-arrival profile: 29,698 iterations, 302 dropped iterations and 327 successful nonempty graph responses (1,308 successful checks; 117,484 failed checks). Failed requests accounted for 98.899% of attempts. Aggregate response percentiles mix failures with successful reads and are not accepted performance evidence.

A separate real-DB overlapping-operation regression proved that the former factory-managed helper borrowed the same context-cached Hibernate session for concurrent operations. Explicit operation-owned sessions corrected that defect; five graph integration tests and both commitment relay tests passed, as did ktlint, Detekt and Quarkus packaging. This did not resolve the load failure: logs now show late SQL result consumption after session close.

A distinguishing regression deliberately separates a 100 ms application timer from a 1,000 ms PostgreSQL statement timeout. The existing client timer fails before the server result, demonstrating premature cancellation rather than acknowledged SQL termination. The server-led timeout correction passed six graph integration tests, both relay integration tests, ktlint, Detekt and Quarkus packaging. After the failed owned-session load run, 674 commitments remained outstanding at the drain deadline. It must not be described as complete request-cancellation support: ADR-0303 D5 additionally requires cancellation to stop database work, and safe acknowledgment, rollback, connection recovery and suppression of subsequent SQL remain acceptance conditions.


## Retained SQL observation and cancelled audit rollback

The server-led timeout build at `5067459570` still failed the fixed-arrival profile: 29,799 requests, zero successful graph checks, 202 dropped iterations and 100 commitments outstanding after the drain deadline. Removing an application timer alone did not solve cancellation from the HTTP caller.

A real PostgreSQL regression then observed the exact defect: after cancelling a subscription on its original Vert.x context, the Hibernate session was closed while `pg_stat_activity` still showed its delayed SQL executing. The controlled operation now retains its internal observer, marks the transaction for rollback on pre-commit cancellation, waits for server SQL termination and then closes the owned session. Guarded statement boundaries check cancellation and remaining monotonic budget. The audit repository guards bank scope setup, both persist operations and explicit flush separately; complaint snapshot reads guard scope setup and the source revision query separately.

Ten integration tests passed, including the formerly failing cancellation regression, statement timeout/pool recovery, overlapping session ownership, both commitment relays and actual cancelled audit rollback. The audit rollback test blocks an insert with an uncommitted identical synthetic ID, observes the real database lock, cancels on the original context and releases the blocker. It verifies zero audit and commitment rows, then more successful actual audit writes than available pool slots. These tests prove the exercised operation boundaries; they do not establish all graph lenses or capacity.

Active SQL cancellation still relies on the bounded PostgreSQL statement timeout, rather than immediate cancellation signaling. Pool acquisition and cleanup need their own bounded admission controls. A cancellation after the atomic commit boundary can have an indeterminate result for the caller. Remaining legacy multi-statement callbacks need individual guards before claiming fleet-wide cancellation support. Fixed-arrival capacity, annual 1x/10x volume, Audit-service ingestion and sandbox deployment remain open.


## Scenario migration and budget boundary verification

The full suite at `44449dce9b` found three failures (two provider-contract HTTP responses and one cold graph read) caused by an additional wall-clock deadline spanning preparation and completed-result handling. The SQL statement admission deadline now begins after transaction start, guards new statement submission, and does not turn a successfully completed SQL result into an application timeout. Completion and the atomic commit boundary still reject caller cancellation. PostgreSQL continues to enforce each submitted statement timeout. This is not a strict total HTTP-duration guarantee.

The focused repeat passed 61 tests with no failures/skips, including the provider contracts and affected graph, authorization, AML, KYB, Fraud and Lending cases; lint, Detekt and Quarkus packaging passed. Authority, AML, KYB, Fraud reference and Lending candidate repositories now guard bank-scope setup and each query/mutation separately while preserving source conflict checks, historical cutoffs, exact assignment predicates and result bounds. Subsequently changed assignment administration and two additional admission-budget regressions require the next full verification before attribution of those results to the final worktree.

The subsequent complete Context suite passed 180 tests (zero failures/errors, one existing skip), followed by ktlintCheck, Detekt and Quarkus packaging. This run includes assignment administration, both admission-budget regressions and guarded complaint, domestic payment, booking, rail and incident projection/history writers. Review confirmed unchanged projection SQL, history-before-dedup ordering and single-event transaction boundaries. Assignment approval now persists the assignment before linking its ID from the proposal, avoiding a foreign-key failure from automatic flush during the next timeout-setting query. Interrupted projection rollback still needs its dedicated regression; capacity has not been re-established by this suite.

The dedicated ContextProjectionCancellationIT subsequently passed against PostgreSQL. It blocks the actual domestic-payment consumer at its dedup insert after four history tables acquired write locks, cancels on the original Vert.x context, then checks all seven history/current/dedup tables contain no partial projection. Replaying the same event and delivering it again produce exactly one complete projection. The final test version passed formatting and Detekt; the required non-incremental build is in progress. This is atomicity evidence, not a capacity or end-to-end UI result.

The non-incremental `:openbank-context-service:build --rerun-tasks` compiled the changed writer interfaces from scratch but did not pass: its test report contained 180 tests, three failures and 73 skips. The failures were a provider-contract denial returning 500 instead of 403, a Kafka native test container exiting 126 during boot, and the actual audit relay fixture expiring its SQL admission deadline. The skipped tests are not evidence of acceptance. These failures are under investigation with an unchanged-budget focused repeat; fixed-arrival load has not restarted.

The unchanged-budget focused repeat of the three failing classes passed, along with ktlintCheck, Detekt and Quarkus packaging. Kafka startup failure was traced to `Text file busy` when executing Testcontainers startup script (exit 126), before Kafka startup. This repeat does not retroactively validate the skipped tests in the failed full build. Authorization-dependency failures returning generic 500 remain a correctness gap under repair; the healthy expected-denial contract passed on repeat.

The authorization dependency fix passed 27 unit/contract tests (zero failures/skips), followed by ktlintCheck, Detekt and Quarkus packaging. Assignment and audit failures map to the existing typed 503 response, prevent data return and preserve cancellation. Audit failure does not recurse into another audit attempt; healthy authorization denial remains distinct. The configured SQL budgets were unchanged.

The immutable runtime at `773c68f5d0` passed real OIDC/OPA preflight: assigned synthetic complaint returned three nodes/two edges and the denied role returned 403. The unchanged fixed 100 RPS run then failed: 29,787 requests, 69.8157% HTTP failures, 214 dropped iterations and only 30.1843% successful graph checks. Both commitment queues drained to zero within the post-load window. The live JVM dump and PostgreSQL sample showed continued processing, no blocked event-loop threads and no long-held database transactions; the sample pool pending-acquisition gauge was zero. Diagnostic thread capture occurred after timeouts were already present, so this failed run is diagnostic rather than clean performance certification. HTTP response-status breakdown is missing from the baseline and is being added without changing acceptance thresholds. Annual 1x/10x and scenario/source/UI/sandbox acceptance remain unproven.

Server metrics provided the missing attribution: the live snapshot contained 9,123 HTTP 429 responses and 197 connection resets. After the load completed and both commitment queues drained to zero, an assigned single complaint read still returned 429 with `X-RateLimit-Limit: 200` and `X-RateLimit-Remaining: 0`. The shared RateLimitFilter currently releases acquired semaphore permits only in its ContainerResponseFilter, so disconnect cleanup is the leading cause to reproduce with real HTTP cancellation. No admission limit or authentication policy has been relaxed. A successful-response latency percentile cannot establish capacity when most requests are rejected.
