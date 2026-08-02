// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.domain.model.SendOutcome
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Reach and delivery for EVERY campaign, in three queries total (issue #3296).
 *
 * WHY THIS EXISTS
 * `GET /api/v1/campaigns` returns campaign records only, so the console could show what is running
 * and what waits for an approver, and nothing about whether any of it reached anyone. The honest
 * stop-gap was to say so on the screen. This is the endpoint that lets it stop saying so.
 *
 * WHY NOT LOOP THE EXISTING PER-CAMPAIGN SUMMARY
 * `CampaignSendLogQuery.summary(id)` runs one count per `SendOutcome` value. Calling it per campaign
 * would be campaigns × outcomes round trips against a service that is KEDA scale-to-zero — the
 * exact N+1 the console refused to make. Both tallies here are single grouped queries.
 *
 * SUPPRESSIONS ARE NOT DROPPED IN THE AGGREGATE. "2 sent" and "2 sent, 40 suppressed for consent"
 * are different campaigns, and only the second explains why reach was low. Every outcome that
 * occurred is carried through with its own count; a marketer reading `sent` alone would conclude
 * the audience was small when it was in fact refused.
 */
@ApplicationScoped
class CampaignSummaryQuery(
    private val campaigns: CampaignRepository,
    private val enrolments: EnrolmentRepository,
    private val sendLog: SendLogRepository,
) {
    suspend fun summaries(): List<CampaignSummary> {
        val all = campaigns.list()
        val enrolledByCampaign = enrolments.countAllByCampaign().associate { it.campaignId to it.count }
        val outcomesByCampaign = sendLog.countAllByCampaignAndOutcome().groupBy { it.campaignId }

        return all.map { c ->
            val cells = outcomesByCampaign[c.id].orEmpty()
            CampaignSummary(
                campaignId = c.id,
                state = c.state.name,
                // A campaign with no enrolments yet is 0, never null: "not enrolled" and "unknown"
                // read the same in a table and mean different things to whoever is deciding
                // whether the campaign is working.
                enrolled = enrolledByCampaign[c.id] ?: 0,
                outcomes = cells
                    .filter { it.count > 0 }
                    .sortedBy { it.outcome.name }
                    .map { OutcomeCount(it.outcome.name, it.count) },
                sent = cells.firstOrNull { it.outcome == SendOutcome.SENT }?.count ?: 0,
                suppressed = cells
                    .filter { it.outcome in CampaignSendLogQuery.SUPPRESSION_REASONS }
                    .sumOf { it.count },
                failed = cells.firstOrNull { it.outcome == SendOutcome.FAILED }?.count ?: 0,
            )
        }
    }
}

/** One campaign's reach, as a console can render it without a second request. */
data class CampaignSummary(
    val campaignId: UUID,
    val state: String,
    val enrolled: Long,
    /** Every outcome that actually occurred, with its count — suppressions included. */
    val outcomes: List<OutcomeCount>,
    /** Convenience rollups over [outcomes]; the detail is kept so nothing is hidden by them. */
    val sent: Long,
    val suppressed: Long,
    val failed: Long,
)

data class OutcomeCount(val outcome: String, val count: Long)
