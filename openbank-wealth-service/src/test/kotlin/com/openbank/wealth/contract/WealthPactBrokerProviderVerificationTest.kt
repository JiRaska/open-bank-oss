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
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.wealth.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import javax.sql.DataSource

/**
 * Broker-sourced provider verification for wealth-service: the half that publishes results, so
 * `can-i-deploy` can answer about customer-edge. Its sibling [WealthPactFolderProviderVerificationTest]
 * reads pacts off disk and publishes nothing.
 *
 * Gated on `pactbroker.url`: the PR lane blanks it because the broker has no public ingress
 * (ADR-0056), so this runs on main-push. The states live in [WealthPactStates] rather than in a
 * shared superclass, because a parent and a subclass both annotated `@QuarkusTest` fail with
 * `TestInstantiationException`.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(WealthPactBrokerProviderVerificationTest.InMemoryKafkaResource::class)
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
@Provider("openbank-wealth-service")
@PactBroker(enablePendingPacts = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class WealthPactBrokerProviderVerificationTest {
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("wealth-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var dataSource: DataSource

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
        // The missing-identity interaction expects 401. The class-level @TestSecurity would
        // otherwise authenticate that replay too and answer as if signed in.
        if (context != null &&
            context.interaction.providerStates.any { it.name == WealthPactStates.NEGATIVE_AUTH_STATE }
        ) {
            testIdentityAssociation.setTestIdentity(null)
        }
        context?.verifyInteraction()
    }

    @State(WealthPactStates.ACTIVE_STATE)
    fun activeHolding() = WealthPactStates.activeHolding(dataSource)

    @State(WealthPactStates.PLEDGED_STATE)
    fun pledgedHolding() = WealthPactStates.pledgedHolding(dataSource)

    @State(WealthPactStates.NO_HOLDING_STATE)
    fun noHolding() = WealthPactStates.noHolding(dataSource)

    @State(WealthPactStates.NEGATIVE_AUTH_STATE)
    fun noValidM2mIdentity() {
        // verifyPacts clears the authenticated test identity for this interaction.
    }
}
