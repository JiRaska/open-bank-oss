---
date: 2026-09-30
decision-status: proposed
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [libs, api-contract, architecture, observability]
summary: "The kernel owns the error model: a closed catalogue of stable codes, one RFC 9457 body that is a superset of ApiError, typed domain exceptions with one mapper, and a measured, phased demotion of raw JDK exceptions to 500."
followup: "#11610 — phases b-d: migrate 143 service-local mappers, build the catalogue gate, demote the generic JDK mappers"
---

# ADR-0326 — Kernel-owned error model: stable error codes, one problem-details response shape, typed domain exceptions

## Context

ADR-0049 (D4) decided that a service must never register an `ExceptionMapper` for a JDK exception
type libs already maps, and ADR-0122 put the shared mappers in `openbank-libs-runtime`. Neither
decided what an error **is**: which codes exist, what the body looks like, or which exception a
service should throw. ADR-0048 versions the API contract and does not describe its error half.
ADR-0317 (proposed) splits `openbank-libs-domain` by bounded context and needs to know where error
codes live. This ADR is needed because the answer to "do unified error codes belong in the kernel"
is already being given three different ways in the code, and the measurements below say which one
is right.

**Yes — the contract belongs in the kernel; the codes themselves belong to their bounded context.**
The evidence, all measured on `origin/main` at `4489cd5636` (2026-09-30):

**One envelope is declared, five are served.** `ApiError` is documented as "the fleet-wide error
envelope" (`traceId, status, code, message, timestamp, details`). Of the 55 modules that depend on
libs-runtime, 25 carry **143 service-local `ExceptionMapper`s** in `src/main`. Grouped by the body
they build:

| Body | Mappers |
|---|---|
| `ApiError` | 50 |
| `{error}` | 37 |
| `{error, status}` | 24 |
| `{error, message}` | 17 |
| Berlin Group `tppMessages` (mandated by the standard, out of scope) | 5 |
| hand-rolled problem details (`type/title/status/…`) | 3 |
| one-offs (`{code, error}`, `{reason}`, `{error, violations}`, `{error, mandateId}`, a DTO) | 7 |

So 35% of service-local mappers return the declared envelope; 8 modules use it in a mapper, 22
modules have at least one mapper that does not. In the `{error}` family the member sometimes holds a
code and sometimes a sentence. 32 of the 143 are `…NotFound…` and 18 are conflict-type, the two
shapes the kernel bases `ResourceNotFoundException` / `ResourceConflictException` already cover —
and 9 modules use those bases.

**Codes are string literals, not a catalogue.** The kernel enum `api.error.ErrorCode` has 18
entries, 8 of them account-domain codes (`ACCOUNT_NOT_FOUND`, `INSUFFICIENT_FUNDS`, …) that sit in
the kernel only because nowhere else existed; 6 modules reference it. `BUSINESS_RULE_VIOLATION` is
emitted as a bare literal beside it, and `WebApplicationExceptionMapper` mints an open family
(`HTTP_<status>`). Outside the kernel, 20 modules spell roughly 75 distinct `UPPER_SNAKE` literals
in code position (a literal-only probe, so a lower bound). All are flat `UPPER_SNAKE`; none is
dotted. 7 codes are emitted by more than one module, and the same meaning has several spellings:
`RATE_LIMIT_EXCEEDED` / `TOO_MANY_REQUESTS`; `VALIDATION_ERROR` / `BAD_REQUEST` / `FORMAT_ERROR` /
`INVALID_PARAMETER`; `CONFLICT` / `CONCURRENT_MODIFICATION` / `INVALID_STATE` / `INVALID_TRANSITION`.
The same meaning also has several statuses: a not-found exception answers 404 in most services and
422 in one.

**The published contract does not describe any of it.** 59 services ship an `openapi.yaml`; 28
define their own `ApiError` schema, in 5 different property sets — 22 of them `{code, message}`,
two fields of the six actually served. No spec `$ref`s a shared error component (there is none),
and 3 specs name any code value at all. `oasdiff` compares a spec with its previous version, so
none of this is visible to the ADR-0048 gate.

**Consumers mostly cannot use codes, so they parse around them.** admin-ui branches on exactly one
code (`ACTOR_NOT_PERMITTED`, read from the `error` member). customer-edge hand-builds 85 error
bodies in a further shape, `{error, code}`. Of 119 pact interactions in 69 pacts, 18 are error
responses: 13 assert no body, 3 assert `{error}`, 1 asserts `code`. No alert rule is keyed on an API
error code. The mobile app reaches the fleet through customer-edge and was not measured here.

