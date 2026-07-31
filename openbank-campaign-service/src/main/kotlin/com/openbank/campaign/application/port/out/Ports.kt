// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.port.out

import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SendRecord
import java.util.UUID

interface CampaignRepository {
    suspend fun findById(id: UUID): Campaign?
    suspend fun list(): List<Campaign>
    suspend fun save(campaign: Campaign): Campaign
}

interface EnrolmentRepository {
    suspend fun findByCampaignAndParty(campaignId: UUID, partyId: UUID): Enrolment?
    suspend fun listByCampaign(campaignId: UUID): List<Enrolment>
    suspend fun listByParty(partyId: UUID): List<Enrolment>
    suspend fun save(enrolment: Enrolment): Enrolment
}

interface SendLogRepository {
    suspend fun record(send: SendRecord)
    suspend fun countRecentForParty(partyId: UUID, sinceEpochSeconds: Long): Int
}

/** ADR-0201 D1: segments are versioned artifacts loaded as code/data, never UI-typed SQL. */
interface SegmentRegistry {
    suspend fun load(name: String, version: Int): Segment?
    suspend fun save(segment: Segment): Segment
    suspend fun list(): List<Segment>
}

/** ADR-0210: evaluates a segment against the silver layer and returns matching party ids. */
interface SegmentEvaluationPort {
    suspend fun evaluate(segment: Segment): List<UUID>
}

/** ADR-0198/0195: live per-call consent check — a cached consent survives its own revocation. */
interface ConsentCheckPort {
    suspend fun hasActiveConsent(partyId: UUID, scope: String): Boolean
}

/** ADR-0200 D3: delivery goes through notification-service, never direct. */
interface NotificationSendPort {
    suspend fun requestSend(partyId: UUID, template: String, recipient: String, variables: Map<String, String>)
}

/** ADR-0200 D2 push: signals a live journey that consent was revoked for its party. */
interface JourneySignaller {
    fun signalConsentRevoked(campaignId: UUID, partyId: UUID)
    fun startJourney(campaignId: UUID, partyId: UUID)
}
