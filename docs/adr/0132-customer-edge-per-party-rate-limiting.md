# ADR-0132 — Per-party request rate limiting at the customer edge

Date: 2026-06-29
Decision-Status: Accepted   <!-- Proposed | Accepted | Superseded by ADR-NNNN | Deprecated | Rejected -->
Delivery-Status: Shipped    <!-- Planned | Partial | Shipped | N/A — decision-only; the build-axis status, independent of the decision axis -->
Author(s): jiri.raska

## Context

`openbank-customer-edge` had no per-identity request throttling of its own. Ingress-nginx
already rate-limits by source IP, which is the right first line of defence for
unauthenticated traffic (e.g. `POST /onboarding/start`), but it does nothing to stop a
single compromised or misbehaving authenticated customer session from hammering the edge
— NAT/shared-IP customers would also make per-IP limits either too loose (shared IP,
many legitimate customers) or too strict (one customer's retry storm blocks everyone
behind the same IP). PSD2 Berlin Group base compliance (ADR-0090) expects the ASPSP to
throttle abusive API consumption per identity, not just per network address, and this
was an open gap: nothing enforced a per-party ceiling.

This was shipped directly (commit `d313737f`, #2492) without an ADR at the time — this
document formally records the decision retroactively (see `docs/adr/README.md`
"Numbering gaps": the code and config already cited "ADR-0132" in comments before the
file existed).

## Decision

We enforce a **fixed-window per-party rate limit** in `openbank-customer-edge`, backed
by the existing Valkey deployment (no new datastore):

- **Identity**: the `party_id` JWT claim, falling back to `sub` if `party_id` is absent.
  Requests with no valid JWT are **not** rate-limited here — they fall through to
  ingress-nginx's per-IP limit, since there is no stable per-identity key to throttle on
  before authentication.
- **Window**: fixed 60-second buckets, keyed `edge:rate-limit:{partyId}:{epochSecond/60}`.
  `INCR` is atomic; `EXPIRE` (70s — 60s window + 10s clock-skew grace) is set only on the
  first increment of each bucket, bounding key lifetime without a second round-trip on
  every request.
- **Limit**: `openbank.rate-limit.per-party.requests-per-minute`, default 100/min, via
  `RATE_LIMIT_PER_PARTY_RPM`.
- **Enforcement point**: a JAX-RS `@Provider @Blocking ContainerRequestFilter`
  (`RateLimitFilter`), so it runs before the request reaches any resource method and
  before any upstream call is made (fails cheap).
- **Response**: `429` with `X-RateLimit-Limit`, `X-RateLimit-Window: 60s`, and
  `Retry-After: 60` headers, so the mobile client (`openbank-app`) can back off instead
  of retrying immediately.

## Alternatives considered

- **Sliding-window / token-bucket algorithm** — rejected for v1: a fixed window allows a
  burst of up to 2x the limit across a window boundary (worst case: limit requests at
  `t=59s` then another limit's worth at `t=61s`). Accepted as a known, bounded imprecision
  in exchange for a single atomic `INCR` per request (no Lua script, no multi-key
  transaction). Revisit if abuse patterns actually exploit the boundary.
- **Rate limit at ingress-nginx by JWT claim** — rejected: nginx does not parse/validate
  the customer JWT: the party identity is only available once Quarkus has decoded it,
  so per-party enforcement has to happen in the app tier, not the ingress layer.
- **In-memory (per-pod) counter** — rejected: `openbank-customer-edge` runs multiple
  replicas; a per-pod counter would let a customer get `replicas × limit` throughput by
  chance of load-balancer routing. Valkey gives a single shared counter across all pods.
- **Rate limit at the OPA sidecar (ADR-0034)** — rejected: OPA is a policy *decision*
  point (allow/deny based on identity+action+resource), not a stateful counter; bolting
  request-rate state onto Rego would duplicate what Valkey already does well.

## Consequences

**Positive**
- Closes the per-identity abuse gap Berlin Group compliance (ADR-0090) expects; ingress
  IP-based limiting and this per-party limiting are now complementary layers.
- No new infrastructure: reuses the Valkey instance already deployed for other
  customer-edge caching/session needs.
- Fails cheap: a request over budget never reaches a resource method or an upstream call.

**Negative**
- Fixed-window boundary burst (see Alternatives) is a known, accepted imprecision.
- One more Valkey round-trip per authenticated request (`INCR`, occasionally `EXPIRE`).
  Not currently a measured bottleneck; revisit if edge latency budgets tighten.

**Neutral**
- Unauthenticated endpoints remain covered only by ingress-nginx's per-IP limit — this
  ADR does not change that posture, it adds a second, identity-scoped layer on top for
  authenticated traffic.

## Compliance impact

- PCI DSS: not applicable — no cardholder data involved.
- DORA: contributes to operational resilience (ICT third-party/API abuse protection) but
  is not itself a DORA-scoped control on its own.
- GDPR: not applicable — the rate-limit key is a transient counter (70s TTL), not stored
  personal data.
- PSD2: satisfies the Berlin Group base expectation (ADR-0090) that an ASPSP throttle
  abusive per-identity API consumption, not just per-network-address.
- CNB: not applicable beyond the PSD2 note above.

## References

- ADR-0090 (PSD2 XS2A — Berlin Group NextGenPSD2 base + ČOBS Czech profile) — the
  compliance expectation this decision satisfies.
- ADR-0034 (unified OPA authorization) — why rate limiting is not modeled as an OPA
  policy decision.
- Commit `d313737f` / PR #2492 — the original (pre-ADR) implementation this document
  retroactively records.
- `openbank-customer-edge/src/main/kotlin/.../ratelimit/RateLimiter.kt`,
  `RateLimitFilter.kt` — the enforcement code.
