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
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Provider-side Pact verification for the contracts consumers publish against
 * `openbank-document-service`. The first is `openbank-sepa-payment`'s payment-confirmation render
 * (ADR-0248 #3): `GET /api/v1/documents/templates` then
 * `POST /api/v1/documents/templates/preview` — see `SepaPaymentDocumentServicePactConsumerTest`.
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
 * IMPORTANT: if `SepaPaymentDocumentServicePactConsumerTest` changes the contract, regenerate
 * (`./gradlew :openbank-sepa-payment:test --tests "*SepaPaymentDocumentServicePactConsumerTest*"`)
 * and commit the updated `pacts/openbank-sepa-payment-openbank-document-service.json` in the same
 * PR, or this test replays a stale contract.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.document.it.PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-document-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class DocumentPactProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
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
}
