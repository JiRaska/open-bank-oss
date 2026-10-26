// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the engagement real-broker E2E suite: real Postgres + real Redpanda (the
 * shared [PostgresRedpandaTestResource]).
 *
 * All five of engagement's consumers — party-erasure, fraud-hold, lending-arrears, dispute and
 * campaign-banner-placements — had never eaten from a real broker: the @QuarkusTest ITs swap the
 * OUTGOING channel to the InMemoryConnector and never publish to the incoming topics at all, so
 * the real deserializer, the consumer group, offset commits and the
 * `failure-strategy: dead-letter-queue` wiring stayed unexercised. Unlike loyalty, no connector
 * override is needed here: engagement's `%test` block does not pin incoming channels to
 * smallrye-in-memory, and the bootstrap overrides from the test resource replace the Kafka
 * devservices the other ITs implicitly boot.
 */
class EngagementAdverseStateE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(
            PostgresRedpandaTestResource::class.java,
            mapOf("db" to "openbank_engagement_e2e"),
        ),
    )

    override fun getConfigOverrides() = mapOf(
        // The suite publishes right after boot, often before each consumer group's first
        // assignment — the connector default (latest) would silently skip those records
        // (measured on loyalty's suite). Prod sets these via the msg-override ConfigMap; the
        // suite sets them per channel.
        "mp.messaging.incoming.party-events-in.auto.offset.reset" to "earliest",
        "mp.messaging.incoming.fraud-hold-events-in.auto.offset.reset" to "earliest",
        "mp.messaging.incoming.lending-events-in.auto.offset.reset" to "earliest",
        "mp.messaging.incoming.dispute-events-in.auto.offset.reset" to "earliest",
        "mp.messaging.incoming.campaign-banner-placements-in.auto.offset.reset" to "earliest",
    )
}
