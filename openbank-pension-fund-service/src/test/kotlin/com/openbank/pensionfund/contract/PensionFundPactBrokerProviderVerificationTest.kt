// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.pensionfund.application.port.PensionFundStore
import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.vertx.core.Vertx
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Broker-sourced twin of [PensionFundPactFolderProviderVerificationTest]: runs on main-push (the PR
 * lane has no broker, ADR-0056) and publishes the verification result `can-i-deploy` reads (#7621).
 * Same states via [PensionFundPactStates]; the missing-identity interaction runs with the test
 * identity cleared.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-tax-reporting", roles = ["ROLE_API"])
@Provider("openbank-pension-fund-service")
@PactBroker(enablePendingPacts = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class PensionFundPactBrokerProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var store: PensionFundStore

    @Inject
    lateinit var vertx: Vertx

    @Inject
    lateinit var testIdentityAssociation: TestIdentityAssociation

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        if (context == null) return
        context.target = HttpTestTarget("localhost", testPort.toInt())
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        if (context != null &&
            context.interaction.providerStates.any { it.name == PensionFundPactStates.NEGATIVE_AUTH_STATE }
        ) {
            testIdentityAssociation.setTestIdentity(null)
        }
        context?.verifyInteraction()
    }

    @State(PensionFundPactStates.FUND_STATE)
    fun fundWithSeptemberNav() = PensionFundPactStates.fundWithSeptemberNav(vertx, store)

    @State(PensionFundPactStates.NEGATIVE_AUTH_STATE)
    fun noValidM2mIdentity() {
        // verifyPacts clears the authenticated test identity for this interaction.
    }
}
