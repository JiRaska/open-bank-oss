// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.workflow

import com.openbank.libs.domain.money.Money
import com.openbank.libs.fincrime.FraudVerdict
import com.openbank.libs.fincrime.ScreeningMatchStatus
import com.openbank.libs.fincrime.ScreeningResult
import com.openbank.libs.fincrime.ScreeningRole
import com.openbank.libs.observability.DomainMetrics
import com.openbank.sepa.application.port.out.AmlCasePort
import com.openbank.sepa.application.port.out.FraudScoreOutcome
import com.openbank.sepa.application.port.out.FraudScoringPort
import com.openbank.sepa.application.port.out.SanctionsScreeningPort
import com.openbank.sepa.application.port.out.SchemeGatewayPort
import com.openbank.sepa.application.port.out.SchemeGatewayUnavailableException
import com.openbank.sepa.application.port.out.SchemeSubmissionOutcome
import com.openbank.sepa.application.port.out.ScreeningUnavailableException
import com.openbank.sepa.application.port.out.SepaBookingAgeSnapshot
import com.openbank.sepa.application.port.out.SepaBookingQuery
import com.openbank.sepa.application.port.out.SepaBookingWrites
import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.application.port.out.SepaPaymentRepository
import com.openbank.sepa.application.port.out.SettlementOutcome
import com.openbank.sepa.application.port.out.SettlementPort
import com.openbank.sepa.application.port.out.SettlementUnavailableException
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.mockk
import io.temporal.client.WorkflowFailedException
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.testing.TestWorkflowEnvironment
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records the SEPA workflow histories [SepaPaymentWorkflowReplayTest] replays (#15724).
 *
 * Runs the SHIPPED [SepaPaymentWorkflowImpl] and the SHIPPED [SepaPaymentActivitiesImpl] on
 * Temporal's in-process test server; only the outbound ports (repository, screening, AML, fraud,
 * scheme gateway, settlement) are stubbed, plus the Vert.x context bridge the activities need in a
 * running Quarkus. Nothing about the workflow is reimplemented here.
 *
 * Off unless `SEPA_RECORD_TEMPORAL_HISTORIES=true`, and it refuses to overwrite an existing file:
 * a history is evidence about the code that recorded it, so an existing one is never regenerated.
 */
@EnabledIfEnvironmentVariable(named = "SEPA_RECORD_TEMPORAL_HISTORIES", matches = "true")
class SepaPaymentWorkflowHistoryRecorder {

    /** One recorded path. Defaults describe the happy path. */
    data class Scenario(
        val name: String,
        val expected: SepaPaymentStatus?,
        val debtorStatus: ScreeningMatchStatus = ScreeningMatchStatus.CLEAR,
        val screeningUnavailable: Boolean = false,
        val schemeEnabled: Boolean = true,
        val schemeAccepted: Boolean = true,
        val schemeReason: String? = null,
        val gatewayUnavailable: Boolean = false,
        val settlementUnavailable: Boolean = false,
        val settlementNotSettled: Boolean = false,
        val screenLoadFailures: Int = 0,
        val submitLoadFailures: Int = 0,
        val cancelDuringScreening: Boolean = false,
    ) {
        override fun toString() = name
    }

