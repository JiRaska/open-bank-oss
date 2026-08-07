// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.usecase.CampaignService
import com.openbank.campaign.domain.model.CampaignState
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger
import java.time.Clock
import java.util.UUID

/**
 * The scheduled enrolment pass. Reuses [CampaignService.enrol] rather than reimplementing it, so a
 * scheduled run and a manual `POST /{id}/enrol` cannot drift apart in what they consider enrolable.
 */
@ApplicationScoped
open class CampaignEnrolmentSweepActivitiesImpl(
    private val campaigns: CampaignRepository,
    private val service: CampaignService,
    private val clock: Clock,
) : CampaignEnrolmentSweepActivities {

    private val log = Logger.getLogger(CampaignEnrolmentSweepActivitiesImpl::class.java)

    override fun enrolDueParties(campaignId: UUID): SweepOutcome = runBlockingOnWorker {
        val campaign = campaigns.findById(campaignId)
        if (campaign == null) {
            // A schedule outliving its campaign is possible: the row is deleted, the Temporal
            // schedule is a separate system. Report it as a skip rather than throwing, so the run
            // does not retry three times against a campaign that will never come back.
            log.warnf("Scheduled sweep for unknown campaign %s — skipping", campaignId)
            return@runBlockingOnWorker SweepOutcome(0, 0, SweepSkip.NOT_ACTIVE)
        }
        // Both guards are ordinary outcomes, not errors. A pause takes effect on the Temporal
        // schedule too, but a run already in flight when it landed still arrives here.
        if (campaign.state != CampaignState.ACTIVE) {
            log.debugf("Scheduled sweep for campaign %s skipped — state is %s", campaignId, campaign.state)
            return@runBlockingOnWorker SweepOutcome(0, 0, SweepSkip.NOT_ACTIVE)
        }
        if (campaign.schedule?.expiredAt(clock.instant()) == true) {
            log.infof(
                "Scheduled sweep for campaign %s skipped — schedule ended at %s",
                campaignId,
                campaign.schedule.endAt,
            )
            return@runBlockingOnWorker SweepOutcome(0, 0, SweepSkip.SCHEDULE_EXPIRED)
        }
        val outcome = service.enrol(campaignId)
        log.infof(
            "Scheduled sweep for campaign %s enrolled=%d failed=%d",
            campaignId,
            outcome.enrolled,
            outcome.failed,
        )
        SweepOutcome(outcome.enrolled, outcome.failed, null)
    }

    /**
     * Same Vert.x bridge, and for the same reason, as `CampaignJourneyActivitiesImpl` — a Temporal
     * activity thread carries no Vert.x context, so a bare `runBlocking` around reactive Panache
     * throws `HR000068` and the sweep silently enrols nobody. `internal open` so a test can
     * substitute a plain `runBlocking`.
     */
    internal open fun <T> runBlockingOnWorker(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }
}
