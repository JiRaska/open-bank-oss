// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.e2e.support

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.pension.application.exit.workflow.ExitActivities
import com.openbank.pension.application.onboarding.workflow.OnboardingWorkflowImpl
import com.openbank.pension.application.onboarding.workflow.TransferInWorkflowImpl
import com.openbank.pension.application.onboarding.workflow.TransferOutWorkflowImpl
import com.openbank.pension.infrastructure.exit.temporal.ExitWorkerRegistrar
import com.openbank.pension.infrastructure.onboarding.temporal.PensionActivitiesImpl
import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration

/**
 * The fleet's in-process Temporal pattern (`WorkflowClientTestProducer` in domestic-payment,
 * settlement, sepa-payment): the real `WorkflowClient` is replaced by a time-skipping
 * [TestWorkflowEnvironment], and — unlike those no-op variants — the REAL pension workflows and
 * activities are registered on it, so an E2E journey runs onboarding, transfer and exit
 * orchestration exactly as a deployed worker would, only with a clock the test can advance.
 *
 * Every workflow is registered on ONE worker for the configured task queue. In a deployed pod the
 * onboarding (S2) and exit (S5) registrars each create their own worker on that same queue with a
 * disjoint set of workflow types; see the PR description for why that is a finding.
 *
 * Workers are registered on `StartupEvent`, not in `@PostConstruct`: the activity beans depend
 * (through the services) on the `WorkflowClient` this bean produces, so asking for them while the
 * client is still being created would be a cycle.
 */
@ApplicationScoped
@Alternative
@Priority(1)
class PensionTemporalTestEnvironment {

    @ConfigProperty(name = "openbank.temporal.task-queue")
    lateinit var taskQueue: String

    private lateinit var env: TestWorkflowEnvironment

    @PostConstruct
    fun create() {
        // The production client (libs-temporal TemporalClientProducer) uses a Kotlin-aware JSON
        // converter; workflow arguments here are Kotlin data classes, so the test must match it.
        val converter = DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(
            JacksonJsonPayloadConverter(JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule()),
        )
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setDataConverter(converter).build())
                .build(),
        )
    }

    @Produces
    @ApplicationScoped
    fun workflowClient(): WorkflowClient = env.workflowClient

    @Suppress("UnusedParameter")
    fun onStart(
        @Observes event: StartupEvent,
        onboarding: Instance<PensionActivitiesImpl>,
        exit: Instance<ExitActivities>,
    ) {
        val worker = env.newWorker(taskQueue)
        worker.registerWorkflowImplementationTypes(
            OnboardingWorkflowImpl::class.java,
            TransferInWorkflowImpl::class.java,
            TransferOutWorkflowImpl::class.java,
        )
        worker.registerActivitiesImplementations(onboarding.get())
        ExitWorkerRegistrar.register(worker, exit.get())
        env.start()
    }

    /** Advance workflow time (cooling-off, notice period, instalment due dates) without waiting. */
    fun advance(duration: Duration) = env.sleep(duration)

    @PreDestroy
    fun stop() {
        if (::env.isInitialized) env.close()
    }
}
