// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the campaign notification-outcome consumer real-broker E2E suite: real
 * Postgres + real Redpanda (the shared [PostgresRedpandaTestResource]).
 *
 * Channel to close: `notification-outcomes-in` (ADR-0239 D3 — notification-service reports the
 * terminal fate of every handoff on `openbank.notification.outcomes.v1`, correlated by the
 * send-log row id, so the console stops reporting an accepted handoff as delivered). Main already
 * pins `auto.offset.reset=earliest` on this channel (outcomes must never be replay-skipped), so
 * unlike the conversion/trigger suites no offset override is needed — the profile keeps the
 * worker/Temporal boot disable only.
 */
class CampaignOutcomesE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(
            PostgresRedpandaTestResource::class.java,
            mapOf("db" to "openbank_campaign_e2e"),
        ),
        TestResourceEntry(CampaignOutcomesBrokerResource::class.java),
    )
}

/**
 * The campaign-broker config the outcome suite needs: no enrolment worker and no Temporal
 * connection at boot (CampaignWorkerRegistrar's StartupEvent polls localhost:7233, nothing is
 * listening, and the StatusRuntimeException kills the whole boot). The outcome consumer touches
 * neither — it settles `delivery_status` — but the registrar runs at boot regardless.
 */
class CampaignOutcomesBrokerResource : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> = mapOf(
        "openbank.campaign.worker.enabled" to "false",
        "campaign.worker.enabled" to "false",
        "openbank.temporal.enabled" to "false",
    )

    override fun stop() = Unit
}
