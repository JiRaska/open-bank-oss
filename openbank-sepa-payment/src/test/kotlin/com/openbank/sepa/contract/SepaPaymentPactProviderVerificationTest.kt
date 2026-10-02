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
import au.com.dius.pact.provider.junitsupport.loader.PactFilter
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Git-pact provider verification for sepa-payment — the half that runs before a merge (#2327,
 * gated by `check-pact-provider-replay.py`). sepa-payment was a consumer of four contracts and a
 * provider of none: standing-order-service's `POST /api/v1/sepa-payments` (#8345) is the first
 * pact naming it as provider, and this class is what replays it.
 *
 * Boots the real service against the dedicated IT Postgres and Redis, with Kafka swapped to the
 * in-memory connector and the outbox dispatcher off — the same shape `SepaPaymentOutboxAtomicityIT`
 * uses to POST a payment. No Temporal frontend is present and none is needed: create persists the
 * payment and starts the workflow off the request path, so the route answers 201 without a worker.
 *
 * The one state is deliberately empty. A create needs nothing seeded. Each run starts fresh
 * containers, and within one JVM a second replay of the same `Idempotency-Key` (e.g. this class and
 * the broker twin sharing the Quarkus app) returns the stored 201 through the idempotency store.
 */
@QuarkusTest
@QuarkusTestResource(SepaPaymentPactProviderVerificationTest.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
@TestSecurity(user = "00000000-0000-0000-0000-000000008345", roles = ["ROLE_PAYMENTS"])
@Provider("openbank-sepa-payment")
@PactFolder("../pacts")
// The missing-identity interaction (401) is replayed by SepaPaymentNegativeAuthProviderVerificationTest,
// which boots without @TestSecurity; under this class's identity it would be authenticated and fail.
@PactFilter("^(?!" + NEGATIVE_AUTH_STATE + "\$).*\$")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class SepaPaymentPactProviderVerificationTest {

    class NoDispatchInMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("events-out").toMutableMap().also {
                it["quarkus.kafka.devservices.enabled"] = "false"
                it["openbank.outbox.dispatch-enabled"] = "false"
            }

        override fun stop() = InMemoryConnector.clear()
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("sepa-payment can accept a new credit transfer")
    fun stateCanAcceptNewTransfer() {
        // Deliberately empty — see the class KDoc.
    }
}
