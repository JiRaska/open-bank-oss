// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.it

import com.openbank.transaction.application.workflow.PaymentWorkflow
import com.openbank.transaction.domain.saga.SagaState
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.temporal.testing.TestWorkflowEnvironment
import java.util.UUID

/**
 * Starts an in-process Temporal server (TestWorkflowEnvironment) and overrides the server URL so
 * that TemporalClientProducer dials the in-process stub instead of localhost:7233 (ADR-0120 Phase
 * 5). A NoOpPaymentWorkflow worker is registered that returns COMPLETED without calling any
 * activities — sufficient for HTTP and Pact contract tests that need the transaction flow to
 * succeed without real saga infrastructure.
 */
class TemporalTestResource : QuarkusTestResourceLifecycleManager {

    private var testEnv: TestWorkflowEnvironment? = null

    override fun start(): Map<String, String> {
        val env = TestWorkflowEnvironment.newInstance()
        env.newWorker("openbank-payment-execution")
            .registerWorkflowImplementationTypes(NoOpPaymentWorkflow::class.java)
        env.start()
        testEnv = env
        return mapOf("openbank.transaction.orchestration.temporal.server-url" to env.temporalServiceAddress)
    }

    override fun stop() {
        testEnv?.close()
    }

    class NoOpPaymentWorkflow : PaymentWorkflow {
        override fun execute(transactionId: UUID): SagaState = SagaState.COMPLETED
    }
}
