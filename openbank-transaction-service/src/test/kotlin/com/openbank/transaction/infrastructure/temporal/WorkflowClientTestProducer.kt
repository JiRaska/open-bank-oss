// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.temporal

import com.openbank.transaction.application.workflow.PaymentWorkflow
import com.openbank.transaction.domain.saga.SagaState
import io.temporal.client.WorkflowClient
import io.temporal.testing.TestWorkflowEnvironment
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces
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
 * @PostConstruct) and closes on CDI context shutdown (@PreDestroy). A no-op [PaymentWorkflow]
 * worker is registered that immediately returns COMPLETED — sufficient for HTTP and contract tests
 * that need the transaction flow to succeed without real saga infrastructure.
 */
@ApplicationScoped
@Alternative
@Priority(1)
class WorkflowClientTestProducer {

    private lateinit var testEnv: TestWorkflowEnvironment

    @PostConstruct
    fun start() {
        testEnv = TestWorkflowEnvironment.newInstance()
        testEnv.newWorker("openbank-payment-execution")
            .registerWorkflowImplementationTypes(NoOpPaymentWorkflow::class.java)
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
        override fun execute(transactionId: UUID): SagaState = SagaState.COMPLETED
    }
}
