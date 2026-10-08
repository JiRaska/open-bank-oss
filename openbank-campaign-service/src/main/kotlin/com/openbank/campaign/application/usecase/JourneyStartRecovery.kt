// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.ConsentCheckPort
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.port.out.SegmentPage
import com.openbank.campaign.domain.model.CampaignProductKind.Companion.CREDIT_OFFERS_SCOPE
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/** One budgeted recovery per interval, shared with bulk admission across pods. */
@ApplicationScoped
class JourneyStartRecovery(
    private val intents: JourneyStartIntentStore,
    private val campaigns: CampaignRepository,
    private val enrolments: EnrolmentRepository,
    private val journeys: JourneySignaller,
    private val consent: ConsentCheckPort,
    @ConfigProperty(name = "openbank.campaign.bulk-admission-per-minute", defaultValue = "0")
    private val capacity: Int,
) {
    private val log = Logger.getLogger(JourneyStartRecovery::class.java)

    /** Returning false means there was no eligible intent or no available capacity. */
    @Suppress("TooGenericExceptionCaught") // Every persistence or Temporal fault retains the intent for a later claim.
    suspend fun tick(): Boolean {
        if (capacity !in 1..SegmentPage.MAX_PAGE_SIZE) return false
        val owner = Ids.randomId()
        val intent = intents.claimRecovery(owner) ?: return false
        try {
            recover(intent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.errorf("journey start recovery failed campaign=%s cause=%s", intent.campaignId, e.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) { intents.releaseRecovery(owner, intent) }
        }
        return true
    }

    private suspend fun recover(intent: JourneyStartIntent) {
        if (enrolments.findByCampaignAndParty(intent.campaignId, intent.partyId) != null) {
            intents.complete(intent.campaignId, intent.partyId)
            return
        }
        val campaign = campaigns.findById(intent.campaignId)
        if (campaign == null || campaign.state == CampaignState.CLOSED) {
            journeys.signalCampaignClosed(intent.campaignId, intent.partyId)
            intents.complete(intent.campaignId, intent.partyId)
            return
        }
        if (campaign.state != CampaignState.ACTIVE) return
        if (campaign.productKind.isCredit && !consent.hasActiveConsent(intent.partyId, CREDIT_OFFERS_SCOPE)) {
            journeys.signalConsentRevoked(intent.campaignId, intent.partyId)
            intents.complete(intent.campaignId, intent.partyId)
            return
        }
        journeys.startJourney(intent.campaignId, intent.partyId, intent.journeyType)
        enrolments.save(intent.enrolment())
        intents.complete(intent.campaignId, intent.partyId)
    }
}
