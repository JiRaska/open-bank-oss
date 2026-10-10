// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.temporal

import com.openbank.pension.application.exit.workflow.DeathSettlementWorkflowImpl
import com.openbank.pension.application.exit.workflow.EXIT_TASK_QUEUE
import com.openbank.pension.application.exit.workflow.EarlyTerminationWorkflowImpl
import com.openbank.pension.application.exit.workflow.ExitActivities
import com.openbank.pension.application.exit.workflow.RegularPayoutWorkflowImpl
import com.openbank.pension.application.onboarding.workflow.OnboardingWorkflowImpl
import com.openbank.pension.application.onboarding.workflow.TransferInWorkflowImpl
import com.openbank.pension.application.onboarding.workflow.TransferOutWorkflowImpl
import com.openbank.pension.infrastructure.exit.temporal.ExitActivitiesImpl
import com.openbank.pension.infrastructure.onboarding.temporal.ONBOARDING_TASK_QUEUE
import com.openbank.pension.infrastructure.onboarding.temporal.PensionActivitiesImpl
import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import io.temporal.worker.Worker
import io.temporal.worker.WorkerFactory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * The ONE place pension-service's Temporal workers are registered (ADR-0334 S8).
 *
 * Each workflow family polls its OWN task queue: onboarding/transfer (S2) on
 * `openbank.pension.onboarding.task-queue`, exit (S5) on `openbank.pension.exit.task-queue`.
 * Before integration both slices started a worker on the same queue with disjoint workflow
 * types, so Temporal could hand a task to a poller that had not registered its type. The two
 * queues must differ; a configuration that collapses them is refused at boot.
 *
 * Switch per `rules.yaml: temporal_worker_switch_naming`: `openbank.pension.worker.enabled`
 * (off in `@QuarkusTest`, where the test environment registers the same families through
 * [registerOnboarding] / [registerExit]).
 */
@ApplicationScoped
class PensionWorkerRegistrar(
    @ConfigProperty(name = "openbank.pension.worker.enabled", defaultValue = "true")
    private val workerEnabled: Boolean,
    @ConfigProperty(name = "openbank.pension.onboarding.task-queue", defaultValue = ONBOARDING_TASK_QUEUE)
    private val onboardingQueue: String,
    @ConfigProperty(name = "openbank.pension.exit.task-queue", defaultValue = EXIT_TASK_QUEUE)
    private val exitQueue: String,
    private val client: Instance<WorkflowClient>,
    private val onboardingActivities: Instance<PensionActivitiesImpl>,
    private val exitActivities: Instance<ExitActivitiesImpl>,
) {
    private val log = Logger.getLogger(PensionWorkerRegistrar::class.java)

    @Suppress("UnusedParameter")
    fun onStart(@Observes event: StartupEvent) {
        if (!workerEnabled) {
            log.info("Temporal pension workers disabled (openbank.pension.worker.enabled=false)")
            return
        }
        requireDistinctQueues(onboardingQueue, exitQueue)
        val factory = WorkerFactory.newInstance(client.get())
        registerOnboarding(factory.newWorker(onboardingQueue), onboardingActivities.get())
        registerExit(factory.newWorker(exitQueue), exitActivities.get())
        factory.start()
        log.infof("Temporal pension workers started: onboarding on '%s', exit on '%s'", onboardingQueue, exitQueue)
    }

    companion object {
        fun requireDistinctQueues(onboardingQueue: String, exitQueue: String) = require(onboardingQueue != exitQueue) {
            "pension onboarding and exit workflows must poll different task queues (both '$exitQueue')"
        }

        /** Onboarding, transfer-in and transfer-out workflows with their activities. */
        fun registerOnboarding(worker: Worker, activities: PensionActivitiesImpl) {
            worker.registerWorkflowImplementationTypes(
                OnboardingWorkflowImpl::class.java,
                TransferInWorkflowImpl::class.java,
                TransferOutWorkflowImpl::class.java,
            )
            worker.registerActivitiesImplementations(activities)
        }

        /** Early termination, regular payout and death settlement workflows with their activities. */
        fun registerExit(worker: Worker, activities: ExitActivities) {
            worker.registerWorkflowImplementationTypes(
                EarlyTerminationWorkflowImpl::class.java,
                RegularPayoutWorkflowImpl::class.java,
                DeathSettlementWorkflowImpl::class.java,
            )
            worker.registerActivitiesImplementations(activities)
        }
    }
}
