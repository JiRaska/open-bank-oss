// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.e2e

import com.openbank.notification.it.PostgresTestResource
import com.openbank.notification.it.RedisTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry
import io.smallrye.reactive.messaging.memory.InMemoryConnector

/**
 * Shared profile for the notification E2E suite: real Postgres (the shared
 * [PostgresTestResource]) plus Valkey (the shared [RedisTestResource] — the Redis client is a
 * readiness dependency even on routes that never touch it, so a boot without it answers 503), and
 * every Kafka channel switched to the in-memory connector (the service @Incoming-consumes five
 * upstream topics and @Outgoing-publishes `notification-events-out`; a boot with the real Kafka
 * connector and no broker leaves the readiness messaging check DOWN). The suite proves the
 * preference lifecycle and the ADR-0047 break-glass dispatch control over the real HTTP surface
 * with durable facts in a real database.
 *
 * Every E2E class must use exactly this profile class so Quarkus reuses one test JVM (and one
 * container set) across the whole suite.
 */
class NotificationE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(PostgresTestResource::class.java),
        TestResourceEntry(RedisTestResource::class.java),
    )

    override fun getConfigOverrides() = mapOf(
        // PDP fail-closed: without this every @Authorize route answers 503
        // POLICY_DECISION_POINT_UNAVAILABLE when no OPA sidecar runs.
        "authz.enforce" to "false",
    ) + InMemoryConnector.switchIncomingChannelsToInMemory(
        "party-events-in",
        "kyc-events-in",
        "consent-events-in",
        "approval-events-in",
        "delegation-events-in",
        "notification-events-in",
    ) + InMemoryConnector.switchOutgoingChannelsToInMemory("notification-events-out")

    override fun runMainMethod(): Boolean = false
}
