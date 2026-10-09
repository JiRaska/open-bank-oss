// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.exit

import com.openbank.pension.application.exit.workflow.EXIT_TASK_QUEUE
import com.openbank.pension.infrastructure.exit.temporal.ExitActivitiesImpl
import com.openbank.pension.infrastructure.exit.temporal.ExitWorkerRegistrar
import io.temporal.client.WorkflowClient
import io.temporal.testing.TestWorkflowEnvironment
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject

/**
 * Replaces the real Temporal client in @QuarkusTest with an in-process, time-skipping
 * [TestWorkflowEnvironment] whose worker runs the REAL exit workflows over the REAL activity bean —
 * so a REST call that signs a notice really executes, against the real database, once the IT
 * skips the notice period with [env]`.sleep`.
 */
@ApplicationScoped
@Alternative
@Priority(1)
class WorkflowClientTestProducer {

    @Inject
    lateinit var activities: ExitActivitiesImpl

    lateinit var env: TestWorkflowEnvironment

    @PostConstruct
    fun start() {
        env = TestWorkflowEnvironment.newInstance()
        ExitWorkerRegistrar.register(env.newWorker(EXIT_TASK_QUEUE), activities)
        env.start()
    }

    @Produces
    @ApplicationScoped
    fun workflowClient(): WorkflowClient = env.workflowClient

    @PreDestroy
    fun stop() {
        if (::env.isInitialized) env.close()
    }
}