    companion object {
        private const val TASK_QUEUE = "sepa-history-recording"
        private const val ALWAYS = Int.MAX_VALUE

        @JvmStatic
        fun scenarios(): List<Scenario> = listOf(
            Scenario("clear-scheme-accepted-settled-completed", SepaPaymentStatus.COMPLETED),
            Scenario(
                "clear-scheme-accepted-settlement-unavailable-processing",
                SepaPaymentStatus.PROCESSING,
                settlementUnavailable = true,
            ),
            Scenario(
                "clear-scheme-accepted-settlement-not-settled-processing",
                SepaPaymentStatus.PROCESSING,
                settlementNotSettled = true,
            ),
            rejected("clear-scheme-rejected-ac04-account-closed", "AC04"),
            rejected("clear-scheme-rejected-ac06-account-frozen", "AC06"),
            rejected("clear-scheme-rejected-rc01-invalid-bic", "RC01"),
            rejected("clear-scheme-rejected-rr04-aml-hold", "RR04"),
            rejected("clear-scheme-rejected-unmapped-technical-error", "XX99"),
            Scenario(
                "clear-scheme-gateway-unavailable-validated",
                SepaPaymentStatus.VALIDATED,
                gatewayUnavailable = true,
            ),
            Scenario("clear-scheme-submission-disabled-validated", SepaPaymentStatus.VALIDATED, schemeEnabled = false),
            Scenario(
                "block-sanctions-hit-rejected",
                SepaPaymentStatus.REJECTED,
                debtorStatus = ScreeningMatchStatus.HIT,
            ),
            Scenario(
                "review-potential-hit-received",
                SepaPaymentStatus.RECEIVED,
                debtorStatus = ScreeningMatchStatus.POTENTIAL_HIT,
            ),
            Scenario("review-screening-unavailable-received", SepaPaymentStatus.RECEIVED, screeningUnavailable = true),
            Scenario(
                "screen-activity-retried-then-clear-completed",
                SepaPaymentStatus.COMPLETED,
                screenLoadFailures = 1,
            ),
            Scenario("submit-activity-retried-then-completed", SepaPaymentStatus.COMPLETED, submitLoadFailures = 1),
            Scenario("screen-activity-retries-exhausted-workflow-failed", null, screenLoadFailures = ALWAYS),
            Scenario("cancelled-during-screening", null, cancelDuringScreening = true),
        )

        private fun rejected(name: String, code: String) =
            Scenario(name, SepaPaymentStatus.REJECTED, schemeAccepted = false, schemeReason = code)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    fun record(scenario: Scenario) {
        val target = SepaPaymentWorkflowHistoryFixtures.HISTORY_DIR.resolve("${scenario.name}.json")
        // A history is evidence about the code that recorded it: an existing one is never regenerated.
        assumeFalse(Files.exists(target), "$target already recorded")
        val index = scenarios().indexOf(scenario) + 1
        val paymentId = UUID.fromString("00000000-0000-4000-8000-%012d".format(index))
        val screeningStarted = CountDownLatch(1)
        val releaseScreening = CountDownLatch(1)

        TestWorkflowEnvironment.newInstance(SepaPaymentWorkflowHistoryFixtures.environmentOptions()).use { env ->
            val worker = env.newWorker(TASK_QUEUE)
            worker.registerWorkflowImplementationTypes(SepaPaymentWorkflowImpl::class.java)
            worker.registerActivitiesImplementations(
                activitiesFor(scenario, paymentId, screeningStarted, releaseScreening),
            )
            env.start()

            val stub = env.workflowClient.newWorkflowStub(
                SepaPaymentWorkflow::class.java,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(TASK_QUEUE)
                    .setWorkflowId("sepa-payment-$paymentId")
                    .build(),
            )
            val untyped = WorkflowStub.fromTyped(stub)
            val execution = untyped.start(paymentId)

            when {
                scenario.cancelDuringScreening -> {
                    assertThat(screeningStarted.await(30, TimeUnit.SECONDS)).isTrue()
                    untyped.cancel()
                    assertThatThrownBy { untyped.getResult(SepaPaymentStatus::class.java) }
                        .isInstanceOf(WorkflowFailedException::class.java)
                    releaseScreening.countDown()
                }
                scenario.expected == null ->
                    assertThatThrownBy { untyped.getResult(SepaPaymentStatus::class.java) }
                        .isInstanceOf(WorkflowFailedException::class.java)
                else ->
                    assertThat(untyped.getResult(SepaPaymentStatus::class.java)).isEqualTo(scenario.expected)
            }

            val history = env.getWorkflowExecutionHistory(execution)
            Files.createDirectories(target.parent)
            Files.writeString(target, history.toJson(true) + "\n")
        }
    }

    @Suppress("LongMethod", "ThrowsCount")
    private fun activitiesFor(
        s: Scenario,
        paymentId: UUID,
        screeningStarted: CountDownLatch,
        releaseScreening: CountDownLatch,
    ): SepaPaymentActivities {
        val created = Instant.parse("2026-01-01T00:00:00Z")
        var current = SepaPayment(
            id = paymentId,
            idempotencyKey = "synthetic-$paymentId",
            type = SepaPaymentType.SCT,
            status = SepaPaymentStatus.RECEIVED,
            debtorAccountId = UUID.fromString("00000000-0000-4000-8000-0000000000d1"),
            debtorIban = "DE89370400440532013000",
            debtorName = "Synthetic Debtor",
            creditorIban = "GB29NWBK60161331926819",
            creditorName = "Synthetic Creditor",
            creditorBic = null,
            amount = Money.of(BigDecimal("100.00"), "EUR"),
            remittanceInfo = null,
            endToEndId = "E2E-SYNTHETIC",
            rejectReason = null,
            rejectDetail = null,
            submittedAt = null,
            completedAt = null,
            createdAt = created,
            updatedAt = created,
        )
        val findCalls = AtomicInteger()
        val repository = mockk<SepaPaymentRepository>()
        coEvery { repository.findById(paymentId) } answers {
            val call = findCalls.incrementAndGet()
            // findById is the first port screenPayment touches, so call N is screening attempt N.
            if (call <= s.screenLoadFailures) error("synthetic transient repository failure")
            current
        }
        coEvery { repository.update(any(), any()) } answers { firstArg<SepaPayment>().also { current = it } }
        coEvery { repository.completeSettlement(any(), any(), any(), any()) } answers {
            val completed = firstArg<SepaPayment>().transitionTo(
                SepaPaymentStatus.COMPLETED,
                clock = Clock.fixed(thirdArg(), ZoneOffset.UTC),
            ).copy(transactionId = secondArg())
            arg<(SepaPayment) -> SepaPaymentOutboxMessage>(3)(completed)
            completed.also { current = it }
        }

        val screening = mockk<SanctionsScreeningPort>()
        coEvery { screening.screen(any(), any(), any()) } answers {
            val role = secondArg<ScreeningRole>()
            if (role == ScreeningRole.DEBTOR) {
                screeningStarted.countDown()
                if (s.cancelDuringScreening) releaseScreening.await(60, TimeUnit.SECONDS)
            }
            if (s.screeningUnavailable) throw ScreeningUnavailableException(IllegalStateException("synthetic outage"))
            val status = if (role == ScreeningRole.DEBTOR) s.debtorStatus else ScreeningMatchStatus.CLEAR
            ScreeningResult(firstArg(), role, status, if (status == ScreeningMatchStatus.CLEAR) 0.0 else 0.5, null)
        }
        val aml = mockk<AmlCasePort>()
        coJustRun { aml.openCase(any()) }
        val fraud = mockk<FraudScoringPort>()
        coEvery { fraud.score(any()) } returns FraudScoreOutcome(FraudVerdict.ALLOW, 0, "synthetic", emptyList())
        val gateway = mockk<SchemeGatewayPort>()
        val submitCalls = AtomicInteger()
        coEvery { gateway.submit(any()) } answers {
            // Not SchemeGatewayUnavailableException (which the activity absorbs): this one escapes the
            // activity, so Temporal retries submitToScheme under the workflow's RetryOptions.
            if (submitCalls.incrementAndGet() <= s.submitLoadFailures) error("synthetic transient gateway failure")
            if (s.gatewayUnavailable) throw SchemeGatewayUnavailableException(IllegalStateException("synthetic outage"))
            SchemeSubmissionOutcome(s.schemeAccepted, s.schemeReason)
        }
        val settlement = mockk<SettlementPort>()
        coEvery { settlement.settle(any(), any()) } answers {
            when {
                s.settlementUnavailable -> throw SettlementUnavailableException("synthetic outage")
                s.settlementNotSettled -> SettlementOutcome(false, null)
                else -> SettlementOutcome(true, UUID.fromString("00000000-0000-4000-8000-0000000000e1"))
            }
        }
        val bookingWrites = object : SepaBookingWrites {
            override suspend fun acceptForBooking(payment: SepaPayment, outbox: SepaPaymentOutboxMessage) =
                repository.update(payment, outbox)
        }
        val bookingQuery = object : SepaBookingQuery {
            override suspend fun bookingAgeSnapshot() = SepaBookingAgeSnapshot(null, 0)
            override suspend fun pendingBookingDueAt(paymentId: UUID): Instant? = null
        }
        return RecordingActivities(
            repository, bookingWrites, bookingQuery, screening, aml, fraud, gateway, settlement,
            Clock.systemUTC(), mockk<DomainMetrics>(relaxed = true), s.schemeEnabled,
        )
    }
}

/** The shipped activities; only the Vert.x context bridge is replaced, as in SepaPaymentActivitiesImplTest. */
@Suppress("LongParameterList")
private class RecordingActivities(
    paymentRepository: SepaPaymentRepository,
    bookingWrites: SepaBookingWrites,
    bookingQuery: SepaBookingQuery,
    screeningPort: SanctionsScreeningPort,
    amlCasePort: AmlCasePort,
    fraudScoringPort: FraudScoringPort,
    schemeGatewayPort: SchemeGatewayPort,
    settlementPort: SettlementPort,
    clock: Clock,
    metrics: DomainMetrics,
    schemeSubmissionEnabled: Boolean,
) : SepaPaymentActivitiesImpl(
    paymentRepository, bookingWrites, bookingQuery, screeningPort, amlCasePort, fraudScoringPort,
    schemeGatewayPort, settlementPort, clock, metrics, schemeSubmissionEnabled,
) {
    override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
}
