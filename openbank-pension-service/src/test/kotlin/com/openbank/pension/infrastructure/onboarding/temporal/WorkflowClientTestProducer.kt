// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.temporal

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.pension.application.onboarding.workflow.OnboardingWorkflowImpl
import com.openbank.pension.application.onboarding.workflow.TransferInWorkflowImpl
import com.openbank.pension.application.onboarding.workflow.TransferOutWorkflowImpl
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
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces

/**
 * Replaces the real Temporal client in `@QuarkusTest` with an in-process environment that runs the
 * REAL workflows against the REAL activity bean, so the REST IT drives a signature through the
 * workflow into the database. The production worker stays off (`openbank.pension.worker.enabled=false`).
 */
@ApplicationScoped
@Alternative
@Priority(1)
class WorkflowClientTestProducer(private val activities: PensionActivitiesImpl) {

    private lateinit var testEnv: TestWorkflowEnvironment

    @PostConstruct
    fun start() {
        val converter = DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(
            JacksonJsonPayloadConverter(JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule()),
        )
        testEnv = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setDataConverter(converter).build())
                .build(),
        )
        val worker = testEnv.newWorker(ONBOARDING_TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(
            OnboardingWorkflowImpl::class.java,
            TransferInWorkflowImpl::class.java,
            TransferOutWorkflowImpl::class.java,
        )
        worker.registerActivitiesImplementations(activities)
        testEnv.start()
    }

    @Produces
    @ApplicationScoped
    fun workflowClient(): WorkflowClient = testEnv.workflowClient

    @PreDestroy
    fun stop() {
        if (::testEnv.isInitialized) testEnv.close()
    }
}