**The generic mappers report internal faults as the caller's.** libs-runtime maps
`IllegalArgumentException` → 400, `IllegalStateException` → 422 `BUSINESS_RULE_VIOLATION` and
`NoSuchElementException` → 404, each with the exception's own message as the response text. Those
are also what `require`, `check`, `error()` and `.first { }` throw from anywhere below the resource:
842 `require(`, 414 `requireNotNull(`, 176 `check(`, 60 `checkNotNull(` and about 220 `error(` call
sites across 45 modules, against 142 explicit `throw`s of the three types. A Kotlin
`CancellationException` is an `IllegalStateException`. So an internal invariant failure is answered
as a client error, stays out of the 5xx error budget the money-path SLOs are computed from, and its
message — written for a log — is sent to the caller. The kernel already works around its own
mapper: `IdempotencyKeyReusedException`, `ResourceNotFoundException` and `PolicyDecisionException`
are each documented as deliberately *not* extending the JDK type, for this reason.

**Why it cannot simply be switched off.** 331 of those `require`/`requireNotNull` calls are in REST
layers, where a 400 is the correct answer, and the enforced `nonnull-jaxrs-param-ratchet` gate
tells authors to write exactly that. Nothing today distinguishes a `require()` on a request
parameter from one on an internal invariant, and nothing measures how often either reaches a caller.

## Decision

We will make the error model a kernel-owned contract in five parts, and deliver it in four phases of
which only the first changes no existing response.

### 1. Error codes are a closed, enumerable catalogue

- `com.openbank.libs.domain.error.ErrorCode` is the contract: `code`, `category`, `title`,
  `retryable`. It is framework-free (ADR-0002) and carries no HTTP status.
- Implementations are **enums**: `PlatformErrorCode` for the codes the kernel itself emits, and one
  enum per bounded context for the rest, owned by that context (in its service today, in its
  context module once ADR-0317 lands). A domain code never goes into the kernel enum.
- The wire spelling stays `UPPER_SNAKE_CASE`, at most 64 characters, and a domain code is prefixed
  with its domain (`ACCOUNT_NOT_FOUND`). Platform codes are unprefixed (`NOT_FOUND`).
- A published code is **never renamed, never reused for another meaning and never moved to another
  category**. Its catalogue line is append-only, the same discipline as an event contract.
- `ErrorCategory` is closed — `VALIDATION`, `UNAUTHENTICATED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`,
  `RULE_VIOLATION`, `RATE_LIMITED`, `UPSTREAM_FAILURE`, `UNAVAILABLE`, `INTERNAL` — and libs-runtime
  holds the **one** table from category to HTTP status (400, 401, 403, 404, 409, 422, 429, 502,
  503, 500). A code therefore cannot choose its own status.
- `retryable` defaults from the category (`RATE_LIMITED` and `UNAVAILABLE` are retryable) and a
  code may override it; an exception may carry a `retryAfter`, rendered as `Retry-After`.

### 2. One response body: RFC 9457, as a superset of `ApiError`

`ProblemDetail` carries the RFC 9457 members (`type`, `title`, `status`, `detail`, `instance`), the
extensions `code`, `correlationId`, `retryable` and `violations[]`, **and** every `ApiError` member
under its existing name (`traceId` = `correlationId`, `message` = `detail`, `timestamp`, `details`
= `violations`). `type` is `urn:openbank:error:<code in kebab-case>` — the URN form two services
already emit for `IDEMPOTENCY_KEY_REUSED`.

The media type is `application/problem+json` when the request's `Accept` names it, otherwise
`application/json`; the document is identical either way. A caller asks for it by listing it beside
a type the endpoint produces (`Accept: application/problem+json, application/json`): the framework
negotiates `Accept` against the resource's `@Produces` before the method runs, so an `Accept` naming
only `problem+json` is a 406 that no mapper sees.

What this means on the wire:

- For a response already in the `ApiError` shape, moving it onto the typed hierarchy **adds**
  members and removes none. To `oasdiff` that is an added optional response property — `additive`
  in `check-api-contract.py`, a MINOR bump of that service's `info.version`, no URL major.
- The status code and the `code` value of an existing response do not change when it migrates;
  where a service's current status disagrees with its category, that is a separate, declared
  change for that service.
- The default `Content-Type` does not change. Making `application/problem+json` the default would
  be a removed response media type — breaking under ADR-0048 — and is not decided here.
- A body in one of the `{error…}` shapes loses nothing either: phase b gives the mapper a
  per-service compatibility member (`error`, holding the code or the message, whichever that
  service published) that stays until its consumers have moved.

