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
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Broker-side provider verification for document-service — the published-result counterpart to
 * [DocumentPactProviderVerificationTest], and the same gap #1009 closed for ledger-service.
 *
 * **The defect this fixes, measured 2026-08-31.** `@PactFolder` reads pacts off disk. It never
 * contacts the broker, so it publishes no verification result and creates no provider version.
 * document-service had only that half, so nothing it did ever reached the broker:
 *
 * ```
 * newest broker version for openbank-document-service : ee974ea3c2c7, 2026-08-07
 * document-service source commits since that date      : 15
 * version actually running in sandbox (3b62a4a5…)      : HTTP 404 — absent from the broker
 * broker's currently-deployed record                   : ee974ea3, which carries ZERO pacts
 * ```
 *
 * A version row with zero pacts makes `can-i-deploy` *unanswerable* rather than negative, so all
 * three consumers of this provider — domestic-payment, sepa-payment and statement-service — resolve
 * `UNVERIFIABLE`. Two of those are money-path, which is what failed the auto-deploy reconcile job
 * and left services stranded on stale images (issue #7621).
 *
 * **Why a second `@Provider` class is safe here.** CLAUDE.md warns that two broker-sourced
 * `@Provider` classes for one provider collide, because each fetches every pact the broker holds.
 * That is not this: the sibling is `@PactFolder`-sourced, which is the sanctioned pair rather than
 * the footgun. Both use [HttpTestTarget] exclusively — document-service has no message-consumer
 * contracts — so there is no HTTP-vs-MESSAGE dispatch fighting over `@BeforeEach`.
 *
 * **Gating.** `@EnabledIfSystemProperty(pactbroker.url)` keeps this skipped locally and on the PR
 * lane, where `_service-ci.yml` blanks `PACT_BROKER_URL` because the broker has no public ingress
 * (ADR-0056). It runs on main-push, where the broker properties are injected. The `@PactFolder`
 * sibling stays ungated and unaffected, so PR-time protection against a wrong request path — the
 * load-bearing half, issue #2338 — is unchanged.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.document.it.PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-document-service")
@PactBroker(enablePendingPacts = "true")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class DocumentPactBrokerProviderVerificationTest {

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
     * Empty for the same reason as the sibling class: `DocumentTemplateSeeder` runs on every boot
     * (`@Observes StartupEvent`) and inserts the six canonical templates, so the fresh Testcontainer
     * database already satisfies this state. Seeding again would assert the fixture, not the provider.
     */
    @State("the canonical document templates are seeded and published")
    fun stateTemplatesSeeded() {
        // Intentionally empty — see the KDoc above.
    }

    /**
     * `previewTemplate` is stateless and non-persisting by design (ADR-0248 #3): it merges a
     * caller-supplied `bodyHtml` with a caller-supplied data map through Handlebars and returns the
     * result. No `Document` row, no outbox event, nothing to set up.
     */
    @State("the template preview renderer is available")
    fun statePreviewRendererAvailable() {
        // Intentionally empty — see the KDoc above.
    }
}
