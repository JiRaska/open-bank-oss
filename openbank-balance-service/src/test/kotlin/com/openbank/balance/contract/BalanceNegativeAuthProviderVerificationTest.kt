// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Replays the consumers' missing-identity interactions against openbank-balance-service (ADR-0279 #3): a
 * balance read or movement with no M2M identity must answer 401 before the handler runs.
 *
 * A second class rather than one more `@State` method: [BalancePactFolderProviderVerificationTest] carries a class-level
 * `@TestSecurity`, so every replay it runs arrives authenticated and a recorded 401 could never
 * verify there. `@TestSecurity` is absent here on purpose; the two classes carry disjoint
 * `@PactFilter` state regexes, so no interaction is replayed twice and none is skipped. Named
 * `*ProviderVerificationTest` so the `providerPactTest` source set picks it up (#10801 is the
 * cost of the other spelling). Same shape as ledger's and transaction's negative twins.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.balance.it.PostgresRedpandaTestResource::class)
@Provider("openbank-balance-service")
@PactFolder("../pacts")
@PactFilter(NEGATIVE_AUTH_STATE)
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class BalanceNegativeAuthProviderVerificationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        if (context == null) return
        context.target = HttpTestTarget("localhost", testPort.toInt())
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State(NEGATIVE_AUTH_STATE)
    fun stateNoValidM2mIdentity() {
        // Intentionally empty: the state IS the absence of an authenticated identity, which this
        // class provides by not declaring @TestSecurity. Declared because pact-jvm fails the
        // interaction outright when no handler matches the state name.
    }
}

/** The state name every consumer of openbank-balance-service uses for its missing-identity interaction. */
const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
