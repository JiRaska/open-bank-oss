// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the campaign engagement/consent consumer real-broker E2E suite: real Postgres
 * + real Redpanda (the shared [PostgresRedpandaTestResource]).
 *
 * Channels to close:
 * - `engagement-events-in` — the campaign-local, no-PII projection of attributable engagement
 *   events (#4480). Main pins `auto.offset.reset: latest` here, so the suite overrides it to
 *   earliest (the publish lands before the consumer group's first assignment).
 * - `consent-events-in` — ADR-0200 D2 push: a marketing-scope ConsentRevoked/ConsentExpired
 *   signals every live journey of the party mid-flight rather than at its next step. Main already
 *   pins `earliest` on this channel; the consumer's journey side-effect is asserted through the
 *   recording [com.openbank.campaign.application.port.out.JourneySignaller] alternative (the real
 *   signaller would dial the Temporal frontend synchronously and block the consumer's event loop).
 */
class CampaignEngagementConsentE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(
            PostgresRedpandaTestResource::class.java,
            mapOf("db" to "openbank_campaign_e2e"),
        ),
        TestResourceEntry(CampaignEngagementConsentBrokerResource::class.java),
    )

    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(RecordingJourneySignaller::class.java)
}

/**
 * The campaign-broker config the suite needs: no enrolment worker and no Temporal connection at
 * boot (CampaignWorkerRegistrar's StartupEvent polls localhost:7233, nothing is listening, and
 * the StatusRuntimeException kills the whole boot), plus the earliest override on
 * `engagement-events-in` (main's `latest` silently drops everything published before the first
 * group assignment — the same measured relaxation the conversion/trigger suites carry).
 */
class CampaignEngagementConsentBrokerResource : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> = mapOf(
        "openbank.campaign.worker.enabled" to "false",
        "campaign.worker.enabled" to "false",
        "openbank.temporal.enabled" to "false",
        "mp.messaging.incoming.engagement-events-in.auto.offset.reset" to "earliest",
    )

    override fun stop() = Unit
}
