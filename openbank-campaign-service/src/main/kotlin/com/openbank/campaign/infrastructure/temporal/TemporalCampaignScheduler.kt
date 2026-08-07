// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.temporal

import com.openbank.campaign.application.port.out.CampaignScheduler
import com.openbank.campaign.application.workflow.CampaignEnrolmentSweepWorkflow
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.schedules.Schedule
import io.temporal.client.schedules.ScheduleActionStartWorkflow
import io.temporal.client.schedules.ScheduleClient
import io.temporal.client.schedules.ScheduleException
import io.temporal.client.schedules.ScheduleHandle
import io.temporal.client.schedules.ScheduleOptions
import io.temporal.api.enums.v1.ScheduleOverlapPolicy
import io.temporal.client.schedules.SchedulePolicy
import io.temporal.client.schedules.ScheduleSpec
import io.temporal.client.schedules.ScheduleState
import io.temporal.client.schedules.ScheduleUpdate
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * Recurring enrolment, held by Temporal Schedules rather than a row and a `@Scheduled` sweep.
 *
 * **Why Temporal and not `@Scheduled`.** A quartz-style annotation in this fleet has a documented
 * failure mode — a non-suspend `@Scheduled` method carries no Vert.x context, so `runBlocking`
 * around reactive Panache throws `HR000068` and the job silently does nothing; five schedulers,
 * three of them money-path, had never once run (#2148/#2187). Beyond that, an in-process scheduler
 * fires once per replica, so scaling to two pods doubles every campaign's sends. A Temporal schedule
 * is durable, cluster-wide singleton by construction, survives a restart mid-window, and is
 * inspectable — `tctl schedule describe` answers "when did this last fire" without a log dive.
 *
 * **Overlap policy is SKIP, deliberately.** An enrolment sweep walks a whole segment; if last
 * night's run is somehow still going when tonight's is due, queueing them means two concurrent
 * passes over the same audience. The enrolment path is idempotent so that would be survivable rather
 * than harmful, but it is still two segment evaluations for one campaign's worth of value. Skipping
 * loses a run and says so in the schedule's history, which is the honest trade.
 */
@ApplicationScoped
class TemporalCampaignScheduler(
    private val scheduleClient: ScheduleClient,
    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-campaign")
    private val taskQueue: String,
) : CampaignScheduler {

    private val log = Logger.getLogger(TemporalCampaignScheduler::class.java)

    override fun upsert(campaignId: UUID, cron: String, zone: String, endAt: Instant?) {
        val id = scheduleId(campaignId)
        val spec = ScheduleSpec.newBuilder()
            .setCronExpressions(listOf(cron))
            // Without this the expression is evaluated in UTC and a 09:00 campaign reaches Czech
            // customers at 11:00 in summer — see ScheduleCatalog.ZONE.
            .setTimeZoneName(zone)
            .apply { if (endAt != null) setEndAt(endAt) }
            .build()
        val schedule = Schedule.newBuilder()
            .setAction(
                ScheduleActionStartWorkflow.newBuilder()
                    .setWorkflowType(CampaignEnrolmentSweepWorkflow::class.java)
                    .setArguments(campaignId)
                    .setOptions(WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build())
                    .build(),
            )
            .setPolicy(
                SchedulePolicy.newBuilder()
                    .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                    .build(),
            )
            .setSpec(spec)
            .build()

        try {
            scheduleClient.createSchedule(id, schedule, ScheduleOptions.newBuilder().build())
            log.infof("Created schedule %s (cron '%s' %s, endAt=%s)", id, cron, zone, endAt)
        } catch (e: ScheduleException) {
            // Already exists: this is an update, not a failure. createSchedule has no upsert mode,
            // and checking-then-creating would race a second operator doing the same thing.
            log.debugf(e, "Schedule %s exists — updating in place", id)
            handle(id).update { ScheduleUpdate(schedule) }
            log.infof("Updated schedule %s (cron '%s' %s, endAt=%s)", id, cron, zone, endAt)
        }
    }

    override fun pause(campaignId: UUID) = onExistingSchedule(campaignId, "pause") { it.pause() }

    override fun unpause(campaignId: UUID) = onExistingSchedule(campaignId, "unpause") { it.unpause() }

    override fun delete(campaignId: UUID) = onExistingSchedule(campaignId, "delete") { it.delete() }

    /**
     * Applies [action], treating "there is no such schedule" as success.
     *
     * Every caller is a lifecycle transition of a campaign that may never have had a schedule at
     * all — pausing a one-shot campaign is normal — so absence is the expected case rather than an
     * error. A genuine Temporal fault still propagates: swallowing that would let a campaign report
     * itself paused while its schedule kept enrolling people.
     */
    private fun onExistingSchedule(campaignId: UUID, action: String, apply: (ScheduleHandle) -> Unit) {
        val id = scheduleId(campaignId)
        try {
            apply(handle(id))
            log.infof("Schedule %s: %s", id, action)
        } catch (e: ScheduleException) {
            log.debugf(e, "No schedule %s to %s — no-op", id, action)
        }
    }

    private fun handle(id: String): ScheduleHandle = scheduleClient.getHandle(id)

    companion object {
        /**
         * Derived from the campaign id, never random: it makes every operation idempotent and lets
         * an operator find a campaign's schedule from its id alone. Same reasoning as the journey
         * workflow id in [TemporalJourneySignaller].
         */
        fun scheduleId(campaignId: UUID): String = "campaign-enrolment-$campaignId"
    }
}

/**
 * Produces the [ScheduleClient]. The Quarkus Temporal extension exposes a [WorkflowClient] but no
 * schedule client, and `ScheduleClient.newInstance` needs the same service stubs — taking them from
 * the injected [WorkflowClient] keeps both on one connection and one set of TLS/namespace settings,
 * instead of a second configuration that can drift.
 */
@ApplicationScoped
class ScheduleClientProducer {

    @jakarta.enterprise.inject.Produces
    @ApplicationScoped
    fun scheduleClient(workflowClient: WorkflowClient): ScheduleClient =
        ScheduleClient.newInstance(workflowClient.workflowServiceStubs, workflowClient.options.let {
            io.temporal.client.schedules.ScheduleClientOptions.newBuilder()
                .setNamespace(it.namespace)
                .setDataConverter(it.dataConverter)
                .build()
        })
}
