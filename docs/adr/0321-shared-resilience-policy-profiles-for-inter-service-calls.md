---
date: 2026-09-26
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, libs, testing, compliance]
summary: "Inter-service calls pick one of four named resilience profiles (money-sync, read, external-scheme, batch) from openbank-libs instead of per-site literals; every profile emits ResilientCallMetrics and honours a synthetic-only fault hook."
followup: "none — decision-only until the profile catalogue and the resilience-profile gate land; delivery tracked by the linked issue"
---

# ADR-0321 — Shared resilience policy profiles for inter-service calls

## Context

ADR-0049 centralised the fault-tolerance posture of the *outbox dispatcher* in openbank-libs,
and ADR-0032 requires the sanctions gate to sit behind "the standard fault-tolerance posture".
Neither decides what that standard posture *is* for an ordinary synchronous call between
services, and nothing in `docs/adr/DIGEST.md` does: ADR-0151 and ADR-0242 decide
*infrastructure* failure injection (Chaos Mesh, DR drills), not the application-level policy the
injection is supposed to test. This ADR is needed because the posture exists only as copied
literals.

Measured on `origin/main` (2026-09-26), `git grep` over `*/src/main/*.kt`:

| Annotation | Uses | Services | Distinct literal shapes |
|---|---|---|---|
| `@Timeout` | 147 | 43 | 33 |
| `@Retry` | 127 | 42 | 17 |
| `@CircuitBreaker` | 121 | 42 | 7 |
| `@Bulkhead` | 75 | 39 | 3 |
| `@Fallback` | 8 | 1 | — |

