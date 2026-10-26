// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the loyalty real-broker E2E suite: real Postgres + real Redpanda (the shared
 * [PostgresRedpandaTestResource]).
 *
 * The pre-existing [com.openbank.loyalty.integration.ReferralQualifiedEarnIT] drives the same
 * consumer scenarios through the InMemoryConnector — same Vert.x context, but none of the
 * production wire: the real Kafka deserializer, the consumer group, offset commits, the
 * `failure-strategy: dead-letter-queue` wiring and the header contract stay unexercised. This
 * profile exists to close exactly that gap: the suite publishes to the real topic
 * `openbank.referral.qualified.v1` with a raw producer and reads the effects back over JDBC.
 */
class LoyaltyBrokerE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(
            PostgresRedpandaTestResource::class.java,
            mapOf("db" to "openbank_loyalty_e2e"),
        ),
    )

    override fun getConfigOverrides() = mapOf(
        // application.yaml's %test block pins the channel to smallrye-in-memory; put it back on
        // the real broker (same move as context-service's ContextE2EProfile). DLQ wiring, topic
        // and deserializer come from the main config.
        "mp.messaging.incoming.referral-qualified-in.connector" to "smallrye-kafka",
        // The suite publishes right after boot, often before the consumer group's first
        // assignment — the connector default (latest) would silently skip those records
        // (measured: every pre-assignment publish never arrived). Fleet consumer yaml sets
        // earliest explicitly; so does the suite.
        "mp.messaging.incoming.referral-qualified-in.auto.offset.reset" to "earliest",
    )
}
