// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.testsupport

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.pension.infrastructure.exit.temporal.ExitActivitiesImpl
import com.openbank.pension.infrastructure.onboarding.temporal.PensionActivitiesImpl
import com.openbank.pension.infrastructure.temporal.PensionWorkerRegistrar
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
 * The ONE in-process Temporal for every `@QuarkusTest` in this module (the fleet's
 * `WorkflowClientTestProducer` pattern). The real `WorkflowClient` is replaced by a time-skipping
 * [TestWorkflowEnvironment], and the REAL workflows and activities are registered through the
 * production registrar's own [PensionWorkerRegistrar.registerOnboarding] /
 * [PensionWorkerRegistrar.registerExit], each on its OWN configured task queue — exactly the
 * topology a deployed pod runs, so a workflow started on the wrong queue never executes here
 * either.
 *
 * Workers are registered on `StartupEvent`, not in `@PostConstruct`: the activity beans depend
 * (through the services) on the client this bean produces.
 */
@ApplicationScoped
@Alternative
@Priority(1)
class PensionTemporalTestEnvironment {

    @ConfigProperty(name = "openbank.pension.onboarding.task-queue")
    lateinit var onboardingQueue: String

    @ConfigProperty(name = "openbank.pension.exit.task-queue")
    lateinit var exitQueue: String

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
        exit: Instance<ExitActivitiesImpl>,
    ) {
        PensionWorkerRegistrar.requireDistinctQueues(onboardingQueue, exitQueue)
        PensionWorkerRegistrar.registerOnboarding(env.newWorker(onboardingQueue), onboarding.get())
        PensionWorkerRegistrar.registerExit(env.newWorker(exitQueue), exit.get())
        env.start()
    }

    @jakarta.inject.Inject
    lateinit var clock: WorkflowTimeClock

    /**
     * Advance workflow time (cooling-off, notice period, instalment due dates) without waiting —
     * and the service clock with it where the profile asks for that ([WorkflowTimeClock]).
     */
    fun advance(duration: Duration) {
        clock.advance(duration)
        env.sleep(duration)
    }

    @PreDestroy
    fun stop() {
        if (::env.isInitialized) env.close()
    }
}
