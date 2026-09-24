// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/**
 * The broker half of lending's provider pair (ADR-0314 D4): the same replay as
 * [LendingLoanBookPactProviderTest], sourced from the Pact Broker and gated on `pactbroker.url`,
 * so it runs on main-push only and PUBLISHES the verification result `can-i-deploy` reads — which
 * the folder class structurally cannot. It is the only permitted second
 * `@Provider("openbank-lending-service")` class; both use [LoanBookPactState].
 */
@QuarkusTest
@QuarkusTestResource(LendingLoanBookPactProviderTest.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
@Provider("openbank-lending-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class LendingLoanBookPactBrokerProviderTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @Inject
    lateinit var testIdentity: TestIdentityAssociation

    /** Same negative-auth handling as the folder twin: no identity for the 401 interaction. */
    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        if (context?.interaction?.providerStates?.any { it.name == LoanBookPactState.NO_IDENTITY } == true) {
            testIdentity.setTestIdentity(null)
        }
        context?.verifyInteraction()
    }

    @State(LoanBookPactState.NAME)
    fun oneFixedLoan() = LoanBookPactState.seed()

    @State(LoanBookPactState.NO_IDENTITY)
    fun noIdentity() {
        // Intentionally empty: the state IS the absence of an identity.
    }
}
