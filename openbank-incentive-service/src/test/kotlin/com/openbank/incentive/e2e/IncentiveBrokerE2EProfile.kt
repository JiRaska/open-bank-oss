// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.incentive.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the incentive real-broker E2E suite: real Postgres + real Redpanda (the
 * shared [PostgresRedpandaTestResource]).
 *
 * Wire to close: the reservation lifecycle is written to `incentive_outbox` inside the request
 * transaction, and the outbox dispatcher relays it to `openbank.incentive.events`. Every
 * pre-existing test either switches `incentive-events-out` to the InMemoryConnector (pact
 * verifications, the trace contract) or runs with `openbank.outbox.dispatch-enabled=false`
 * (the %test default) — so the dispatcher had never relayed a row over a real broker, and no
 * assertion ever read a Kafka record. The bootstrap overrides from the Postgres/Redpanda
 * resource replace the Kafka devservices the other ITs implicitly boot.
 */
class IncentiveBrokerE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(
            PostgresRedpandaTestResource::class.java,
            mapOf("db" to "openbank_incentive_e2e"),
        ),
        TestResourceEntry(IncentiveBrokerE2EResource::class.java),
    )
}

/**
 * The incentive-broker config the suite needs, supplied through a test resource.
 *
 * The %test block switches the outbox dispatcher off; here the dispatcher must run against the
 * real broker, so the suite re-enables it. `poll-interval: 5s` (the main default) bounds how
 * fast a row can reach the topic — assertions poll rather than assume immediacy.
 */
class IncentiveBrokerE2EResource : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> = mapOf(
        "openbank.outbox.dispatch-enabled" to "true",
        // %test leaves the pepper empty; the application refuses to boot without one
        // (IncentiveApplication throws IllegalStateException at startup).
        "openbank.incentive.code-pepper" to "broker-e2e-pepper-with-32-characters-minimum",
    )

    override fun stop() = Unit
}
