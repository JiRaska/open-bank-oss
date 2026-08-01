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

            // Filters and pages the way SQL does, so the test exercises the real contract rather
            // than a fake that always answers with everything.
            override suspend fun listByCampaign(campaignId: UUID, outcome: SendOutcome?, page: Int, size: Int) =
                records.filter { outcome == null || it.outcome == outcome }.drop(page * size).take(size)

            override suspend fun countByCampaign(campaignId: UUID, outcome: SendOutcome?) =
                records.count { outcome == null || it.outcome == outcome }.toLong()
        },
    )

    @Test
    fun `every recorded outcome is surfaced, suppressions included`(): Unit = runBlocking {
        val sends = query.listSends(campaignId, outcome = null, page = 0, size = 50).items

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
        val suppressed = query.listSends(campaignId, outcome = null, page = 0, size = 50)
            .items.first { it.outcome == SendOutcome.SUPPRESSED_CONSENT }

        assertEquals(campaignId, suppressed.campaignId)
        assertEquals(1, suppressed.stepOrder)
    }

    /**
     * `total` is what lets the console tell "this is everything" from "this is the first page of
     * many". Those render identically and mean opposite things to whoever is deciding whether a
     * campaign reached its audience, so the count must survive paging.
     */
    @Test
    fun `a page carries the total it was cut from`(): Unit = runBlocking {
        val page = query.listSends(campaignId, outcome = null, page = 0, size = 2)

        assertEquals(2, page.items.size)
        assertEquals(records.size.toLong(), page.total)
    }

    @Test
    fun `the total is of the filtered set, not of everything`(): Unit = runBlocking {
        // A total that ignored the filter would show a page of 1 out of "5 sends", which reads as
        // four rows the console failed to render.
        val page = query.listSends(campaignId, SendOutcome.SUPPRESSED_CONSENT, page = 0, size = 50)

        assertEquals(page.items.size.toLong(), page.total)
        assertEquals(setOf(SendOutcome.SUPPRESSED_CONSENT), page.items.map { it.outcome }.toSet())
    }

    /**
     * A caller-supplied page size is a caller-supplied amount of work. Clamping rather than
     * rejecting keeps an over-large request useful, and the ceiling is the whole reason paging
     * removes the unbounded read instead of relocating it.
     */
    @Test
    fun `an over-large page size is clamped, not honoured`(): Unit = runBlocking {
        val page = query.listSends(campaignId, outcome = null, page = 0, size = 1_000_000)

        assertEquals(CampaignSendLogQuery.MAX_PAGE_SIZE, page.size)
    }

    @Test
    fun `a negative page or a zero size cannot produce an empty page by accident`(): Unit = runBlocking {
        val page = query.listSends(campaignId, outcome = null, page = -3, size = 0)

        assertEquals(0, page.page)
        assertEquals(1, page.size)
        assertEquals(1, page.items.size)
    }
}
