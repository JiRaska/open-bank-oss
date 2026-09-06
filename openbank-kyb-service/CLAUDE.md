# openbank-kyb-service — service notes

Legal-entity verification and the multi-signer business onboarding case (**ADR-0284**), port
**8157**. Hexagonal per ADR-0002. Not a money-path service — it mints no value; it mints the
*entity party* and the *mandates* that later let a human move that entity's money, which is why
its OPA extension is written the way it is (see below).

## The three things that decide everything here

**1. The register is the authority, and the code says so in one place.** How many signatures bind
a company is `RegistryExtract.representationRule`, parsed from the register's own *způsob jednání*
text. Nothing else in this service may derive that number, and where the parser cannot read the
text it yields `UNKNOWN` — which routes the case to `MANUAL_REVIEW` rather than to a plausible
count. A wrong-but-lower count is a framework agreement that does not bind the customer's company;
that is the failure this whole service is shaped around.

**2. "Not found", "malformed" and "the register is down" are three different answers.**
`RegistryAdapter.lookup` returns null for an entity the register does not know and throws
`RegistryUnavailableException` for an outage; the identifier's own check digit is validated before
either. `RegistryRouter` consults `ManualAttestationRegistryAdapter` **last**, so a register that
is merely down can never silently degrade into self-declaration — it becomes a case a human looks
at. If you add an adapter, keep that ordering and that distinction.

**3. A country is DATA.** `country-packs/<cc>-v<N>.json` (the ADR-0212 compliance-pack shape)
carries the schemes a country issues, which register answers and what it lists, how UBOs are
established, the legal-form map and the evidence per form. Adding a jurisdiction is a pack file
plus, where its register has an API, an adapter. A `when (country)` branch anywhere in this service
is a bug — that is the drift ADR-0212 already paid for once.

## Gotchas this service has already been bitten by

- **The event payload is one data class per event type, and that is not verbosity.** The ADR-0006
  contract-agreement gate pairs each AsyncAPI message with the data class whose companion declares
  that `EVENT_TYPE`, then compares the documented properties against the CONSTRUCTOR properties. A
  shared "envelope + map" would leave the contract unchecked in both directions: a field could be
  added and never documented, or documented and never sent, and CI would agree with both. Add an
  event ⇒ add a class in `KybEventPayloads.kt` AND a message in
  `openbank-contracts/openbank-kyb-service/asyncapi.yaml`.
- **`@ConfigProperty` on a primitive field needs a Kotlin initializer, and that initializer is the
  bug.** It generates the synthetic constructor Arc builds the bean through, so the annotation is
  never applied and the field keeps the literal whatever the environment says
  (`configproperty-kotlin-defaults`). `AuthzProducer` therefore declares the OPA timeout as a
  `Duration` (an object → `lateinit` works) rather than a `Long`.
- **`openbank.temporal.enabled` is a BUILD-time property.** `@IfBuildProperty` is resolved during
  augmentation and frozen into the image; a container env var cannot flip it (lending's #6085). The
  `%prod` default is what ships. `%dev`/`%test` turn it off, and the worker half is switched
  separately by `openbank.kyb.worker.enabled` (`temporal_worker_switch_naming`).
- **`openbank.outbox.dispatch-enabled` must stay `true`** in `application.yaml`. It defaults to
  `false`; when it is false the case lifecycle never leaves `kyb_outbox` — no error, `attempt_count`
  stays 0, and every consumer stays permanently empty.
- **The taint annotations must sit BELOW `@RegisterRestClient`.** `check-synthetic-taint-rest-clients`
  reads the window between that annotation and the `interface` keyword, and it matches the boundary
  declaration as a single-line string literal — a ktlint-wrapped call is invisible to it. ARES and
  GLEIF are declared `@SyntheticTaintExternalBoundary` (a synthetic marker must not leave the
  platform); party-service is internal and PROPAGATES the marker, because an entity party minted
  for a synthetic case must stay tainted downstream.

## Authorization

`kyb_rest_ext.rego` is narrow on purpose, for the reason `consent_rest_ext.rego` documents at
length: every backend service authenticates on the shared `openbank-services` client whose service
account carries `ROLE_OPERATOR`, so a role-only write rule here would hand every service in the
fleet the ability to resolve a review or reject a case. The staff rule therefore excludes
`service-account-*` outright, and the customer actions are granted to the edge principal alone —
enumerated, never as a `kyb.` prefix, so `review.resolve` and `reject` stay bank-side.

Every customer handler is scoped by `X-Customer-Party-Id`, which the edge stamps from the token it
validated. A body naming a different initiator is refused at the edge and again here.

## Build / test

```
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :openbank-kyb-service:test --offline
```

`KybCaseApiIT` drives the whole two-signer journey over real HTTP against a real Postgres and then
reads `kyb_outbox` with plain JDBC — the only way to prove the case row and its event commit
together. A unit test with a mocked repository cannot tell which publisher a use case called.
