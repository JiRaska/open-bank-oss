// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application

import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.application.usecase.CampaignSendLogQuery
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The send log is the only place a suppression is observable (#2895): an enrolment reads the same
 * whether the party was contacted or deliberately skipped. These pin that `listSends` surfaces the
 * suppressed outcomes rather than filtering to successful deliveries — a "sends" list that quietly
 * showed only `SENT` would answer "who got it" while losing "why didn't they".
 */
class CampaignSendLogTest {

    private val campaignId = UUID.randomUUID()

    private val records = listOf(
        record(SendOutcome.SENT, 1),
        record(SendOutcome.SUPPRESSED_CONSENT, 1),
        record(SendOutcome.SUPPRESSED_CAP, 2),
        record(SendOutcome.SUPPRESSED_QUIET_HOURS, 2),
        record(SendOutcome.FAILED, 3),
    )

    private fun record(outcome: SendOutcome, step: Int) = SendRecord(
        id = UUID.randomUUID(),
        campaignId = campaignId,
        partyId = UUID.randomUUID(),
        stepOrder = step,
        outcome = outcome,
        occurredAt = Instant.parse("2026-07-31T18:00:00Z"),
    )

    private val query = CampaignSendLogQuery(
        object : SendLogRepository {
            override suspend fun record(send: SendRecord) = Unit
            override suspend fun countRecentForParty(partyId: UUID, sinceEpochSeconds: Long) = 0
            override suspend fun listByCampaign(campaignId: UUID) = records
        },
    )

    @Test
    fun `every recorded outcome is surfaced, suppressions included`(): Unit = runBlocking {
        val sends = query.listSends(campaignId)

        assertEquals(records.size, sends.size)
        assertEquals(
            setOf(
                SendOutcome.SENT,
                SendOutcome.SUPPRESSED_CONSENT,
                SendOutcome.SUPPRESSED_CAP,
                SendOutcome.SUPPRESSED_QUIET_HOURS,
                SendOutcome.FAILED,
            ),
            sends.map { it.outcome }.toSet(),
        )
    }

    @Test
    fun `the record keeps party and step so a suppression can be attributed`(): Unit = runBlocking {
        val suppressed = query.listSends(campaignId).first { it.outcome == SendOutcome.SUPPRESSED_CONSENT }

        assertEquals(campaignId, suppressed.campaignId)
        assertEquals(1, suppressed.stepOrder)
    }
}
