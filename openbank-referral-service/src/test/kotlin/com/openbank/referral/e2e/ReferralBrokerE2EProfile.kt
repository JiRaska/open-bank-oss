// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the referral real-broker E2E suite: real Postgres + real Redpanda (the
 * shared [PostgresRedpandaTestResource]).
 *
 * Channel to close: `account-created-in` (ADR-0310 D1 — AccountCreated becomes a recorded
 * account.opened fact that qualifies attributed invites). The pre-existing
 * ReferralQualificationIT drives it through the InMemoryConnector, so the real deserializer,
 * the consumer group, offset commits and the DLQ wiring the connector applies on a nacked record
 * stayed unexercised. The bootstrap overrides from the Postgres/Redpanda resource replace the
 * Kafka devservices the other ITs implicitly boot.
 */
class ReferralBrokerE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(
            PostgresRedpandaTestResource::class.java,
            mapOf("db" to "openbank_referral_e2e"),
        ),
        TestResourceEntry(ReferralBrokerE2EResource::class.java),
    )
}

/**
 * The referral-broker config the suite needs, supplied through a test resource.
 *
 * - The %test block pins `account-created-in` to smallrye-in-memory, so the profile must
 *   override the connector back to smallrye-kafka (same pattern as loyalty's suite).
 * - `auto.offset.reset=earliest`: the suite publishes right after boot, before the consumer
 *   group's first assignment; the connector default (latest) silently skips those records
 *   (measured on loyalty's suite).
 */
class ReferralBrokerE2EResource : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> = mapOf(
        "mp.messaging.incoming.account-created-in.connector" to "smallrye-kafka",
        "mp.messaging.incoming.account-created-in.auto.offset.reset" to "earliest",
    )

    override fun stop() = Unit
}