### 3. A typed exception hierarchy, and one mapper

`DomainException(category, errorCode, clientMessage, violations, retryAfter, internalDetail, cause)`
in libs-domain, with one subclass per category a caller can be answered with: `ValidationFailure`,
`AuthenticationRequired`, `AccessDenied`, `NotFound`, `Conflict`, `RuleViolation`, `RateLimitExceeded`,
`UpstreamFailure`, `Unavailable`. None extends a JDK exception type the generic mappers claim.

libs-runtime registers **one** mapper, `DomainExceptionMapper`, for the base type. A service throws
a subclass (or its own subclass of one) and writes no mapper.

An exception whose code is malformed, or belongs to a different category than its class, is
answered as `INTERNAL_ERROR` and carries nothing over. The constructor does not throw: an exception
that fails to construct would surface as a different exception and hide the intended one.

### 4. Message hygiene

Only two kinds of text leave the process: the catalogue `title` and the exception's `clientMessage`,
which is written for the caller. `Throwable.message` — the `internalDetail` — is logged and never
rendered. No stack trace, no SQL, no class name and no authorization-policy reason is a response
member. `Violation` does not carry the rejected value. The existing mappers that render
`exception.message` (the three generic ones, and the policy and `WebApplicationException` mappers)
are brought under this rule in phase c, not before, because changing their text is a behaviour
change for every service at once.

### 5. Observability

- `openbank.api.errors{code, category, status}` counts every typed error. `code` is a label only
  because the catalogue is closed and the wire format is validated; a malformed code is never
  used as a label.
- `openbank.api.generic_exception_mapper.fired{mapped, thrown, status}` counts every time one of the
  three generic JDK mappers answers a request. `thrown` is the concrete class, which is what
  separates a `CancellationException` from a business rule.
- The typed mapper logs the code as a structured field (`errorCode`) beside the correlation id.
- Error-budget semantics: `INTERNAL`, `UPSTREAM_FAILURE` and `UNAVAILABLE` are 5xx and spend the
  budget; every other category is the caller's and does not. Phase c moves internal faults that
  are today reported as 4xx into the budget — an SLO that gets worse on paper on the day it
  becomes true.

### Phases

