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
import io.quarkus.test.security.TestSecurity
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import javax.sql.DataSource

/**
 * Replays every committed consumer contract for wealth-service on every pull request, against the
 * real Flyway schema in a real Postgres. Today the only consumer is customer-edge (#11966): the
 * holdings routes and the list read behind `GET /net-worth`.
 *
 * Its broker twin is [WealthPactBrokerProviderVerificationTest]. Without the broker half the
 * provider would never publish a verification result, and `can-i-deploy` could not answer about
 * customer-edge at all.
 *
 * The class-level `@TestSecurity` would authenticate the recorded 401 too, so that one interaction
 * is filtered out here and replayed by [WealthNegativeAuthProviderVerificationTest].
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(WealthPactFolderProviderVerificationTest.InMemoryKafkaResource::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
@Provider("openbank-wealth-service")
@PactFolder("../pacts")
@PactFilter(WealthPactStates.AUTHENTICATED_STATES)
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class WealthPactFolderProviderVerificationTest {
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("wealth-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var dataSource: DataSource

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

    @State(WealthPactStates.ACTIVE_STATE)
    fun activeHolding() = WealthPactStates.activeHolding(dataSource)

    @State(WealthPactStates.PLEDGED_STATE)
    fun pledgedHolding() = WealthPactStates.pledgedHolding(dataSource)

    @State(WealthPactStates.NO_HOLDING_STATE)
    fun noHolding() = WealthPactStates.noHolding(dataSource)
}
