// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.e2e

import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.port.out.JourneyType
import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared profile for the campaign conversion-consumer real-broker E2E suite: real Postgres + real
 * Redpanda (the shared [PostgresRedpandaTestResource]).
 *
 * Channels to close: `account-conversions-in` and `card-conversions-in` (ADR-0245 — a product
 * event the campaign existed to cause writes one CONVERTED outcome against the party's
 * enrolment). Unlike loyalty/referral, campaign's %test block never pins incoming channels to
 * in-memory — every channel already runs smallrye-kafka in tests, booting a throwaway Kafka via
 * devservices — but nothing ever published to the conversion topics and ConversionConsumer had
 * only mocked unit coverage, so the real header/payload event-type matching and the real
 * attribution-window write were never on the wire together.
 */
class CampaignConversionsE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(
            PostgresRedpandaTestResource::class.java,
            mapOf("db" to "openbank_campaign_e2e"),
        ),
        TestResourceEntry(CampaignConversionsBrokerResource::class.java),
    )

    /**
     * The real [com.openbank.campaign.infrastructure.temporal.TemporalJourneySignaller] dials the
     * Temporal frontend synchronously on first use, and with no server listening the SDK retries
     * UNAVAILABLE for seconds — blocking the Kafka consumer's event loop mid-conversion. The suite
     * asserts the signal side-effect (TREATMENT cohort ⇒ goal-reached signalled), not the RPC, so
     * the recording no-op replaces the signaller for this profile only.
     */
    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(RecordingJourneySignaller::class.java)
}

/**
 * Captures journey signals in place of the Temporal RPC, so the suite can assert the conversion
 * consumer's signal side-effect without a Temporal server. Enabled only through
 * [CampaignConversionsE2EProfile.getEnabledAlternatives].
 */
@Alternative
@ApplicationScoped
class RecordingJourneySignaller : JourneySignaller {
    data class Signal(val control: String, val campaignId: UUID, val partyId: UUID)

    private val recorded = CopyOnWriteArrayList<Signal>()

    fun signals(control: String, campaignId: UUID, partyId: UUID): List<Signal> =
        recorded.filter { it.control == control && it.campaignId == campaignId && it.partyId == partyId }

    override fun signalConsentRevoked(campaignId: UUID, partyId: UUID) {
        recorded.add(Signal("consent-revoked", campaignId, partyId))
    }

    override fun signalCampaignPaused(campaignId: UUID, partyId: UUID) {
        recorded.add(Signal("campaign-paused", campaignId, partyId))
    }

    override fun signalCampaignResumed(campaignId: UUID, partyId: UUID) {
        recorded.add(Signal("campaign-resumed", campaignId, partyId))
    }

    override fun signalCampaignClosed(campaignId: UUID, partyId: UUID) {
        recorded.add(Signal("campaign-closed", campaignId, partyId))
    }

    override fun signalGoalReached(campaignId: UUID, partyId: UUID) {
        recorded.add(Signal("goal-reached", campaignId, partyId))
    }

    override fun startJourney(campaignId: UUID, partyId: UUID, type: JourneyType) {
        recorded.add(Signal("start-${type.name.lowercase()}", campaignId, partyId))
    }
}

/**
 * The campaign-broker config the conversion suite needs, supplied through a test resource.
 *
 * - No enrolment worker and no Temporal connection at boot: CampaignWorkerRegistrar observes
 *   StartupEvent and, when enabled, polls `openbank.temporal.server-url` (localhost:7233, nothing
 *   listening) — the StatusRuntimeException kills the whole boot.
 * - `auto.offset.reset=earliest` on all four consumer channels: the suite publishes right after
 *   boot, before the consumer groups' first assignment, and main deliberately sets `latest` on
 *   the conversion channels (replaying history would attribute conversions to campaigns that had
 *   not sent anything — the override is a test-only relaxation, measured on this suite).
 */
class CampaignConversionsBrokerResource : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> = mapOf(
        "openbank.campaign.worker.enabled" to "false",
        "campaign.worker.enabled" to "false",
        "openbank.temporal.enabled" to "false",
        "mp.messaging.incoming.account-conversions-in.auto.offset.reset" to "earliest",
        "mp.messaging.incoming.card-conversions-in.auto.offset.reset" to "earliest",
    )

    override fun stop() = Unit
}
