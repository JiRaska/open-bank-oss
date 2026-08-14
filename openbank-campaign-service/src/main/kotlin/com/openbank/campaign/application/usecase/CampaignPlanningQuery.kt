// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.ScheduleCatalog
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/**
 * A portfolio-level planning projection for marketers.
 *
 * It deliberately returns the next *declared schedule window*, not a promised send and not an
 * audience-overlap estimate. Temporal owns actual execution, and evaluating cross-campaign overlap
 * without the same live segment snapshot would manufacture a certainty the bank does not have.
 */
@ApplicationScoped
class CampaignPlanningQuery(private val campaigns: CampaignRepository) {

    suspend fun items(now: Instant = Instant.now()): List<CampaignPlan> = campaigns.list()
        .flatMap { campaign ->
            val schedule = campaign.schedule
            buildList {
                if (schedule != null) {
                    val cadence = requireNotNull(ScheduleCatalog[schedule.cadence])
                    val active = campaign.state == CampaignState.ACTIVE
                    val nextWindow = if (active) ScheduleCatalog.nextWindowAfter(schedule.cadence, now) else null
                    add(
                        CampaignPlan(
                            campaignId = campaign.id,
                            name = campaign.name,
                            state = campaign.state.name,
                            entry = CampaignEntry.SCHEDULED,
                            cadence = schedule.cadence,
                            cadenceHumanForm = cadence.humanForm,
                            zone = ScheduleCatalog.ZONE,
                            nextScheduledWindowAt = nextWindow?.takeIf { candidate ->
                                schedule.endAt?.let { candidate < it } ?: true
                            },
                            endAt = schedule.endAt,
                        ),
                    )
                }
                campaign.trigger?.let { trigger ->
                    add(
                        CampaignPlan(
                            campaignId = campaign.id,
                            name = campaign.name,
                            state = campaign.state.name,
                            entry = CampaignEntry.EVENT,
                            trigger = trigger,
                        ),
                    )
                }
                if (schedule == null && campaign.trigger == null) {
                    add(
                        CampaignPlan(
                            campaignId = campaign.id,
                            name = campaign.name,
                            state = campaign.state.name,
                            entry = CampaignEntry.MANUAL,
                        ),
                    )
                }
            }
        }
        .sortedWith(compareBy<CampaignPlan> { it.nextScheduledWindowAt == null }.thenBy { it.nextScheduledWindowAt })
}

data class CampaignPlan(
    val campaignId: UUID,
    val name: String,
    val state: String,
    val entry: CampaignEntry,
    val cadence: String? = null,
    val cadenceHumanForm: String? = null,
    val zone: String? = null,
    val nextScheduledWindowAt: Instant? = null,
    val endAt: Instant? = null,
    val trigger: String? = null,
)

enum class CampaignEntry { SCHEDULED, EVENT, MANUAL }
