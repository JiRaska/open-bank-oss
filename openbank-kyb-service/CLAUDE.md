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
  (`configproperty-kotlin-defaults`). The OPA `PolicyDecisionPoint` now comes from libs-runtime's
  `OpaPolicyDecisionPointProducer` (opted in via `openbank.authz.opa-pdp-producer.enabled`), which
  reads `opa.timeout-ms` through `lateinit` field injection, so the trap no longer applies here.
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
- **A register adapter that cannot serve a scheme must not CLAIM it.** `RegistryRouter` picks the
  first adapter whose `supports()` answers true and only falls through to manual attestation when
  none does, so an adapter that claims `GB_CRN` without a Companies House API key turns "we could
  not check" into "the register said no" — a hard failure on every GB case instead of a review
  queue. `CompaniesHouseRegistryAdapter.supports()` therefore tests the key, and the class is
  `@Startup` so the "no key configured" warning is emitted at BOOT. An `@ApplicationScoped` bean is
  lazy: without `@Startup` that warning first appears on the first GB lookup, which for a bank with
  no UK customers is never, and the fallback then reads as a decision rather than a missing key.
- **Not every register publishes a representation rule, and the pack has to say so.** The UK puts
  the power to bind a company in the articles, not in the register, so `gb-v1.json` carries
  `representationRuleParser: null` and `listsRepresentationRule: false`, and the rule is DERIVED
  (model articles: any single director binds). A company whose officers could not be read is
  `UNKNOWN`, never `SOLE` — assuming one signature is the failure that produces an invalid
  contract. Companies House also publishes only the month and year of a director's birth, so
  `Representative.dateOfBirth` stays null for GB rather than being reconstructed.

- **`reviewReason` is read by the CUSTOMER, and it is on the review event.** `CaseResponse` returns it
  to the initiator and every signer, and `BUSINESS_ONBOARDING_REVIEW_REQUIRED` publishes it. The
  flags written there so far all restate what the customer told us, so naming them is harmless. A
  screening finding is not that: "X matched a sanctions list" in that field is tipping off, and so
  is a neutral-sounding "screening requires review" — it still names the cause. A finding therefore
  adds NOTHING to the reason, and the applicant view and the event carry
  `BusinessOnboardingCase.applicantReviewReason`: an AML review reads the same whatever raised it.
  Who matched what is in `Case.screening`, which `CaseResponse.from` fills only when
  `viewerPartyId` is null — the operator routes. Anything else you derive from a list, a score or
  another customer's data belongs behind the same door.
- **A finding is settled by a disposition, never by a side effect.** `reviewResolved` used to be able
  to approve a hit that its own pre-approval rebuild had just discovered, and dropping a listed
  owner from the declarations made the hit vanish. Every non-clear screen now opens a finding
  (`FindingDisposition.OPEN`); only `findingDisposed` changes it, with the authenticated operator
  and a reason; `reviewResolved` refuses while one is open or a sanctions one is confirmed; and
  `CaseScreening.rebuilt` keeps a leaver that carries a finding. If you add a path to SIGNED or
  ACTIVE, it goes through the same two checks.
- **`cases.update` returns the case at its NEW version — write that copy, not the one you passed
  in.** `kyb_cases.version` is an optimistic lock and the repository compares it explicitly,
  because the row is loaded fresh inside the write and `@Version` alone would overwrite a
  committed change. A second `update` of a stale copy answers `ConcurrentCaseUpdateException`
  (409 `CASE_CONCURRENT_UPDATE`). An event handler has nobody to retry for it: see
  `entityPartyActivated`.
- **A screening is complete for the people on the case NOW, or it is not complete.**
  `RelatedPartyScreeningService.screened` rebuilds the subject list (entity, representatives,
  register owners, declared persons) before every approval and carries answers over by subject; a
  stored "complete" from an earlier owner list is never trusted. An ownership lookup that answers
  `UNAVAILABLE` makes the screening incomplete by itself, because the list may be short.
- **`openbank.kyb.screening.enabled` ships OFF, and "off" stores nothing.** It needs a route to
  sanctions-service that the deployment has to supply in the same change; switched on without one,
  every case stops at its last signature with `503 SCREENING_PENDING`. Off is not "screened clear":
  `Case.screening` stays null, and `RelatedPartyScreeningService` is `@Startup` so the warning is
  printed at boot. The module's ITs run with it ON against `StubRelatedPartyScreening`.

- **Who is monitored is DERIVED from the case, in the transaction that writes it — never called
  from a use case.** `BusinessOnboardingCaseRepositoryImpl.update` brings `kyb_monitored_subjects`
  to `BusinessOnboardingCase.monitoredSubjects` on every write (ADR-0376). There are three paths
  into an approved state and three out of it; a registration one of them forgot is a customer
  nobody monitors, with nothing reporting it. If you add a status or a path, change `monitored`,
  not a call site. The relay (`MonitoringRegistrationService`) only sends what the store owes, and
  it runs under the service's OWN OIDC client: the registry refuses the shared one with 403.
- **A monitoring finding puts an APPROVED case back in `MANUAL_REVIEW`, and the customer must not
  be able to tell.** Customer responses read `applicantStatus` / `applicantReviewReason`, no
  lifecycle event is published for the review, and neither the applicant nor an idle timer can
  abandon the case. Anything new that a customer can read off a case goes through those two
  properties. Resolving such a review returns the case to where it stood and repeats nothing of
  the approval — no mandates, no `AGREEMENT_SIGNED`.
- **The screen's `subjectId` is the subject's opaque `ref`, not the name-derived `key`.** The key
  is a hash of the normalised name: fine as a map key, a pseudonym in a URL path. The registry is
  keyed by the same `ref`, which is what lets a clearance recorded in sanctions-service apply to
  monitoring. Never build an outbound identifier from `key`.
- **A new Kafka channel is two deploys.** `sanctions-monitoring-in` has no `group.id` in
  `application.yaml` and none in `kyb-service-msg-override.yaml` until the image carrying the
  channel is deployed; naming it there earlier is a boot failure. The KafkaUser is granted both
  the intended group and the fallback one for the window in between.

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
