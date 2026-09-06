// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Provider replay of the auth-NEGATIVE pact interactions (issue #8697's regenerated consumer
 * pacts: interest-service withholding remittance, sdd-service collection debit, swift-service
 * MT103 settlement — each asserting that a missing or expired token is refused with 401).
 *
 * These interactions cannot run in [TransactionPactFolderProviderVerificationTest]: that class
 * carries class-level `@TestSecurity(user = "pact-verifier", roles = [ROLE_OPERATOR])`, which
 * authenticates EVERY request the pact target makes, so the "missing token" case is
 * unrepresentable there — the replayed request arrives authenticated and the endpoint correctly
 * answers 201. Quarkus test security has no per-interaction opt-out, and pact-jvm's `@State`
 * callback is a plain method that cannot change it.
 *
 * The split is therefore by authentication posture, and it is enforced by Gradle, not by
 * convention: this class runs ONLY in the `pactNegativeTest` task, which sets
 * `pact.filter.providerState` to exactly [NEGATIVE_STATE] (and `pact.filter.emptyState=false`),
 * while the module's main `test` task excludes that state. The two filters are complements, so
 * every committed interaction is verified by exactly one task and neither task can silently
 * absorb the other's share. The 401 here is REAL: with no `@TestSecurity` the request is
 * anonymous, and `@RolesAllowed` refuses it — the same refusal the consumers contract on.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.transaction.it.PostgresRedpandaTestResource::class)
@Provider("openbank-transaction-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify
class TransactionPactNegativeAuthProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun before(context: PactVerificationContext) {
        context.target = HttpTestTarget("localhost", testPort.toInt())
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State(NEGATIVE_STATE)
    fun stateNoValidM2mIdentity() {
        // Intentionally empty: the interaction sends no (or an invalid) Authorization header and
        // the resource's own security must be what answers 401. Any setup here — seeding a
        // principal, stubbing a filter — could only weaken the very thing under contract.
    }

    companion object {
        const val NEGATIVE_STATE = "no valid M2M identity is presented"
    }
}
