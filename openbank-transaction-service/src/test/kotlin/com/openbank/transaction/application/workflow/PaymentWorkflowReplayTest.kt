// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.workflow

import com.openbank.libs.temporal.TemporalClientProducer
import com.openbank.libs.temporal.TemporalConfig
import com.openbank.transaction.domain.saga.SagaState
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.Version
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import io.temporal.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.util.UUID
import java.util.stream.Stream

class PaymentWorkflowReplayTest {
    @Test
    fun `replay uses the requested SDK runtime`() {
        assertThat(Version.LIBRARY_VERSION)
            .isEqualTo(System.getProperty("openbank.payment.replay.expected-sdk", "1.40.0"))
    }

    @ParameterizedTest(name = "SDK {0} history: {1}")
    @MethodSource("histories")
    fun `retained history has the recorded compatibility with the loaded SDK`(sdk: String, scenario: String) {
        replayEnvironment().use { env ->
            val replay = {
                WorkflowReplayer.replayWorkflowExecutionFromResource(
                    "temporal/payment/sdk-$sdk/$scenario.json",
                    env,
                    PaymentWorkflowImpl::class.java,
                )
            }
            if (Version.LIBRARY_VERSION == "1.25.1" && sdk == "1.40.0") {
                // Captured evidence: SDK 1.40 records flag 3 even on the simple payment path.
                // Missing resources or arbitrary failures must not satisfy this boundary check.
                assertThatThrownBy { replay() }.hasStackTraceContaining("Unknown SDK flag:3")
            } else {
                replay()
            }
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

    companion object {
        @JvmStatic
        fun histories(): Stream<Arguments> = listOf("1.25.1", "1.40.0").flatMap { sdk ->
            listOf(
                "debit-success",
                "credit-success",
                "hold-failure",
                "journal-failure",
                "credit-journal-failure",
                "terminal-failure",
                "journal-retry-success",
            ).map { Arguments.of(sdk, it) }
        }.stream()
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