So the premise that the fleet "does not use SmallRye Fault Tolerance" is false — it uses it
almost everywhere. What is missing is *policy*: 33 distinct timeout values (from 2 s to 30 s)
with no recorded reason for any of them, retries that sometimes carry `retryOn = [Exception::class]`
(which retries a 4xx as readily as a 5xx), and `ResilientCallMetrics`
(`openbank-libs-runtime/.../observability/ResilientCallMetrics.kt`, the `breaker_open` vs
`call_failed` split from #3267) wired into exactly **one** service. A reviewer cannot tell
whether a given `@Timeout(30000)` on a payment path is deliberate. The libs already hold the
building blocks: the MicroProfile FT API is a `compileOnly` dependency of openbank-libs-runtime,
and the synthetic-taint REST filters (`SyntheticTaintFilter`, `SyntheticTaintClientFilter`,
`SyntheticTaintExternalBoundary`, ADR-0252) already mark a request as synthetic end to end.

## Decision

We will define resilience as a small closed set of **named profiles** per call class, owned by
openbank-libs, and make every inter-service REST adapter declare exactly one.

**D1 — Four profiles.**

| Profile | Use for | Timeout | Retry | Circuit breaker | Bulkhead |
|---|---|---|---|---|---|
| `money-sync` | synchronous money-path writes and gates (sanctions, ledger posting, SCA) | 3 s | at most 1 retry, 200 ms + 100 ms jitter, **only** on connect/timeout/5xx and only when the call carries an idempotency key; never on 4xx | volume 10, ratio 0.5, delay 5 s, success 2 | 20 concurrent |
| `read` | idempotent reads (directory, catalog, balances) | 2 s | 2 retries, 200 ms + 100 ms jitter, on connect/timeout/5xx | volume 10, ratio 0.5, delay 5 s | 50 concurrent |
| `external-scheme` | clearing, SEPA/SWIFT, CNB, any `@SyntheticTaintExternalBoundary` client | 10 s | 2 retries, 1 s + 500 ms jitter, idempotent calls only | volume 4, ratio 0.5, delay 10 s, success 2 | 10 concurrent |
| `batch` | scheduled and back-office calls with no user waiting | 30 s | 3 retries, 2 s + 1 s jitter | volume 10, ratio 0.5, delay 30 s | 5 concurrent |

The numbers are the modal values already in the tree (62 × `@Retry(maxRetries = 2, delay = 200, jitter = 100)`,
62 × `@CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)`), so
adopting a profile changes behaviour for the outliers only. Jitter is mandatory in every retry.

**D2 — Declaration.** A profile is a set of Kotlin constants in openbank-libs-runtime
(`com.openbank.libs.resilience`) used as the annotation arguments, plus a marker annotation
`@ResilienceProfile("money-sync")` on the adapter method. Per-environment tuning uses
MicroProfile FT's own config override (`<class>/<method>/Timeout/value`) — never a new literal.
A deviation is allowed only as `@ResilienceProfile("custom", reason = "...")`, visible beside the
call — the same review shape as `SyntheticTaintExternalBoundary`.

**D3 — Metrics.** Every profiled adapter records through `ResilientCallMetrics`, which gains a
closed `profile` tag next to `adapter` and `outcome`. Breaker state and bulkhead rejections come
from SmallRye FT's own Micrometer metrics, which Quarkus exports when `quarkus-micrometer` is
present.

**D4 — Fault-injection hook.** openbank-libs-runtime adds a client filter that, **only** when the
inbound request is synthetic-tainted (ADR-0252) *and* carries a fault header naming a fault
(`delay`, `error-5xx`, `reset`), injects that fault before the real call. A request without the
taint is never affected, so the hook is inert for real customers by construction; it is also off
unless `openbank.resilience.fault-injection.enabled=true`. This makes the D1 claims testable
against a running service, and complements ADR-0151, which injects at the infrastructure layer.

## Alternatives considered

- **Keep per-site literals, document the conventions** — zero migration cost, but the 33
  distinct timeouts show prose does not converge; a documented convention with no gate is what
  the tree already has.
- **Service-mesh retries and timeouts** — uniform and language-agnostic, but the platform runs no
  mesh today, mesh retries are blind to idempotency keys (so would retry non-idempotent money
  POSTs), and it duplicates in-process breakers 42 services already run.
- **One global default via `Timeout/value` config, no profiles** — cheapest, but one value cannot
  suit both a 2 s read and a clearing call; it would recreate the outliers in the other direction.

## Consequences

**Positive**
- A reviewer reads intent (`money-sync`) instead of a number; retries of 4xx on money paths
  become visible and removable.
- `breaker_open` vs `call_failed` becomes a fleet-wide signal rather than a one-service one.
- Resilience claims become testable in production through synthetic journeys only.

**Negative**
- A fleet sweep across ~42 services; the outliers change behaviour and need per-service review.
- One more annotation per adapter.

**Neutral**
- Enforcement: a new checker `check-resilience-profile.py` (gate `resilience-profile`, advisory
  first, then enforced with a baseline ratchet) requires every `@Timeout`/`@Retry`/`@CircuitBreaker`
  method in `src/main` to carry `@ResilienceProfile`, and rejects `retryOn = [Exception::class]`
  under `money-sync`.

### Delivery check

- `git grep -l '@Timeout(' -- '*/src/main/*.kt' | xargs grep -L '@ResilienceProfile'` prints nothing.
- `.github/gates/gates.yaml` has id `resilience-profile` with `mode: enforced`.
- `git grep -l 'ResilientCallMetrics' -- '*/src/main/*.kt' | cut -d/ -f1 | sort -u | wc -l` is at
  least 40 (today: one service outside the libs).

## Compliance impact

- PCI DSS: not applicable — no cardholder-data handling changes.
- DORA: Art. 11 (response and recovery) — defined, measured degradation behaviour per call class;
  Art. 24–25 (digital operational resilience testing) — the synthetic-only D4 hook gives a
  repeatable scenario-based test of each profile, recorded as test evidence next to the
  ADR-0151 and ADR-0242 drills.
- GDPR: not applicable — no personal-data processing changes.
- PSD2: not applicable — no change to the PSD2 interface contract.
- CNB: not applicable — no reporting change.

## References

- ADR-0032, ADR-0049, ADR-0151, ADR-0242, ADR-0252
- `openbank-libs-runtime/src/main/kotlin/com/openbank/libs/observability/ResilientCallMetrics.kt`
- `openbank-libs-runtime/src/main/kotlin/com/openbank/libs/web/SyntheticTaint*.kt`
