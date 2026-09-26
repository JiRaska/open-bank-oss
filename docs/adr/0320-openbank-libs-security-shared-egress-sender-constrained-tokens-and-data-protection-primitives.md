---
date: 2026-09-26
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [security, libs, authn, privacy-gdpr]
summary: "Four shared primitives in com.openbank.libs.security, no new module: egress allowlist, DPoP/mTLS token check, Transit field protection, Valkey rate limit; optional JOSE/Redis types load only via opt-in service subclasses (#6240)."
---

# ADR-0320 — openbank-libs-security: shared egress, sender-constrained tokens and data-protection primitives

## Context

ADR-0279 decided that shared security primitives live in `openbank-libs-runtime` under
`com.openbank.libs.security`, and rejected a separate `openbank-libs-security` module "until a
primitive needs a dependency no service-wide module may carry". It named only the first three
primitives (telemetry, honeytoken filter, SLO metrics). This ADR is needed because four further
gaps, measured on `origin/main` on 2026-09-26, sit exactly in the space ADR-0279 reserved but did
not decide, and each is today either absent or re-implemented in one service:

1. **No egress / SSRF control.** Nothing in `openbank-libs-*` wraps outbound HTTP with a host
   allowlist, private-range denial or redirect policy. ADR-0059 built an allowlist for one feature
   (oversight webhooks) only; card-issuance's `OpenBaoTransitDekUnwrapper` uses a raw
   `java.net.http.HttpClient`.
