# OpenBank Delegation Service API

Customer-to-party delegated access (ADR-0232): granular, SCA-bound grants over products (accounts, savings goals, cards) and single objects (payment, statement, document).

## KYB authority client

The statutory representation read contract uses the named Delegation workload and the private
KYB listener over mTLS in deployed profiles. The client declares 2-second connect and read limits.
Pact replays current, stale, and unauthorized revisions against KYB. The JOINT signing flow consumes
this contract once its source and transport prerequisites are deployed.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Expired approval signatures / Podpisy po expiraci

Signing an expired approval request returns HTTP 409 with code `EXPIRED`, including after the expiry sweep has stored its `EXPIRED` status. An attempt refused by the initial expired-request guard does not consume the co-signer's SCA challenge or add a signature or event. SCA consumption before the later locked-state recheck is unchanged; this is not an atomic guarantee for concurrent state changes. Other closed states, including `REJECTED`, continue to return `NOT_PENDING` when signing is attempted after their deadline.

Podpis expirovaného požadavku na schválení vrací HTTP 409 s kódem `EXPIRED`, a to i poté, co úloha expirace uložila stav `EXPIRED`. Pokus odmítnutý úvodní kontrolou již expirovaného požadavku nespotřebuje SCA výzvu spolupodepisujícího ani nepřidá podpis nebo událost. Spotřeba SCA před následnou kontrolou zamčeného stavu se nemění; nejde o atomickou záruku pro souběžné změny stavu. Ostatní uzavřené stavy, včetně `REJECTED`, při pokusu o podpis po uplynutí lhůty nadále vracejí `NOT_PENDING`.

## Pending approvals when the mandate register is unreachable / Čekající schválení při nedostupném registru mandátů

`GET /api/v1/parties/{humanId}/approval-requests/pending` first reads, from party-service, the entities the human can currently sign for. When that acting-for read fails, the endpoint returns HTTP 503 with code `MANDATES_UNAVAILABLE`, the same answer as every other mandate read in business signing. It never returns 500, and never an empty list, which would falsely tell the signer that nothing waits for them.

`GET /api/v1/parties/{humanId}/approval-requests/pending` nejprve načte z party-service subjekty, za které může člověk právě podepisovat. Selže-li toto čtení, endpoint vrátí HTTP 503 s kódem `MANDATES_UNAVAILABLE`, stejně jako každé jiné čtení mandátů při podepisování za firmu. Nikdy nevrací 500 ani prázdný seznam, který by podepisujícímu mylně sdělil, že na něj nic nečeká.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Consumer contract verification

Consumer Pact tests exercise delegation-service's outbound REST interactions, including calls to SCA, PID, account, and card-issuance services. Their expected paths are literal values kept separate from the client annotations used to form requests, so an incorrect client route can fail the contract test. Run them with `./gradlew :openbank-delegation-service:consumerPactTest`.

## Customer recertification caller

Recertification confirmation accepts only the customer-edge workload acting for the matching grantor party. The workload must present its own verified service-account JWT (`azp`, subject and matching Keycloak username); a matching principal name or `ROLE_API` alone is insufficient. The grantor equality check also applies to an already recorded idempotent confirmation.

## Shared configuration defaults

This service's `application.yaml` does not declare the HTTP security response headers (`quarkus.http.header.*`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.

## Owner portfolio path parameter

`GET /api/v1/delegation-portfolios/owner/{ownerPartyId}` names its path parameter `ownerPartyId`, in the OpenAPI document and in the served resource alike. It must equal the authenticated active profile; any other value is rejected with `403`. Generated clients use `ownerPartyId`; the shared `partyId` parameter does not apply to this route.


## Business payment approval retries

The original consumed SCA challenge replays only the same entity, initiator, instruction kind
and canonical frozen payload. A changed instruction with the same challenge receives HTTP 409
before a new request or outbox event can be written.

## Which resource types a new offer may name (ADR-0375 D5 + D6)

`CAPABILITY_MATRIX` still lists `PAYMENT` and `STATEMENT`, and must: historical grants carry them
and the aggregate is how such a row is read back. `OFFERABLE_RESOURCE_TYPES` is the narrower set a
**new** offer may name — `ACCOUNT`, `SAVINGS_GOAL`, `CARD`, `DOCUMENT` — and `validateCandidate`
refuses anything else with `RESOURCE_TYPE_NOT_OFFERABLE`, before the ownership lookup.

