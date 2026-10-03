# Complaint booking source validation — 2026-09-26

## Result and scope

The Domestic Payment and Transaction source changes passed their complete local
Gradle builds on Java 25. This is source integration evidence, not a completed
live complaint journey, sandbox deployment, or performance acceptance.

The source association uses two distinct identities: a Domestic Payment UUID and
a Transaction Service booking UUID. Domestic settlement supplies its persisted
payment UUID; Transaction Service persists it and emits it in its normal outbox.
Only the authenticated Domestic Payment service principal, with its matching
verified JWT authorized party and the existing API role, may submit that pointer
for a domestic debit with a source account. A pointer alone does not prove payment
existence, ownership, settlement, or fraud.

## Executed checks

The invocation built Context, Transaction Service and Domestic Payment with two
Gradle workers. The overall invocation failed in Context; it must not be reported
as a passing fleet build.

| Module | Unit/integration tests | Failures | Skipped | Build evidence |
| --- | ---: | ---: | ---: | --- |
| Transaction Service | 278 | 0 | 1 | `build`, coverage verification, Detekt, Kotlin lint and Quarkus build completed |
| Domestic Payment | 252 | 0 | 1 | `build`, coverage verification, Detekt, Kotlin lint and Quarkus build completed |
| Context | 195 | 2 | 1 | Full build failed; remaining tasks are not implied to have run |

Transaction provider Pact verification also produced 13 test cases, zero failures,
and one skip. The Domestic consumer regenerated the committed Pact from its test;
the optional originating payment identifier is part of that interaction.

The source tests cover HTTP authorization denials, persisted pointer and outbox
payloads, immutable idempotent replay, competing requests with different pointers,
and rejection of a settlement conflict. HTTP identity fixtures in these tests are
Quarkus test identities; they do not establish real signed-token/OIDC operation.

## Unresolved Context results

`ContextAuditCancellationIT` failed in an uncancelled control write with the
unchanged 500 ms SQL operation deadline. Its cause is not established by static
review. `ContextProjectionCancellationIT` could not start its HTTP server because
of a port binding conflict. A separate sequential reproduction with an
OS-assigned HTTP test port passed both tests: two tests, zero failures, zero skips.
This establishes the tested cancellation and recovery behavior in that run; it
does not establish the cause of the first audit timeout or make the failed broad
run green. The complete standalone Context build subsequently passed: 195 tests, zero
failures, one skip; coverage verification, Detekt, Kotlin lint and Quarkus build
completed. The separate provider Pact task reported nine tests, zero failures and
one skip. The original failed run remains part of the evidence.
No production timeout or security control was relaxed.

## Remaining acceptance

The portable complaint probe requires a genuinely produced payment and booking.
The live fixture must run the normal Domestic and Transaction Temporal workflows,
real clearing acceptance, source account and screening services, Balance holds
and Ledger posting, Dispute APIs, and authorized Context maker/checker assignment.
Do not fabricate the association through database writes or injected events.

The probe must establish current and historical complaint revision evidence,
independent payment lifecycle evidence, exact booking provenance, wrong-purpose,
wrong-case and unassigned-root denials, and cleanup of its own assignment. Source
complaints remain synthetic evidence and are not deleted by assignment cleanup.

Authenticated 1x/10x capacity, audit queue drain, rendered admin UI acceptance,
mandatory human review, and sandbox rollout remain separate uncompleted gates.
