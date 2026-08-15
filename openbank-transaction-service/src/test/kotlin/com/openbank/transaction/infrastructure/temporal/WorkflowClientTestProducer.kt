// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.temporal

import com.openbank.transaction.application.workflow.PaymentActivities
import com.openbank.transaction.application.workflow.PaymentActivitiesImpl
import com.openbank.transaction.application.workflow.PaymentWorkflow
import com.openbank.transaction.domain.saga.SagaState
import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClient
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.workflow.Workflow
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces
import java.time.Duration
import java.util.UUID

/**
 * Test-scope CDI alternative that replaces [TemporalClientProducer] for all @QuarkusTest runs.
 *
 * [TemporalClientProducer] creates a [WorkflowClient] by dialling the Temporal frontend over gRPC
 * (default localhost:7233); that port is not available in CI — it only runs in the payment-execution
 * worker pod. Annotating this class @Alternative @Priority(1) makes Quarkus Arc prefer it over
 * TemporalClientProducer so no network connection is ever attempted.
 *
 * The in-process [TestWorkflowEnvironment] starts before the first use of the produced bean (in
 * @PostConstruct) and closes on CDI context shutdown (@PreDestroy). A stub [PaymentWorkflow]
 * worker is registered that skips the hold/journal legs (no real saga infrastructure in a
 * @QuarkusTest) but still performs the REAL terminal write through the real
 * [PaymentActivitiesImpl] — since #4238 that write belongs to the workflow, so a stub workflow
 * that only returned COMPLETED would leave every test transaction PENDING and would be lying
 * about the production shape.
 */
@ApplicationScoped
@Alternative
@Priority(1)
class WorkflowClientTestProducer(private val activities: PaymentActivitiesImpl) {

    private lateinit var testEnv: TestWorkflowEnvironment

    @PostConstruct
    fun start() {
        testEnv = TestWorkflowEnvironment.newInstance()
        val worker = testEnv.newWorker("openbank-payment-execution")
        worker.registerWorkflowImplementationTypes(NoOpPaymentWorkflow::class.java)
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

    class NoOpPaymentWorkflow : PaymentWorkflow {
        private val finalisation: PaymentActivities = Workflow.newActivityStub(
            PaymentActivities::class.java,
            ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofMinutes(1))
                .build(),
        )

        override fun execute(transactionId: UUID): SagaState {
            finalisation.markCompleted(transactionId)
            return SagaState.COMPLETED
        }
    }
}
