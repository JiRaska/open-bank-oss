// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.temporal

import com.openbank.pension.application.onboarding.DispatchResult
import com.openbank.pension.application.onboarding.OnboardingService
import com.openbank.pension.application.onboarding.OnboardingTimers
import com.openbank.pension.application.onboarding.PensionOrchestrator
import com.openbank.pension.application.onboarding.TransferInTimers
import com.openbank.pension.application.onboarding.TransferService
import com.openbank.pension.application.onboarding.workflow.OnboardingActivities
import com.openbank.pension.application.onboarding.workflow.OnboardingWorkflow
import com.openbank.pension.application.onboarding.workflow.OnboardingWorkflowImpl
import com.openbank.pension.application.onboarding.workflow.PensionWorkflowIds
import com.openbank.pension.application.onboarding.workflow.TransferInActivities
import com.openbank.pension.application.onboarding.workflow.TransferInWorkflow
import com.openbank.pension.application.onboarding.workflow.TransferInWorkflowImpl
import com.openbank.pension.application.onboarding.workflow.TransferOutActivities
import com.openbank.pension.application.onboarding.workflow.TransferOutWorkflow
import com.openbank.pension.application.onboarding.workflow.TransferOutWorkflowImpl
import com.openbank.pension.domain.transfer.FundsArrival
import com.openbank.pension.domain.transfer.TransferStatus
import io.quarkus.runtime.StartupEvent
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import io.temporal.worker.WorkerFactory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * A Temporal activity thread carries no Vert.x context, so a bare `runBlocking` around reactive
 * Panache throws `HR000068` and the step silently does nothing. Same bridge as campaign-service's
 * activities.
 */
internal fun <T> onWorker(block: suspend () -> T): T =
    VertxContextSupport.subscribeAndAwait { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }

/**
 * Starts and signals the pension workflows. The workflow id is the aggregate id, and an
 * already-started workflow is treated as success, so a retried signature or transfer request
 * re-ensures the workflow rather than starting a second one.
 */
@ApplicationScoped
class TemporalPensionOrchestrator(
    private val client: WorkflowClient,
    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-pension")
    private val taskQueue: String,
) : PensionOrchestrator {

    private fun options(id: String): WorkflowOptions = WorkflowOptions.newBuilder()
        .setTaskQueue(taskQueue)
        .setWorkflowId(id)
        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
        .build()

    /** Temporal client calls are blocking gRPC: never on the event loop. */
    private suspend fun blocking(call: () -> Unit) = withContext(Dispatchers.IO) { call() }

    private suspend fun idempotent(start: () -> Unit) = blocking {
        try {
            start()
        } catch (_: WorkflowExecutionAlreadyStarted) {
            // Already running or already finished for this aggregate: nothing to do.
        }
    }

    override suspend fun startOnboarding(applicationId: UUID, timers: OnboardingTimers) = idempotent {
        val stub = client.newWorkflowStub(OnboardingWorkflow::class.java, options(PensionWorkflowIds.onboarding(applicationId)))
        WorkflowClient.start(stub::run, applicationId, timers)
    }

    override suspend fun signalContributionReceived(applicationId: UUID) = blocking { onboarding(applicationId).contributionReceived() }

    override suspend fun signalWithdrawal(applicationId: UUID) = blocking { onboarding(applicationId).withdrawn() }

    override suspend fun startTransferIn(transferId: UUID, applicationId: UUID, timers: TransferInTimers) = idempotent {
        val stub = client.newWorkflowStub(TransferInWorkflow::class.java, options(PensionWorkflowIds.transferIn(transferId)))
        WorkflowClient.start(stub::run, transferId, timers)
    }

    override suspend fun signalCounterpartyAccepted(transferId: UUID) = blocking { transferIn(transferId).accepted() }

    override suspend fun signalCounterpartyRejected(transferId: UUID, reason: String) = blocking { transferIn(transferId).rejected(reason) }

    override suspend fun signalFundsReceived(transferId: UUID, arrival: FundsArrival) = blocking { transferIn(transferId).fundsReceived(arrival) }

    override suspend fun signalTransferWithdrawal(transferId: UUID) = blocking { transferIn(transferId).withdrawn() }

    override suspend fun startTransferOut(transferId: UUID) = idempotent {
        val stub = client.newWorkflowStub(TransferOutWorkflow::class.java, options(PensionWorkflowIds.transferOut(transferId)))
        WorkflowClient.start(stub::run, transferId)
    }

    private fun onboarding(id: UUID) =
        client.newWorkflowStub(OnboardingWorkflow::class.java, PensionWorkflowIds.onboarding(id))

    private fun transferIn(id: UUID) =
        client.newWorkflowStub(TransferInWorkflow::class.java, PensionWorkflowIds.transferIn(id))
}

/**
 * The activity implementations. Services are resolved through [Instance] at call time: the
 * services depend on the orchestrator, which depends on the client a test producer may build with
 * these activities registered — a constructor dependency would close that cycle.
 */
@ApplicationScoped
class PensionActivitiesImpl(
    private val onboardingService: Instance<OnboardingService>,
    private val transferService: Instance<TransferService>,
) : OnboardingActivities, TransferInActivities, TransferOutActivities {

    private val onboarding get() = onboardingService.get()
    private val transfers get() = transferService.get()

    override fun activate(applicationId: UUID): Boolean = onWorker { onboarding.activate(applicationId) }

    override fun expire(applicationId: UUID, reason: String) = onWorker { onboarding.expire(applicationId, reason) }

    override fun dispatch(transferId: UUID): DispatchResult = onWorker { transfers.dispatchIn(transferId) }

    override fun recordAccepted(transferId: UUID) = onWorker { transfers.recordAccepted(transferId) }

    override fun complete(transferId: UUID, arrival: FundsArrival) = onWorker { transfers.completeIn(transferId, arrival) }

    override fun fail(transferId: UUID, outcome: TransferStatus, reason: String) =
        onWorker { transfers.failIn(transferId, outcome, reason) }

    override fun valuate(transferId: UUID): Boolean = onWorker { transfers.valuateOut(transferId) }

    override fun redeem(transferId: UUID): String = onWorker { transfers.redeemOut(transferId) }

    override fun settle(transferId: UUID) = onWorker { transfers.settleOut(transferId) }

    override fun compensate(transferId: UUID, reason: String) = onWorker { transfers.compensateOut(transferId, reason) }
}

/** Registers the three pension workflows and their activities on this service's task queue. */
@ApplicationScoped
class PensionWorkerRegistrar(
    @ConfigProperty(name = "openbank.pension.worker.enabled", defaultValue = "true")
    private val workerEnabled: Boolean,
    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-pension")
    private val taskQueue: String,
    private val client: Instance<WorkflowClient>,
    private val activities: PensionActivitiesImpl,
) {
    private val log = Logger.getLogger(PensionWorkerRegistrar::class.java)

    @Suppress("UnusedParameter")
    fun onStart(@Observes event: StartupEvent) {
        if (!workerEnabled) {
            log.info("Temporal pension worker registration disabled (openbank.pension.worker.enabled=false)")
            return
        }
        val factory = WorkerFactory.newInstance(client.get())
        val worker = factory.newWorker(taskQueue)
        worker.registerWorkflowImplementationTypes(
            OnboardingWorkflowImpl::class.java,
            TransferInWorkflowImpl::class.java,
            TransferOutWorkflowImpl::class.java,
        )
        worker.registerActivitiesImplementations(activities)
        factory.start()
        log.infof("Temporal pension worker started on task queue '%s'", taskQueue)
    }
}
