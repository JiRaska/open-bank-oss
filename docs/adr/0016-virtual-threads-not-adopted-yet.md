---
date: 2026-05-29
decision-status: accepted
delivery-status: n-a
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [architecture]
summary: "Virtual Threads are not adopted; Kotlin coroutines plus reactive Mutiny already give non-blocking blocking-style code, so @RunOnVirtualThread is used only case-by-case for blocking SDKs or new imperative services."
---

# Virtual Threads: not adopted yet — keep Kotlin coroutines

## Context

JDK 25 (the toolchain we landed in scénář B krok E, commit 1c8a465) makes
Virtual Threads (Project Loom) fully matured: millions of concurrent tasks at
near-zero per-task memory cost, blocking I/O without burning OS threads, and
Quarkus 3.x exposes the opt-in via `@io.smallrye.common.annotation.RunOnVirtualThread`
on REST endpoints and `@Incoming` Kafka consumers.

The Scénář B exit checklist included "F2: zapnout Virtual Threads selektivně
pro KYC, AML batch consumers a audit-service Kafka consumer." We audited the
candidates and found:

- **Both existing `@Incoming` Kafka consumers** (`AuditConsumer`,
  `NotificationConsumer`) are already declared as `suspend fun` and run inside
  Kotlin coroutine contexts. Their blocking-looking DB calls are bridged to
  reactive Mutiny via `awaitSuspending()`, so the coroutine yields rather than
  pinning a thread.
- **All REST resources** in the 28 services are `suspend fun` likewise — there
  is no `@Blocking` worker-pool endpoint anywhere in the production code that
  would gain from Virtual Threads.
- **The shared outbox dispatcher** (`OutboxDispatch.dispatchOnce` in libs)
  invokes a `suspend (payload) -> Unit` publish lambda — same model.

In other words, OpenBank already gets the "blocking-style code without burning
OS threads" property — from Kotlin coroutines + reactive Mutiny — that
Virtual Threads would deliver to a non-reactive codebase.

## Decision

We will **not** add `@RunOnVirtualThread` to any current service code. The
existing coroutine + Mutiny pipeline stays.

We will adopt Virtual Threads **case-by-case**, gated by these triggers:

1. A new service is designed in a blocking imperative style (no Mutiny, no
   coroutines). Then `@RunOnVirtualThread` is the right tool from day one.
2. We integrate a third-party SDK whose API is purely blocking and whose use
   would otherwise demand a `@Blocking` worker-thread pool (e.g. a legacy
   SOAP client, a synchronous SWIFT gateway library). Wrapping such a call
   in a `@RunOnVirtualThread` endpoint is preferable to a thread pool sized
   guess.
3. Measured back-pressure on the coroutine dispatcher pool surfaces as a
   bottleneck under load test. Then virtual threads become a tuning lever.

None of those triggers are met today.

## Alternatives considered

- **Mass-migrate all `suspend fun consume(...)` to `fun consume(...)` +
  `@RunOnVirtualThread`.** That is technically possible but actively
  regressive: it loses structured concurrency, coroutine cancellation, and
  `withContext` scoping — features the codebase already uses. Reject.
- **Mix the two paradigms per service.** Some endpoints on virtual threads,
  others on coroutines. Hard to reason about, easy to leak Mutiny `Uni` into a
  blocking lambda or vice versa. Reject — pick one model per service.
- **Wait until Quarkus 4.x.** Quarkus has signaled a stronger lean on Loom
  in 4.x (uniform `@Transactional` model across blocking/reactive); the
  trade-off may shift. Re-evaluate this ADR when that lands.

## Consequences

**Positive**
- No churn. The existing reactive + coroutine model is well-understood and
  already covered by tests.
- We still get JDK 25's other Loom wins for free: when a Quarkus-internal
  worker pool task happens to run on a virtual carrier, it does so without
  any source change.

**Negative**
- We do not benefit from the "imperative blocking is fine now" story when
  pitching the project to teams who don't want to learn coroutines. That
  conversation moves to the design moment when a new service is created.

**Neutral**
- Generational ZGC (the other JDK 25 win that we did adopt — commit
  1c8a465) gives most of the GC-pause benefit independent of threading.

## Compliance impact

None. DORA Art. 25 (operational resilience) is concerned with reproducibility
of behavior under load and recovery; both threading models satisfy it.

## References

- ADR 0014 — openbank-libs centralization roadmap (the suspend-fun + Mutiny
  pattern we already use)
- Scénář B krok E (commit 1c8a465) — JDK 25 + ZGC adoption
- [Quarkus guide — Virtual Threads](https://quarkus.io/guides/virtual-threads)
- [JEP 444 — Virtual Threads (final, JDK 21)](https://openjdk.org/jeps/444)
