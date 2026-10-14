// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Main-push twin publishes both authenticated and anonymous provider verification.
 * The consumer's 401 interaction clears the test identity before replay; its local PR twin is
 * [PensionReportingNegativeAuthProviderVerificationTest].
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
@OidcSecurity(
    claims = [
        Claim(key = "iss", value = "https://issuer.example.invalid"),
        Claim(key = "sub", value = "tax-client-subject"),
        Claim(key = "azp", value = "openbank-tax-reporting"),
        Claim(key = "preferred_username", value = "service-account-openbank-tax-reporting"),
    ],
)
@Provider("openbank-pension-service")
@PactBroker
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class PensionReportingPactBrokerProviderVerificationTest {
    @Inject
    lateinit var testIdentityAssociation: TestIdentityAssociation

    @Inject
    lateinit var mapper: ObjectMapper

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        checkNotNull(context).target = HttpTestTarget("localhost", testPort.toInt())
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        val verification = checkNotNull(context)
        if (verification.interaction.providerStates.any { it.name == "no valid M2M identity is presented" }) {
            testIdentityAssociation.setTestIdentity(null)
        }
        verification.verifyInteraction()
    }

    @State("no valid M2M identity is presented")
    fun missingIdentity() {
        // verifyPacts removes the test identity before replaying this interaction.
    }

    @State("pension participant activity may exist for the third quarter of 2026")
    fun configureState() {
        // Fresh Flyway database represents the allowed empty period; SQL aggregation still runs.
    }

    @State("pension participant technical source capture is permitted for March 2010")
    fun capturePermitted() {
        // The test provider is configured for the Pact company; an ended period needs no rows.
    }

    @State("pension participant technical source snapshot exists for March 2009")
    fun immutableSnapshotExists() = ParticipantSnapshotPactFixture.seed(mapper)
}
