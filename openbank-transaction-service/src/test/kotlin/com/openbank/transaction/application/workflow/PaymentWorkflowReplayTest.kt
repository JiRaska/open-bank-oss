// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.workflow

import com.openbank.libs.temporal.TemporalClientProducer
import com.openbank.libs.temporal.TemporalConfig
import com.openbank.transaction.domain.saga.SagaState
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.temporal.client.WorkflowClientOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import io.temporal.workflow.Workflow
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.UUID

class PaymentWorkflowReplayTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "debit-success", "credit-success", "hold-failure", "journal-failure",
            "credit-journal-failure", "terminal-failure", "journal-retry-success",
        ],
    )
    fun `SDK 1_25_1 history replays against current workflow`(scenario: String) {
        replayEnvironment().use { env ->
            WorkflowReplayer.replayWorkflowExecutionFromResource(
                "temporal/payment/sdk-1.25.1/$scenario.json",
                env,
                PaymentWorkflowImpl::class.java,
            )
        }
    }

    @Test
    fun `replay rejects a workflow with an incompatible command sequence`() {
        replayEnvironment().use { env ->
            assertThatThrownBy {
                WorkflowReplayer.replayWorkflowExecutionFromResource(
                    "temporal/payment/sdk-1.25.1/debit-success.json",
                    env,
                    IncompatiblePaymentWorkflow::class.java,
                )
            }.hasStackTraceContaining("NonDeterministicException")
        }
    }

    /** Test-only negative control: a timer cannot match the recorded first activity. */
    class IncompatiblePaymentWorkflow : PaymentWorkflow {
        override fun execute(transactionId: UUID): SagaState {
            Workflow.sleep(Duration.ofSeconds(1))
            return SagaState.COMPLETED
        }
    }
}

internal fun paymentReplayClientOptions(): WorkflowClientOptions {
    val registry = SimpleMeterRegistry()
    val config = object : TemporalConfig {
        override fun enabled() = false
        override fun serverUrl() = "localhost:7233"
        override fun namespace() = "synthetic-payment-replay"
        override fun taskQueue() = "synthetic-payment-replay"
        override fun metricsEnabled() = false
    }
    return try {
        WorkflowClientOptions.newBuilder(TemporalClientProducer(config, registry).workflowClientOptions())
            .setIdentity("synthetic-payment-history")
            .build()
    } finally {
        registry.close()
    }
}

private fun replayEnvironment(): TestWorkflowEnvironment = TestWorkflowEnvironment.newInstance(
    TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(paymentReplayClientOptions()).build(),
)
