// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
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
 * Broker-sourced twin of [SepaPaymentPactProviderVerificationTest]: the half whose verification
 * results reach the Pact Broker, so `can-i-deploy` knows sepa-payment honours standing-order's
 * contract. It runs only on main push, where CI supplies `pactbroker.url`; the PR lane replays the
 * same pacts from git through the folder twin (#2327).
 *
 * Booted exactly like the folder twin, and it serves the same states — the twin-state-parity gate
 * holds the two sets equal (#9752). Unlike the folder twin it cannot filter by state, since the
 * broker hands it every interaction, so the missing-identity interaction is handled in
 * [verifyPacts] by clearing the test identity for that one replay.
 */
@QuarkusTest
@QuarkusTestResource(SepaPaymentPactProviderVerificationTest.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
@TestSecurity(user = "00000000-0000-0000-0000-000000008345", roles = ["ROLE_PAYMENTS"])
@Provider("openbank-sepa-payment")
@PactBroker(enablePendingPacts = "true")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class SepaPaymentPactBrokerProviderVerificationTest {

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
        // The missing-identity interaction expects 401. Class-level @TestSecurity otherwise
        // authenticates every broker replay, including that one, and would produce a 201.
        if (context != null && context.interaction.providerStates.any { it.name == NEGATIVE_AUTH_STATE }) {
            testIdentityAssociation.setTestIdentity(null)
        }
        context?.verifyInteraction()
    }

    @State(NEGATIVE_AUTH_STATE)
    fun stateNoValidM2mIdentity() {
        // verifyPacts clears the authenticated test identity for this interaction.
    }

    @State("sepa-payment can accept a new credit transfer")
    fun stateCanAcceptNewTransfer() {
        // Deliberately empty — see the folder twin's KDoc.
    }
}
