# 02 — Architecture

Hexagonal per [ADR-0002](../../../../docs/adr/0002-hexagonal-architecture-per-service.md). The domain has zero framework imports and is enforced by `check-domain-purity.py`.

## Layers

```
domain/
  match/VopNameMatchPolicy.kt     the decision — pure, deterministic, symmetric, clock-free
  model/VopModels.kt              VopOutcome, VopNoDataReason, VopVerification (+ its invariants)

application/
  port/in/VopUseCases.kt          VerifyPayeeUseCase, VerifyPayeeCommand
  port/out/VopPorts.kt            AccountHolderNameLookupPort, VopSchemeRoutingPort,
                                  VopVerificationRecordPort, NameLookupUnavailableException
  usecase/VopVerificationService  routing + wiring + evidence — decides nothing itself

infrastructure/
  rest/VopResource.kt             POST /api/v1/vop/verify
  rest/dto/VopDtos.kt             wire mapping + explicit validation
  ratelimit/                      VopRateLimiter, VopRateLimitFilter
  client/                         AccountServiceClient, PartyServiceClient (local mirrors)
  adapter/                        AccountHolderNameLookupAdapter, NoSchemeRoutingAdapter
  persistence/                    VopVerificationEntity, VopVerificationRecordAdapter
  authz/AuthzProducer.kt          OPA sidecar PDP
  ClockProducer.kt                injected Clock (ADR-0100)
```

The use case is a **wiring** layer: it routes (domestic vs external), calls the ports, and records the evidence. Every *decision* is in `VopNameMatchPolicy`. That is the same shape as `sepa-instant`'s `ScreeningPolicy` and `sdd-service`'s `CollectionAuthorisationPolicy`.

See [the flow diagram](../diagrams/01-vop-verification-flow.mmd) and [the outcome decision tree](../diagrams/03-outcome-decision.mmd).

## The two-hop name lookup

The single most surprising fact about this service:

> **`openbank-account-service` holds no account-holder name at all.**

Its `accounts` table has no name column, and `V10__account_search_trgm.sql` indexes `account_number` only. The authoritative name is `parties.legal_name` / `parties.trading_name` in `openbank-party-service`, reachable from an account only via `party_id`. So resolving IBAN → name is:

```
IBAN → account-service GET /accounts/iban/{iban} → partyId
     → party-service   GET /parties/{partyId}     → legalName ?: tradingName
```

Both hops use an M2M token (`openbank-services`, minted by the oidc-client filter). We deliberately do **not** send account-service's `X-Customer-Party-Id` owner-scoping header: VoP is a check on behalf of a payer who is legitimately *not* the account's owner. That means vop-service holds a genuinely broader read than any customer session — it can resolve *any* domestic IBAN to a name. That privilege is contained by returning a *band* rather than the raw record, and by the disclosure asymmetry below. **Do not "fix" the missing owner header: it would break the regulation's purpose.**

**Why not denormalise the name onto account-service?** It would collapse the two hops to one. It was rejected (ADR-0171): it duplicates the authoritative name out of party-service and creates a second place for it to go stale — precisely the drift party-service exists to prevent. The latency cost is a known, accepted negative; a cache in vop-service is the escape hatch if measurement demands one.

## Fail-open — the decision to understand before anything else

| | sanctions gate (ADR-0032) | VoP (ADR-0171) |
|---|---|---|
| On outage | **Fails CLOSED** — payment held `PENDING` | **Fails OPEN** — `no_data` + warning, payment proceeds |
| Why | A sanctions miss is a legal breach | Refusing every payment during a VoP outage would itself breach the IPR execution-time obligation |

**These two sit side by side in the same pre-execution flow with opposite semantics, on purpose.** It looks like an inconsistency and it is not. `VopVerificationServiceTest` pins it with a test named for exactly that, so a future reader "fixing" VoP to match its neighbour trips a red test with the reason in its name.

The one thing that never happens on failure: a silent `MATCH`. Absence of an answer is `no_data`, never a positive one.

### The rate limiter fails CLOSED — and that is consistent

If Valkey is unreachable we cannot prove a caller is under the limit, so we return 429. That sounds like it contradicts the above, but it does not: **a 429 makes the caller render `no_data`, so the payment still flows with a warning** — VoP's fail-open behaviour reached by a different route. Failing open on the limiter would silently remove the only enumeration control during an outage, buying no payment availability at all.

## Resilience

`AccountHolderNameLookupAdapter` carries `@CircuitBreaker` / `@Retry` / `@Timeout(3s)`, copying `SanctionsScreeningAdapter`'s shape — including its `self`-injection trick:

```kotlin
@Inject lateinit var self: AccountHolderNameLookupAdapter
override fun lookupHolderName(iban: String) = self.lookupWithResilience(iban)
```

That is not a style quirk. SmallRye Fault Tolerance interceptors only fire through the CDI proxy — calling `this.lookupWithResilience(...)` directly would **silently bypass every annotation** on it.

The adapter also distinguishes two failure kinds, and the distinction is the point:
- **A 404 on either hop is not a failure** — it means we hold no name for this IBAN → `null` → `NO_DATA`/`ACCOUNT_NOT_FOUND`.
- **Anything else** (timeout, 5xx, circuit open) throws `NameLookupUnavailableException` → also `NO_DATA`, but with a different reason and a warning log.

## Why there is no Kafka

VoP publishes no domain events. It changes no business state — the only write is the evidence row. So there is no outbox, no dispatcher, no `openbank.outbox.dispatch-enabled` flag, and no KafkaUser/mTLS mounts in the Rollout. If a future change makes VoP emit an event, the outbox rules (ADR-0003/0013/0050) apply and the dispatch-enabled footgun becomes live.

## Evidence recording is best-effort, on purpose

`VopVerificationRecordAdapter` catches its own failures and logs at ERROR rather than propagating. A failure to write the evidence row must not fail the verification the payer is waiting on — turning a logging outage into a payment-flow outage would invert the fail-open decision through the back door. The residual (an attacker inducing a targeted DB outage to blind the record) is accepted and documented in the threat model.
