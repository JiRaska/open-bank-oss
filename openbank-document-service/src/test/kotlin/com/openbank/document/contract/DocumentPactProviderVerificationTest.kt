// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Provider-side Pact verification for the contracts consumers publish against
 * `openbank-document-service`. `@PactFolder` replays EVERY pact in `../pacts` naming this provider,
 * so the set below is what is committed today rather than a list this class maintains — all three
 * merged services holding a live synchronous REST client against document-service, each making the
 * same two calls (ADR-0248 #3): `GET /api/v1/documents/templates` then
 * `POST /api/v1/documents/templates/preview`.
 *
 *  - `openbank-sepa-payment` — `SepaPaymentDocumentServicePactConsumerTest` (#4299)
 *  - `openbank-domestic-payment` — `DomesticPaymentDocumentServicePactConsumerTest`
 *  - `openbank-statement-service` — `StatementDocumentServicePactConsumerTest`
 *
 * All three declare the same two provider states, which is why adding them cost no new
 * `@State` handler: the states are properties of the provider (seeded templates, a stateless
 * renderer), not of any one consumer. Deliberately ONE class — a second broker-sourced `@Provider`
 * class for the same provider collides, since each fetches every pact the broker holds.
 *
 * **Why this class is the load-bearing half.** A consumer pact ALONE cannot catch a wrong request
 * path: the Pact mock server answers whatever path the client asks for, so pointing a client at a
 * route that does not exist leaves the consumer test green. Only replaying the committed pact
 * against the real, running provider goes red — the #2269 lesson. sepa-payment's pre-existing cover
 * for these two calls was `DocumentServiceWireMockResource`, a consumer-authored stub that
 * hard-codes the same paths the client sends, and a stub written from the client cannot disagree
 * with it.
 *
 * `@PactFolder("../pacts")` and UNGATED, on purpose. A `@PactBroker` class would not do: the PR
 * lane blanks `PACT_BROKER_URL` (the broker has no public ingress, ADR-0056), so an
 * `@EnabledIfSystemProperty(pactbroker.url)` gate skips and the contract would be replayed only
 * after the merge — the state 16 of 27 pacts were in when #2327 was measured.
 * `check-pact-provider-replay.py` (gate `pact-provider-replay-coverage`) enforces exactly this
 * shape.
 *
 * `@IgnoreNoPactsToVerify(ignoreIoErrors)` makes a missing/unreadable pact folder a skip rather
 * than a failure, matching every other `@PactFolder` provider class in the fleet.
 *
 * IMPORTANT: if any of the three consumer tests changes the contract, regenerate that consumer's
 * pact (`./gradlew :<consumer-module>:test --rerun --tests "*.contract.*PactConsumerTest"`) and
 * commit that consumer's updated `<consumer>-openbank-document-service.json` under `pacts` in the
 * same PR (spelled without a leading slash-star on purpose: Kotlin block comments NEST, so a glob
 * written as a path prefix inside a KDoc opens a comment that never closes), or this test replays
 * a stale contract. `pact-drift-check.yml` enforces it over a scope derived from the `@Pact`
 * annotations, so a new consumer module is in scope automatically.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_documents_it")],
)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-document-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class DocumentPactProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    /**
     * The class-level `@TestSecurity` authenticates every request, which would turn a consumer's
     * 401/403 interaction green-by-accident into a mismatch — or, worse, hide a provider that
     * stopped enforcing authz. For [NO_IDENTITY_STATE] the test identity is cleared in
     * `verifyPacts`, so the request arrives anonymous, exactly as the consumer encoded it.
     */
    @Inject
    lateinit var testIdentity: TestIdentityAssociation

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        if (context?.interaction?.providerStates?.any { it.name == NO_IDENTITY_STATE } == true) {
            testIdentity.setTestIdentity(null)
        }
        context?.verifyInteraction()
    }

    /**
     * No seeding needed, and that is a property of the provider rather than an omission:
     * `DocumentTemplateSeeder` runs on EVERY boot (`@Observes StartupEvent`) and inserts the six
     * canonical templates — including the `POTVRZENI_O_PLATBE_CS`/`_EN` payment confirmations this
     * contract is about — into whatever database the service starts against, so the fresh
     * Testcontainer DB this test boots on already satisfies the state. Seeding again through the
     * repository would only assert that the fixture works.
     */
    @State("the canonical document templates are seeded and published")
    fun stateTemplatesSeeded() {
        // Intentionally empty — see the KDoc above.
    }

    /**
     * `previewTemplate` is stateless and non-persisting by design (ADR-0248 #3): it merges a
     * caller-supplied `bodyHtml` with a caller-supplied data map through Handlebars and returns
     * the result. No `Document` row, no outbox event, nothing to set up.
     */
    @State("the template preview renderer is available")
    fun statePreviewRendererAvailable() {
        // Intentionally empty — see the KDoc above.
    }

    /**
     * The negative-auth case (ADR-0279 #3): a request with no identity must be refused, not
     * silently authorised. No committed consumer pact currently exercises this provider state —
     * the handler exists so the boundary is pinned into the contract per the fleet-wide convention
     * ([com.openbank.lending.contract.LoanBookPactState.NO_IDENTITY]) and so a future consumer
     * interaction encoding it verifies immediately without a provider-side change.
     */
    @State(NO_IDENTITY_STATE)
    fun noIdentity() {
        // Intentionally empty: the state IS the absence of an identity (see verifyPacts).
    }

    private companion object {
        const val NO_IDENTITY_STATE = "no valid identity is presented"
    }
}
