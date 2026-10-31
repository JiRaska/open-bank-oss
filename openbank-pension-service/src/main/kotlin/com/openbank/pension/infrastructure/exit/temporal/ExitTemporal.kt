// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.exit.temporal

import com.openbank.pension.application.exit.ExitExecutionService
import com.openbank.pension.application.exit.ExitWorkflowLauncher
import com.openbank.pension.application.exit.workflow.DeathSettlementWorkflow
import com.openbank.pension.application.exit.workflow.EXIT_TASK_QUEUE
import com.openbank.pension.application.exit.workflow.EarlyTerminationWorkflow
import com.openbank.pension.application.exit.workflow.ExitActivities
import com.openbank.pension.application.exit.workflow.PlannedInstallment
import com.openbank.pension.application.exit.workflow.RegularPayoutWorkflow
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Activity bindings: a thin bridge onto [ExitExecutionService]. Temporal runs activities on its own
 * threads, which carry no Vert.x context, and Hibernate Reactive refuses to run without one — so
 * every call is lifted onto a duplicated context through [VertxContextSupport], exactly as
 * domestic-payment's activities do. Never `runBlocking`.
 */
@Suppress("TooManyFunctions") // one bridge per activity
@ApplicationScoped
class ExitActivitiesImpl(private val execution: ExitExecutionService) : ExitActivities {

    private fun <T> vtx(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

    override fun terminationRedeem(noticeId: String) = vtx { execution.terminationRedeem(UUID.fromString(noticeId)) }
    override fun terminationSettle(noticeId: String) = vtx { execution.terminationSettle(UUID.fromString(noticeId)) }
    override fun terminationComplete(noticeId: String) =
        vtx { execution.terminationComplete(UUID.fromString(noticeId)) }

    override fun payoutPlan(payoutId: String): List<String> = vtx {
        execution.payoutPlan(UUID.fromString(payoutId)).map { (seq, day) -> PlannedInstallment(seq, day).encode() }
    }

    override fun payoutSingle(payoutId: String) = vtx { execution.payoutSingle(UUID.fromString(payoutId)) }
    override fun payoutInstallment(payoutId: String, seq: Int) =
        vtx { execution.payoutInstallment(UUID.fromString(payoutId), seq) }

    override fun payoutComplete(payoutId: String) = vtx { execution.payoutComplete(UUID.fromString(payoutId)) }

    override fun deathRedeem(claimId: String) = vtx { execution.deathRedeem(UUID.fromString(claimId)) }
    override fun deathClaimants(claimId: String): List<String> =
        vtx { execution.deathClaimants(UUID.fromString(claimId)) }
    override fun deathPayClaimant(claimId: String, claimantId: String) =
        vtx { execution.deathPayClaimant(UUID.fromString(claimId), UUID.fromString(claimantId)) }

    override fun deathSettle(claimId: String) = vtx { execution.deathSettle(UUID.fromString(claimId)) }
}

/**
 * Starts the exit workflows with the aggregate id as workflow id, so a second start for the same
 * notice / payout / claim (an idempotent replay, or the overdue sweep) is a no-op rather than a
 * second execution that could pay twice.
 */
@ApplicationScoped
class TemporalExitWorkflowLauncher(
    private val client: WorkflowClient,
    @param:ConfigProperty(name = "openbank.pension.exit.task-queue", defaultValue = EXIT_TASK_QUEUE)
    private val taskQueue: String,
) : ExitWorkflowLauncher {

    private val log = Logger.getLogger(TemporalExitWorkflowLauncher::class.java)

    private fun options(id: String) = WorkflowOptions.newBuilder().setTaskQueue(taskQueue).setWorkflowId(id).build()

    override fun startTermination(noticeId: UUID, noticePeriodDays: Int) = start("pension-termination-$noticeId") {
        val stub = client.newWorkflowStub(EarlyTerminationWorkflow::class.java, options(it))
        WorkflowClient.start({ stub.run(noticeId.toString(), noticePeriodDays.toLong()) })
    }

    override fun startPayout(payoutId: UUID) = start("pension-payout-$payoutId") {
        val stub = client.newWorkflowStub(RegularPayoutWorkflow::class.java, options(it))
        WorkflowClient.start({ stub.run(payoutId.toString()) })
    }

    override fun startDeathSettlement(claimId: UUID) = start("pension-death-$claimId") {
        val stub = client.newWorkflowStub(DeathSettlementWorkflow::class.java, options(it))
        WorkflowClient.start({ stub.run(claimId.toString()) })
    }

    private fun start(workflowId: String, launch: (String) -> Unit) {
        try {
            launch(workflowId)
        } catch (e: WorkflowExecutionAlreadyStarted) {
            log.debugf("workflow %s already running: %s", workflowId, e.message)
        }
    }
}
