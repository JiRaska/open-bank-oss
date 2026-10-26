// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the campaign real-broker E2E suite: real Postgres + real Redpanda (the
 * shared [PostgresRedpandaTestResource]).
 *
 * First channel to close: `incentive-events-in`. The consumer (CampaignIncentiveOutcomeConsumer)
 * had only unit-level coverage of its parse decisions — the projection's real wire (deserializer,
 * consumer group, offset commits, the `failure-strategy: dead-letter-queue` wiring the connector
 * actually applies on a nacked record) was never exercised. The bootstrap overrides from the
 * Postgres/Redpanda resource replace the Kafka devservices other ITs implicitly boot. The %test
 * block already sets `incentive-outcome-projection.ready: true`, so the projection gate is open
 * here without an override.
 */
class CampaignIncentiveOutcomeE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(
            PostgresRedpandaTestResource::class.java,
            mapOf("db" to "openbank_campaign_e2e"),
        ),
        TestResourceEntry(CampaignBrokerE2EResource::class.java),
    )
}

/**
 * The campaign-broker config the suite needs, supplied through a test resource — the same
 * mechanism CampaignRestContractIT.NoBrokerNoWorkerResource relies on.
 *
 * - No enrolment worker and no Temporal connection at boot: CampaignWorkerRegistrar observes
 *   StartupEvent and, when enabled, polls `openbank.temporal.server-url` (localhost:7233, nothing
 *   listening) — the StatusRuntimeException kills the whole boot. The incentive projection path
 *   is plain JDBC behind the consumer and needs neither.
 * - `auto.offset.reset=earliest`: the suite publishes right after boot, before the consumer
 *   group's first assignment; the connector default (latest) silently skips those records
 *   (measured on loyalty's suite). Prod sets this via the msg-override ConfigMap.
 */
class CampaignBrokerE2EResource : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> = mapOf(
        "openbank.campaign.worker.enabled" to "false",
        "campaign.worker.enabled" to "false",
        "openbank.temporal.enabled" to "false",
        "mp.messaging.incoming.incentive-events-in.auto.offset.reset" to "earliest",
    )

    override fun stop() = Unit
}
