// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Read-only view of what a campaign actually did, for the operator console (#2895).
 *
 * Separate from [CampaignService] on purpose: that class owns the campaign lifecycle — create,
 * submit, activate, enrol — and this one only answers questions. Keeping the query here also keeps
 * both classes under the detekt function-count threshold without raising it, which is the honest
 * reading of that rule rather than a workaround.
 */
@ApplicationScoped
class CampaignSendLogQuery(private val sendLog: SendLogRepository) {

    /**
     * Every send attempt for this campaign, newest first — **including suppressed ones**.
     *
     * The outcome is the whole point: from the enrolment side a party that was contacted and one
     * that was deliberately skipped (consent withdrawn, frequency cap, quiet hours — ADR-0200 D6)
     * look identical. Filtering this to successful deliveries would answer "who got it" while
     * losing "why didn't they", which is the question operators actually ask.
     */
    suspend fun listSends(campaignId: UUID, outcome: SendOutcome?, page: Int, size: Int): SendPage {
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val safePage = page.coerceAtLeast(0)
        return SendPage(
            items = sendLog.listByCampaign(campaignId, outcome, safePage, safeSize),
            total = sendLog.countByCampaign(campaignId, outcome),
            page = safePage,
            size = safeSize,
        )
    }

    /**
     * How many sends per outcome, across the whole campaign.
     *
     * Counted in SQL rather than derived from a page: a "suppressed" headline computed from the
     * rows currently on screen says "2 suppressed" while a campaign is suppressing thousands, and
     * that number is exactly the one an operator acts on. Paging a list is safe; paging a total is
     * not.
     */
    /**
     * The journey as a marketer reads it: for each step, how many people it reached and where the
     * rest went.
     *
     * Every number is a SQL aggregate. A funnel is read as the whole picture by definition, so one
     * folded from a page of records would understate every campaign larger than that page while
     * looking exactly as authoritative.
     */
    suspend fun funnel(campaignId: UUID): List<StepFunnel> {
        val cells = sendLog.countByStepAndOutcome(campaignId)
        return cells.groupBy { it.stepOrder }.toSortedMap().map { (step, rows) ->
            val byOutcome = rows.associate { it.outcome to it.count }
            StepFunnel(
                stepOrder = step,
                reached = rows.sumOf { it.count },
                delivered = byOutcome[SendOutcome.SENT] ?: 0,
                failed = byOutcome[SendOutcome.FAILED] ?: 0,
                suppressed = SUPPRESSION_REASONS.mapNotNull { r ->
                    byOutcome[r]?.takeIf { it > 0 }?.let { SuppressionCount(r.name, it) }
                },
            )
        }
    }

    suspend fun summary(campaignId: UUID): Map<SendOutcome, Long> =
        SendOutcome.entries.associateWith { sendLog.countByCampaign(campaignId, it) }

    companion object {
        /** Outcomes that mean "deliberately not sent", in the order ADR-0200 D6 evaluates them. */
        val SUPPRESSION_REASONS = listOf(
            SendOutcome.SUPPRESSED_CAP,
            SendOutcome.SUPPRESSED_QUIET_HOURS,
            SendOutcome.SUPPRESSED_CONSENT,
        )

        /**
         * A caller-supplied page size is a caller-supplied amount of work. Clamping rather than
         * rejecting keeps an over-large request useful instead of turning it into an error the
         * console has to handle, and the ceiling is what stops `?size=1000000` from being the
         * unbounded read this paging exists to remove.
         */
        const val MAX_PAGE_SIZE = 200
    }
}

/**
 * A page of sends plus the total it was cut from.
 *
 * `total` is not decoration: without it the console cannot tell "this is everything" from "this is
 * the first 50 of many", and those render identically while meaning opposite things to whoever is
 * deciding whether a campaign reached its audience.
 */
/** How many people one journey step reached, and where the rest of them went. */
data class StepFunnel(
    val stepOrder: Int,
    val reached: Long,
    val delivered: Long,
    val failed: Long,
    val suppressed: List<SuppressionCount>,
)

/** One reason a step did not deliver, with its count. Kept as a list so the order is the rule order. */
data class SuppressionCount(val reason: String, val count: Long)

data class SendPage(val items: List<SendRecord>, val total: Long, val page: Int, val size: Int)
