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
