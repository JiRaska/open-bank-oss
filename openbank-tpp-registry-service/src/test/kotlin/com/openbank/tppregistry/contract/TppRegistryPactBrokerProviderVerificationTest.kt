// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Broker-side provider verification for openbank-tpp-registry-service, the published-result counterpart to
 * [TppRegistryPactProviderVerificationTest].
 *
 * WHY BOTH EXIST. A `@PactFolder` test replays the COMMITTED pact from disk: it proves this
 * provider still honours the contract, on every PR, with no infrastructure. It never contacts
 * the broker, so it publishes nothing — and `can-i-deploy` reads published verification
 * results, not green test runs. Without this class the broker never learned that
 * openbank-tpp-registry-service verifies anything, so its consumers (openbank-psd2-service) stayed
 * permanently UNVERIFIED and could not be deployed (issue #3232).
 *
 * A second `@Provider("openbank-tpp-registry-service")` class is safe here for the reason
 * CLAUDE.md gives for ledger-service's identical pair: the collision it warns about is HTTP vs
 * MESSAGE target dispatch fighting over the same `@BeforeEach`, and both classes here use the
 * same target type, so verifying the same interactions from two pact sources is at worst
 * redundant, never colliding.
 *
 * Gated on `pactbroker.url`: skipped locally and on the PR lane, which have no broker
 * configured. It runs on the main push, where `_service-ci.yml` sets `PUBLISH_RESULTS=true`
 * — that is the run whose result `can-i-deploy` gates the deploy on. The `@PactFolder` class
 * keeps running unconditionally, so PR-time contract coverage is unchanged by this addition.
 */
@QuarkusTest
@QuarkusTestResource(TppRegistryPactBrokerProviderVerificationTest.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.tppregistry.it.PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_API", "ROLE_OPERATOR"])
@Provider("openbank-tpp-registry-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class TppRegistryPactBrokerProviderVerificationTest {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory("tpp-events-out")

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
        // context is null on the @IgnoreNoPactsToVerify dummy invocation — skip gracefully.
        context?.verifyInteraction()
    }

    @State("no registry entry exists for the pact unknown-TPP id")
    fun unknownTppIsNotRegistered() {
        // No setup: nothing registers CZ-CNB-PACT-UNREGISTERED. Declared so the state is an
        // explicit part of the contract rather than a name pact-jvm passes over silently — an
        // unhandled state is not an error, which is how #468's missing states stayed invisible.
    }

    @State("the TPP registry has an ACTIVE AISP with an unexpired QWAC")
    fun activeAispWithValidQwacIsSeeded() {
        // No setup: V1__init.sql already seeds CZ-CNB-TEST-AISP as ACTIVE/AISP with a NULL
        // qwac_expires_at, which TppRegistryService.checkAuthorization treats as never-expired.
        // Declared for the same reason as the state above — an explicit, visible no-op.
    }
}