- **a — additive (this ADR's first slice, shipped with it).** The contract, `PlatformErrorCode`
  (12 codes), the hierarchy, `requireParam` / `requireValid`, `ProblemDetail`, the one mapper, both
  counters. No existing request is answered differently.
  - *Addendum (kernel money failures, additive).* The kernel's own `Money` / `CurrencyCode`
    construction failures are kernel codes, not a service's: `PlatformErrorCode` gains
    `AMOUNT_SCALE_EXCEEDED` and `CURRENCY_UNSUPPORTED` (both `VALIDATION`), raised through
    `InvalidMoneyException(reason: InvalidMoneyReason)`. That type stays an
    `IllegalArgumentException`, so every existing catch and the generic 400 still apply; libs-runtime
    adds a narrower `InvalidMoneyExceptionMapper` that renders the reason's code as a `ProblemDetail`
    at the same status. `Money.parseInbound` is the API-boundary helper that attributes the failure to
    its request field.
- **b — migrate, under a ratchet.** Services move their exceptions onto the hierarchy and delete
  their mappers, `ResourceNotFoundException` / `ResourceConflictException` fold into it, and domain
  codes move out of the kernel enum. Two gates: `error-code-catalogue` (codes derived from every
  `ErrorCode` enum; fleet-wide uniqueness, append-only against a committed baseline) and a
  shrinking-baseline ban on new service-local `ExceptionMapper`s, starting at 143. The catalogue
  is generated into a reference document and into one shared OpenAPI error component that specs
  `$ref`. REST-layer `require` / `requireNotNull` move to `requireParam` / `requireValid`.
- **c — demote, per service, behind a flag.** Once a service's firing counter has been read — zero,
  or every remaining `thrown` class triaged — that service flips its generic mappers to answer
  `INTERNAL_ERROR` with a constant message. The measurement decides the order; money-path services
  are not first.
- **d — remove.** The generic mappers, the flag, and the legacy `api.error.ErrorCode` enum go.

## Alternatives considered

- **Keep `ApiError` and only add a code list.** No new body. Rejected: three mappers in three
  services have already hand-rolled `type/title/status/detail`, so the fleet is converging on
  RFC 9457 anyway, one incompatible copy at a time; and because the new body is a superset, the
  standard costs existing consumers nothing.
- **Replace the body and serve `application/problem+json` outright.** The clean end state. Rejected
  for now: it removes members and a media type that pacts, RestAssured suites and customer-edge
  read today, which ADR-0048 classifies as breaking for every service at once.
- **Dotted `<DOMAIN>.<REASON>` codes.** Reads well and namespaces by construction. Rejected: none of
  the roughly 75 codes on the wire is dotted, so adopting it means either renaming every published
  code — breaking the rule this ADR exists to introduce — or keeping two spellings forever.
- **One closed kernel enum for every code.** A single file to read. Rejected on the evidence: the
  existing kernel enum is that design, 8 of its 18 entries are account codes, 6 modules use it and
  20 mint literals instead. A kernel that must be edited for every domain's error is a kernel
  nobody waits for, and it runs against ADR-0317's direction.
- **HTTP status as a property of each code.** Rejected: it puts a transport concept in libs-domain,
  and per-code status is exactly what let the same meaning answer 404 in one service and 422 in
  another.
- **Demote the generic mappers now.** Closes the hygiene gap in one change. Rejected: 331
  request-validation sites depend on the 400 they produce, and nobody has measured how often the
  other ~1,400 call sites reach a caller. Phase a exists to make that a number.

## Consequences

**Positive**
- A service stops writing mappers: the next exception is a subclass and a catalogue line.
- Clients get one document and a code they may branch on, with retry semantics stated instead of
  inferred from the status.
- Internal faults become visible as 5xx, and their text stops leaving the process.
- The cost of demotion is measured per service before any service pays it.

**Negative**
- Two envelope types (`ApiError`, `ProblemDetail`) and two `ErrorCode` types (the legacy runtime
  enum and the new contract) coexist until phase d.
- The body is larger: every typed error carries both vocabularies until consumers have moved.
- Phase b touches 25 modules, several money-path, each needing its own API-contract bump.
- Phase c makes several availability SLOs read worse on the day they start telling the truth.

**Neutral**
- The default media type and every existing status code are unchanged by this ADR.
- psd2's Berlin Group error body is mandated by that standard and stays outside the model.
- `DomainExceptionMapper` reads the correlation id from the same MDC key the existing mappers use.

**Enforcement.** Phase a is held by two things that already run: `PlatformErrorCodeCatalogueTest`
in the libs-domain build (the enum against a committed, append-only baseline, both directions) and
the enforced gate `no-service-local-exceptionmapper-collision-with-libs-runtime`, extended to
`DomainException`. The two phase-b gates above are planned, not built; until they are, "never
rename a domain code" is prose for every enum except the kernel's.

### Delivery check

```
# phase a — contract, hierarchy and mapper exist
git grep -c 'interface ErrorCode' -- openbank-libs-domain/src/main        # 1
git grep -c 'class DomainExceptionMapper' -- openbank-libs-runtime/src/main # 1
grep -vc '^#' openbank-libs-domain/src/test/resources/error-codes/platform.baseline  # >= 12

# phase b — done when this prints only psd2's Berlin Group mappers
git grep -lE 'ExceptionMapper<' -- '*/src/main/**.kt' ':!openbank-libs*'
grep -n 'id: error-code-catalogue' .github/gates/gates.yaml                # present, mode: enforced

# phase c/d — done when this prints nothing
git grep -nE 'class (IllegalArgument|IllegalState|NoSuchElement)ExceptionMapper' -- openbank-libs-runtime/src/main
```

## Compliance impact

- PCI DSS: not applicable — no cardholder-data flow or control changes; error bodies carried no card data before and carry none after.
- DORA: availability reporting for ICT services becomes more accurate once internal faults are counted as server errors (phase c); no reporting obligation changes.
- GDPR: supports data minimisation in error responses — internal text, and rejected input values, are not echoed to the caller.
- PSD2: not applicable — the XS2A interface keeps the Berlin Group error body its standard mandates.
- CNB: not applicable — no supervisory return, outsourcing or four-eyes control is affected.

## References

- ADR-0002 (framework-free domain), ADR-0048 (API-contract version axis), ADR-0049 D4 (no
  service-local mapper for a libs-owned type), ADR-0122 (domain/runtime split), ADR-0317 (per-context
  libs modules).
- RFC 9457, Problem Details for HTTP APIs.
- Issue #526 (mapper collision), #10911 (mapper consolidation, phase 1).
- `openbank-libs-domain/src/main/kotlin/com/openbank/libs/domain/error/`,
  `openbank-libs-runtime/src/main/kotlin/com/openbank/libs/api/error/`.