2. **No sender-constrained token validation.** `docs/compliance/fapi2-self-assessment.md` lists
   "Sender-constrained tokens — DPoP or mTLS" as an unchecked hard-fail item; no `*.kt` under
   `openbank-libs-*` verifies a `cnf` claim or a DPoP proof. Service-to-service mTLS is not
   deployed either (issue #1914: the Istio STRICT manifest is referenced by no ArgoCD app).
3. **No shared field-protection primitive.** ADR-0262's envelope encryption via OpenBao Transit
   exists only inside `openbank-card-issuance-service/.../infrastructure/crypto/`; ADR-0189 keeps
   targeted (not blanket) column protection but says further fields are "decided per field", and
   today each such field would copy card-issuance's code.
4. **No per-client or distributed rate limit in libs.** `libs-runtime/web/RateLimitFilter.kt` is a
   per-pod concurrency semaphore (`openbank.rate-limit.max-concurrent-requests`, default 200);
   the only per-party limit is customer-edge's own Valkey filter (ADR-0132).

Relationship to adjacent ADRs — this ADR **amends**, it does not supersede:
- **ADR-0279** — amended: adds four named primitives to its "shared primitives" item and confirms
  its placement rule; its module-split trigger is not met (see Decision).
- **ADR-0177** — unchanged: workload identity decides *how a service proves who it is* to
  Keycloak. This ADR decides only how a *resource server* checks that a presented token is bound to
  its presenter; the two compose (a workload-identity token may itself be DPoP-bound).
- **ADR-0172** — unchanged: every key the field-protection primitive uses is a Transit key
  inventoried there; no parallel key registry.
- **ADR-0189** — unchanged in position (targeted, per-field protection, no blanket encryption);
  this ADR supplies the *mechanism* its per-field increments use.
- **ADR-0262** — amended: its OpenBao Transit envelope code is lifted into libs and card-issuance
  becomes the first consumer; its crypto decision is not reopened.
- **ADR-0132** — unchanged: customer-edge keeps its per-party limit; the libs primitive generalises
  the key extraction so other ingress services stop needing a copy.

## Decision

We will add four primitives to the existing `com.openbank.libs.security` package — ports in
`openbank-libs-domain`, implementations in `openbank-libs-runtime`. **No new Gradle module**:
ADR-0279's split trigger is a dependency no service-wide module may carry, and none of the four
needs one — card-issuance's Transit client is plain `java.net.http`, Valkey is already
`compileOnly("io.quarkus:quarkus-redis-client")` in libs-runtime, and JOSE verification uses
jose4j, which `quarkus-oidc` brings transitively. "openbank-libs-security" in the title names
this package, not a module.

**Classpath measurement (origin/main, 2026-09-26).** Neither JOSE nor Valkey is on *every*
consumer's classpath, and libs-runtime does not carry either at runtime:
- `quarkus-oidc` (and with it SmallRye JWT / jose4j): declared by **67 of 68** non-libs service
  modules (`implementation(libs.quarkus.oidc)`); `openbank-simulation` has none.
- `quarkus-redis-client`: declared by **33** service modules; the other ~35 have no Valkey client.

**Classpath-guard design — the #6240 hazard.** `CommonExceptionMappers.kt` records what happens
when libs-runtime auto-registers a `@Provider` whose signature names a type absent from a
consumer's runtime classpath: agent-service, analytics-sink and ap2-service failed at ArC init
with `ClassNotFoundException`. P2 (JOSE types) and P4 (Redis client types) would reproduce it on
the modules measured above. Decision, instead of a new module:
1. **No `@Provider`, `@ServerRequestFilter`, `@Interceptor` or CDI bean annotation in
   libs-runtime on any class whose signature, fields or supertypes name a JOSE or Redis type.**
   libs-runtime ships the logic as a plain `abstract class` (`DpopProofVerifier`,
   `ValkeyClientRateLimiter`) that Jandex never registers.
2. **Activation is an explicit opt-in in the consuming service:** a thin `@Provider` /
   `@Interceptor` subclass in that service's own `src/main`, exactly the #6240 remedy already used
   for the Hibernate mappers. A service that does not opt in never loads the class, so a missing
   optional dependency cannot break it.
3. **The one annotation libs-runtime does export, `@SenderConstrained` (#10928), is a pure
   `@InterceptorBinding`** that names no optional type; binding it without the opt-in subclass is
   inert, which `check-sender-constrained-endpoints.py` catches by requiring the subclass wherever
   the annotation is used.
4. **Guard test.** A libs-runtime test boots a Quarkus app with neither `quarkus-oidc` nor
   `quarkus-redis-client` on the test classpath and asserts it starts — the negative case for
   #6240, which today has no test.

This is the reason ADR-0279's split trigger is judged *not* met: the trigger is a dependency no
service-wide module may carry, and the opt-in-subclass pattern means libs-runtime carries neither
dependency at runtime. If the guard test ever cannot be made green without adding one of them as
a runtime dependency of libs-runtime, the trigger *is* met and P2/P4 move to a separate module.

| # | Primitive | Shape | Phase | Enforcing gate |
|---|---|---|---|---|
| P1 | **Egress client** | `SafeHttpClient` wrapping `java.net.http.HttpClient`: per-service host allowlist from config (`openbank.egress.allowed-hosts`), deny RFC 1918 / link-local / loopback / metadata addresses after DNS resolution, no cross-host redirects, bounded timeouts | 1 | new `check-raw-http-client.py`: new `HttpClient.newBuilder()`/`newHttpClient()` in `src/main` outside libs fails; today's sites baselined (ratchet) |
| P2 | **Sender-constrained token check** | `@SenderConstrained` binding + opt-in interceptor subclass (planned, not yet built — #10928), reusing the key-binding verification already in pid-service's `EudiPresentationVerifierImpl` (KB-JWT against `cnf.jwk`, jose4j) rather than a second JOSE path: validates a DPoP proof (RFC 9449: `htm`, `htu`, `iat`, `jti` replay cache, `ath`) against the token's `cnf.jkt`, or an mTLS `cnf.x5t#S256` against the client certificate (RFC 8705) | 2 (psd2-service first, the FAPI 2.0 surface) | new `check-sender-constrained-endpoints.py`: every XS2A resource in `openbank-psd2-service` carries the annotation; extends to TPP-facing services by list in `rules.yaml` |
| P3 | **Field-protection port** | `FieldProtector` (encrypt/decrypt with AAD; deterministic `tokenize` **delegates to the existing ADR-0189 keyed blind index**, `openbank-libs-domain/.../identity/BlindIndex.kt` — no second HMAC scheme) with an OpenBao Transit envelope adapter lifted from card-issuance and a local AES-GCM adapter for tests | 2 (card-issuance migrates first) | new `check-field-crypto-in-libs.py`: `javax.crypto.Cipher` or a Transit `encrypt/`/`decrypt/` path in a service's `src/main` fails outside the baseline |
| P4 | **Per-client rate limit** | `ClientRateLimitFilter`: fixed-window counter in Valkey keyed on `azp`/`party_id`/client-cert thumbprint, fail-open when Valkey is down, counted on `openbank_rate_limit_backend_unavailable_total` and alerted by `ClientRateLimitFailOpen` (counter increasing for 5 m — the limit is silently off while it fires); the per-pod semaphore stays as a last-resort bulkhead | 3 | none new: opt-in per service by config; the existing k6 abuse lane (ADR-0279 WS1) is the observable. Declared `n-a` for a gate because the per-service need is a product choice, not a defect class |

Service-to-service **mTLS** as network transport is *not* decided here: it belongs to ADR-0177's
north star (SPIFFE/mesh) and issue #1914. P2's mTLS branch only validates a certificate-bound
token where a TLS client certificate already reaches the service.

Each gate starts `advisory` with a baseline and flips to `enforced` once its primitive has one
migrated consumer, per the repo's ratchet convention.

## Alternatives considered

- **A new `openbank-libs-security` Gradle module.** Rejected: ADR-0279 already weighed this — every
  one of ~54 consumer build files, the services-ci path regexes and `libs-change-dependents.sh`
  would need editing, and the justifying condition (an optional heavy dependency) is not met by any
  of the four primitives. Revisit if P3 ever needs a Vault/KMS SDK instead of plain HTTP.
- **Delegate all four to the platform (egress gateway, Istio mTLS + RequestAuthentication, Envoy
  rate limit, a tokenization service).** Rejected as the *only* layer: #1914 shows the mesh is not
  deployed; a network egress gateway cannot see which service code path made the call; FAPI 2.0
  DPoP binding must be checked by the resource server against the request's method and URI; and a
  central tokenization service adds a synchronous money-path hop. Network controls remain welcome
  as defence in depth.
- **Keep per-service implementations and document the pattern.** Rejected: ADR-0279's own measured
  premise is that prose fixes did not stick and lib primitives plus gates did.

## Consequences

**Positive**
- One reviewed implementation per control instead of copies; card-issuance's crypto becomes
  reusable for ADR-0189's per-field increments.
- FAPI 2.0 checklist's sender-constraint item becomes implementable in psd2-service.

**Negative**
- libs-runtime changes rebuild the whole fleet (path-scoped CI); each phase is a fleet-wide build.
- DPoP adds a replay cache (Valkey) to psd2-service's request path.
- Keycloak realm changes (DPoP-bound tokens) must be verified against a local Keycloak before merge.

**Neutral**
- ADR-0279, ADR-0262 get an "amended by ADR-0320" reference when this is accepted.

### Delivery check

- `git grep -n 'class SafeHttpClient\|annotation class SenderConstrained\|interface FieldProtector\|class ClientRateLimitFilter' -- 'openbank-libs-*/src/main'` prints four lines.
- `gates.yaml` contains ids for P1–P3 checkers with `mode: enforced`.
- `git grep -n 'OpenBaoTransitDekUnwrapper' openbank-card-issuance-service/src/main` prints nothing (migrated to libs).
- `docs/compliance/fapi2-self-assessment.md` has the sender-constrained item ticked with a
  conformance-suite result under `docs/compliance/fapi2-results/`.

## Compliance impact

Mapping is to the obligations these primitives support, not a claim of compliance:

- PCI DSS: v4.0 Req. 3.5 (PAN rendered unreadable wherever stored) — P3; Req. 1.4 (controls on
  connections between trusted and untrusted networks) — P1. Cards use synthetic PANs today
  (ADR-0113), so this is readiness, not in-scope evidence.
- DORA: Regulation (EU) 2022/2554 Art. 9 (ICT protection and prevention: secure data in transit
  and at rest, strong authentication) — P1–P4.
- GDPR: Art. 32 (security of processing, incl. encryption and pseudonymisation) and Art. 25 (data
  protection by design) — P3.
- PSD2: RTS on SCA and CSC, Delegated Regulation (EU) 2018/389, Art. 22 (confidentiality and
  integrity of personalised security credentials) and Art. 35 (security of communication sessions)
  — P2 binds XS2A access tokens to the TPP that obtained them; P4 limits TPP abuse.
- CNB: not applicable — no CNB-specific requirement is cited by this decision.

## References

- ADR-0279, ADR-0177, ADR-0172, ADR-0189, ADR-0262, ADR-0132, ADR-0059, ADR-0113
- Issue #6240 (optional-type `@Provider` crashed consumers) and `openbank-libs-runtime/.../api/error/CommonExceptionMappers.kt`
- `openbank-pid-service/.../infrastructure/crypto/EudiPresentationVerifierImpl.kt` (prior art for P2)
- `openbank-libs-domain/.../identity/BlindIndex.kt` (reused by P3)
- `docs/compliance/fapi2-self-assessment.md`
- `openbank-libs-runtime/src/main/kotlin/com/openbank/libs/web/RateLimitFilter.kt`
- Issue #1914 (Istio STRICT mTLS not deployed)
- RFC 9449 (DPoP), RFC 8705 (OAuth mTLS), FAPI 2.0 Security Profile
