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
import io.quarkus.security.runtime.QuarkusSecurityIdentity
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
import java.security.Principal

/**
 * Broker twin of [PensionFundPactFolderProviderVerificationTest]: runs on main-push only (gated on
 * `pactbroker.url`, which the PR lane blanks — ADR-0056) and PUBLISHES the verification result
 * `can-i-deploy` reads; the folder class cannot. The broker serves every consumer's pact, so every
 * state the folder twin and the negative twin handle is handled here too, or the replay fails with
 * MissingStateChangeMethod and publishes a failure that blocks pension-service deploys.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "service-account-openbank-pension", roles = ["ROLE_API"])
@Provider("openbank-pension-fund-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class PensionFundPactBrokerProviderVerificationTest {

    @Inject
    lateinit var vertx: Vertx

    @Inject
    lateinit var store: PensionFundStore

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var testIdentityAssociation: TestIdentityAssociation

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        // The missing-identity interactions expect 401; class-level @TestSecurity would otherwise
        // authenticate them.
        if (context != null && context.interaction.providerStates.any { it.name == NEGATIVE_AUTH_STATE }) {
            testIdentityAssociation.setTestIdentity(null)
        }
        context?.verifyInteraction()
    }

    @State(NEGATIVE_AUTH_STATE)
    fun stateNoValidM2mIdentity() {
        // verifyPacts clears the authenticated test identity for this interaction.
    }

    @State(TaxReportingPensionFundPactStates.FUND_STATE)
    fun fundWithSeptemberNav() {
        testIdentityAssociation.setTestIdentity(
            QuarkusSecurityIdentity.builder()
                .setPrincipal(Principal { "service-account-openbank-tax-reporting" })
                .addRole("ROLE_API")
                .build(),
        )
        TaxReportingPensionFundPactStates.fundWithSeptemberNav(vertx, store)
    }

    @State(PRICED_STATE)
    fun pricedHoldings(): Unit = PensionFundPactStates.pricedHoldings()

    @State(UNPUBLISHED_STATE)
    fun unpublishedNavHoldings(): Unit = PensionFundPactStates.unpublishedNavHoldings()
}
