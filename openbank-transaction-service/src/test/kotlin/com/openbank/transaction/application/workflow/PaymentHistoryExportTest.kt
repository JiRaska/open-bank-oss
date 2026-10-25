// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.workflow

import com.openbank.transaction.domain.saga.SagaState
import io.temporal.client.WorkflowFailedException
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.failure.ApplicationFailure
import io.temporal.serviceclient.Version
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.WorkerOptions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

/**
 * Explicit baseline capture, run on the pre-upgrade SDK. Never rewrites committed fixtures.
 * Only synthetic activities execute; this test never creates a production Temporal connection.
 */
@EnabledIfEnvironmentVariable(named = "OPENBANK_EXPORT_PAYMENT_HISTORIES", matches = "true")
class PaymentHistoryExportTest {
    @Test
    fun `export synthetic payment histories for review`() {
        check(Version.LIBRARY_VERSION == "1.25.1") { "Baseline export requires the original Temporal SDK 1.25.1" }
        val output = Path.of("build", "payment-history-export")
        Files.createDirectories(output)
        for (scenario in Scenario.entries) {
            TestWorkflowEnvironment.newInstance(
                TestEnvironmentOptions.newBuilder()
                    .setInitialTime(Instant.parse("2026-01-01T00:00:00Z"))
                    .setWorkflowClientOptions(paymentReplayClientOptions())
                    .build(),
            ).use { env ->
                val worker = env.newWorker(
                    TASK_QUEUE,
                    WorkerOptions.newBuilder().setIdentity("synthetic-payment-history").build(),
                )
                val activities = SyntheticActivities(scenario)
                worker.registerWorkflowImplementationTypes(PaymentWorkflowImpl::class.java)
                worker.registerActivitiesImplementations(activities)
                env.start()
                val workflow = env.workflowClient.newWorkflowStub(
                    PaymentWorkflow::class.java,
                    WorkflowOptions.newBuilder()
                        .setWorkflowId("synthetic-payment-${scenario.fileName}")
                        .setTaskQueue(TASK_QUEUE)
                        .build(),
                )
                val stub = WorkflowStub.fromTyped(workflow)
                val execution = stub.start(TRANSACTION_ID)
                if (scenario == Scenario.TERMINAL_FAILURE) {
                    assertThatThrownBy { stub.getResult(SagaState::class.java) }
                        .isInstanceOf(WorkflowFailedException::class.java)
                    assertThat(activities.completedCalls).isEqualTo(MAX_ATTEMPTS)
                    assertThat(activities.released).isFalse()
                    assertThat(activities.failedCalls).isZero()
                } else {
                    assertThat(stub.getResult(SagaState::class.java)).isEqualTo(scenario.result)
                    assertThat(activities.completedCalls)
                        .isEqualTo(if (scenario.result == SagaState.COMPLETED) 1 else 0)
                    assertThat(activities.released).isEqualTo(scenario == Scenario.JOURNAL_FAILURE)
                    assertThat(activities.failedCalls).isEqualTo(if (scenario.result == SagaState.COMPENSATED) 1 else 0)
                }
                if (scenario == Scenario.JOURNAL_RETRY_SUCCESS) assertThat(activities.journalCalls).isEqualTo(2)
                Files.writeString(
                    output.resolve("${scenario.fileName}.json"),
                    env.getWorkflowExecutionHistory(execution).toJson(true),
                    StandardOpenOption.CREATE_NEW,
                )
            }
        }
    }

    private enum class Scenario(val fileName: String, val result: SagaState = SagaState.COMPLETED) {
        DEBIT_SUCCESS("debit-success"),
        CREDIT_SUCCESS("credit-success"),
        HOLD_FAILURE("hold-failure", SagaState.COMPENSATED),
        JOURNAL_FAILURE("journal-failure", SagaState.COMPENSATED),
        CREDIT_JOURNAL_FAILURE("credit-journal-failure", SagaState.COMPENSATED),
        TERMINAL_FAILURE("terminal-failure"),
        JOURNAL_RETRY_SUCCESS("journal-retry-success"),
    }

    private class SyntheticActivities(private val scenario: Scenario) : PaymentActivities {
        var completedCalls = 0
        var failedCalls = 0
        var journalCalls = 0
        var released = false

        override fun placeHold(transactionId: UUID): UUID {
            assertThat(transactionId).isEqualTo(TRANSACTION_ID)
            if (scenario == Scenario.HOLD_FAILURE) {
                throw ApplicationFailure.newNonRetryableFailure("synthetic hold failure", "SyntheticHoldFailure")
            }
            return if (scenario in listOf(Scenario.CREDIT_SUCCESS, Scenario.CREDIT_JOURNAL_FAILURE)) {
                PaymentActivities.SENTINEL_HOLD
            } else {
                HOLD_ID
            }
        }

        override fun postJournal(transactionId: UUID): UUID {
            journalCalls++
            if (scenario in listOf(Scenario.JOURNAL_FAILURE, Scenario.CREDIT_JOURNAL_FAILURE)) {
                throw ApplicationFailure.newNonRetryableFailure("synthetic journal failure", "SyntheticJournalFailure")
            }
            if (scenario == Scenario.JOURNAL_RETRY_SUCCESS && journalCalls == 1) {
                throw ApplicationFailure.newFailure("synthetic retry", "SyntheticTransientFailure")
            }
            return JOURNAL_ID
        }

        override fun releaseHold(holdId: UUID) {
            assertThat(holdId).isEqualTo(HOLD_ID)
            released = true
        }

        override fun reverseJournal(journalId: UUID) = error("This workflow path must never reverse a journal")

        override fun markCompleted(transactionId: UUID) {
            completedCalls++
            if (scenario == Scenario.TERMINAL_FAILURE) {
                throw ApplicationFailure.newFailure("synthetic terminal write failure", "SyntheticTerminalFailure")
            }
        }

        override fun markFailed(transactionId: UUID, reason: String) {
            failedCalls++
        }
    }

    private companion object {
        const val TASK_QUEUE = "synthetic-payment-replay"
        const val MAX_ATTEMPTS = 3
        val TRANSACTION_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val HOLD_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000002")
        val JOURNAL_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000003")
    }
}
