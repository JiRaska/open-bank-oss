// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.openbank.wealth.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Replays the consumer's missing-identity interaction against wealth-service (ADR-0279 #3): a
 * call with no M2M identity must answer 401 UNAUTHORIZED before any handler runs. That refusal is
 * what makes customer-edge, which proves ownership, the only way a customer reaches a holding.
 *
 * A separate class because [WealthPactFolderProviderVerificationTest] carries a class-level
 * `@TestSecurity`, so a recorded 401 could never verify there. The two classes carry disjoint
 * `@PactFilter` state patterns, so no interaction is replayed twice and none is skipped.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(WealthNegativeAuthProviderVerificationTest.InMemoryKafkaResource::class)
@Provider("openbank-wealth-service")
@PactFolder("../pacts")
@PactFilter(WealthPactStates.NEGATIVE_AUTH_STATE)
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class WealthNegativeAuthProviderVerificationTest {
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("wealth-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

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

    @State(WealthPactStates.NEGATIVE_AUTH_STATE)
    fun noValidM2mIdentity() {
        // Intentionally empty: the state IS the absence of an identity, which this class provides
        // by not declaring @TestSecurity. pact-jvm fails an interaction whose state has no handler.
    }
}