**Why those two, and why the type rather than the id.** D5 leaves their ownership `UNVERIFIABLE` by
measurement: a statement is addressed by `(accountId, currency, legalSequence)` and no route
resolves a statement id, and a payment id resolves in no single service across six rails. D6 then
makes the rendered sealed artifact the one delivery mechanism for a shared object, and
`ExternalDisclosureService.issue` requires `resourceType == DOCUMENT`. So even once an oracle
existed, a grant over these two could never be *delivered* — the mechanism refuses them by type.
Reporting that as an ownership failure would invite the grantor to retry with a different resource
id, and no id would ever work.

**Nothing is lost.** A statement already renders as a document
(`GET /api/v1/statements/{accountId}/{currency}/{legalSequence}/document`), so sharing one with an
outsider is a `DOCUMENT` grant over that artifact — oracle and atomic view counting included.
Ongoing access to an account's statements is a different thing with its own capability,
`ACCOUNT_DOWNLOAD_STATEMENTS` (D7).

This turns a runtime refusal into a declared one: offers already failed on the UNVERIFIABLE oracle,
so no working path changes — only the error the grantor sees, and the fact that the vocabulary now
says so instead of each request discovering it. The `delegation-capability-enforcement` gate derives
its subjects from the matrix **intersected with** this set, so a type nobody can offer needs no
declared enforcer.

## Exposure is delivered sealed, never by shaping a live read (ADR-0375 D6)

`DelegationGrant.exposure` — `redactionRules`, `maxViews`, `watermark`, `allowDownload` — is
refused on every path that could create or revive authority: a new offer, `accept`, and
`reinstate`. That refusal is the **declared end state**, not a gap. Returning the original object
bytes with a redaction or view-count field merely echoed is a confidentiality defect that reads,
from the grantor's screen, exactly like an enforced control, and `maxViews` on a streaming route
could never be atomic.

The mechanism is `ExternalDisclosure`: a rendered sealed artifact over the document, with its own
expiry, OTP, link secret and view limit, whose consumption is a locked read-modify-write inside one
transaction (`ExternalDisclosureRepositoryImpl.mutateById`).

**The defect this uncovered.** `issue` used to require `grant.exposure` to be non-null and clamp
the disclosure's view limit to it. No grant can be ACTIVE *and* carry exposure — the offer refuses
it and `accept`/`reinstate` refuse it — so that requirement threw for every input a real caller
could present, and the sealed mechanism was unreachable. Its unit test did not see it because the
fixture hand-built an ACTIVE grant *with* exposure, a combination the lifecycle cannot produce. The
dependency also pointed the wrong way: the disclosure carries its own limit, which is the thing
that is actually enforced.

`check-delegation-exposure-sealed.py` (gate `delegation-exposure-sealed`) keeps both halves honest:
the refusal must stay, the sealed path's row lock must stay, every file referencing grant exposure
must be classified in `rules.yaml: delegation_exposure_delivery.referenced_in`, and `role:
enforcement` is refused outright.

### Joint statutory representation

The statutory issue and acceptance routes collect separate device-backed signatures under an
exact Party rule and KYB attestation. An operation fixes its grant terms, target revision, company,
representatives, office slots, quorum, and expiry. Every receipt binds one human and the frozen
operation. The last qualifying signature, the OFFERED or ACTIVE transition, its provenance, and the
existing lifecycle outbox event commit in one database transaction.

`openbank.delegation.statutory-collection-enabled` defaults to false. Ordinary individual and
verified sole-signature flows use their existing routes. New joint issues use the existing SOLO
usage policy. The statutory quorum governs creating or accepting a grant, while any existing
approval-group policy governs later use of that grant.

Delegation verifies the current KYB attestation over private-CA mTLS with its existing client
certificate and the named `openbank-delegation` bearer. The KYB provider route and policy
from #15662 must be present in the verified image before configuring the 8443 listener.
The sandbox collection switch stays off during this rollout.

The V37 migration adds operations, receipts, and grant provenance. Rollback disables collection and
retains these rows for audit. Deploy the sole issuer provenance guard and the named Party, KYB, and
SCA contracts before enabling collection in a test environment.
