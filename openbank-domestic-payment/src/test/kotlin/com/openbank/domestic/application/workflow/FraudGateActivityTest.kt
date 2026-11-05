// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import com.openbank.domestic.application.port.out.AmlCasePort
import com.openbank.domestic.application.port.out.CustomerNotificationPort
import com.openbank.domestic.application.port.out.DomesticPaymentEventPublisher
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.port.out.FraudScoreOutcome
import com.openbank.domestic.application.port.out.FraudScoringPort
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.domestic.infrastructure.observability.FraudEnforcementMetrics
import com.openbank.libs.domain.money.Money
import com.openbank.libs.fincrime.FraudVerdict
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * #4403: the fraud-gate decision table and the flag that switches it from shadow to enforced.
 * Every row here is red on a tree where the activity only shadow-scores.
 */
class FraudGateActivityTest {

    private val repository: DomesticPaymentRepository = mockk()
    private val fraudScoringPort: FraudScoringPort = mockk()
    private val eventPublisher: DomesticPaymentEventPublisher = mockk()
    private val notifications: CustomerNotificationPort = mockk(relaxed = true)
    private val amlCasePort: AmlCasePort = mockk(relaxed = true)
    private val registry = SimpleMeterRegistry()
    private val metrics = FraudEnforcementMetrics().also { it.bindTo(registry) }

    private fun activities(enforcement: Boolean) = object : DomesticPaymentActivitiesImpl(
        paymentRepository = repository,
        bookingWrites = mockk(),
        eventPublisher = eventPublisher,
        screeningPort = mockk(),
        amlCasePort = amlCasePort,
        fraudScoringPort = fraudScoringPort,
        schemeGatewayPort = mockk(),
        settlementPort = mockk(),
        accountLookupPort = mockk(),
        customerNotificationPort = notifications,
        clock = Clock.systemUTC(),
        metrics = mockk(relaxed = true),
        schemeSubmissionEnabled = false,
        fraudEnforcementMetrics = metrics,
        fraudEnforcementEnabled = enforcement,
    ) {
        override fun <T> vtx(block: suspend () -> T): T = runBlocking { block() }
    }

    private fun scored(verdict: FraudVerdict, synthetic: Boolean): UUID {
        val payment = payment(DomesticPaymentStatus.VALIDATED)
        coEvery { repository.findById(payment.id) } returns payment
        coEvery { fraudScoringPort.score(any()) } returns
            FraudScoreOutcome(verdict, 50, "v1", emptyList(), synthetic = synthetic)
        return payment.id
    }

    @ParameterizedTest(name = "enforced: {0} synthetic={1} -> {2}")
    @CsvSource(
        "ALLOW,false,PROCEED",
        "CHALLENGE,false,HOLD",
        "REVIEW,false,HOLD",
        "DECLINE,false,REJECT",
        "UNKNOWN,false,HOLD",
        // Fail closed: a synthetic ALLOW is a payment nobody scored, never a pass.
        "ALLOW,true,HOLD",
        "DECLINE,true,HOLD",
    )
    fun `with enforcement on the table decides`(verdict: FraudVerdict, synthetic: Boolean, expected: FraudGateAction) {
        val id = scored(verdict, synthetic)

        assertThat(activities(enforcement = true).fraudGate(id)).isEqualTo(expected)
        assertThat(decisions(expected, "enforced")).isEqualTo(1.0)
    }

    @ParameterizedTest(name = "shadow: {0} synthetic={1} proceeds, counted as {2}")
    @CsvSource("ALLOW,false,PROCEED", "DECLINE,false,REJECT", "REVIEW,false,HOLD", "ALLOW,true,HOLD")
    fun `with enforcement off everything proceeds but the would-be decision is counted`(
        verdict: FraudVerdict,
        synthetic: Boolean,
        wouldDo: FraudGateAction,
    ) {
        val id = scored(verdict, synthetic)

        assertThat(activities(enforcement = false).fraudGate(id)).isEqualTo(FraudGateAction.PROCEED)
        assertThat(decisions(wouldDo, "shadow")).isEqualTo(1.0)
        assertThat(decisions(wouldDo, "enforced")).isEqualTo(0.0)
    }

    @Test
    fun `an enforced HOLD opens a FRAUD_REVIEW case and a shadow one opens nothing`() {
        val id = scored(FraudVerdict.REVIEW, synthetic = false)

        activities(enforcement = false).fraudGate(id)
        coVerify(exactly = 0) { amlCasePort.openCase(any()) }

        activities(enforcement = true).fraudGate(id)
        coVerify(exactly = 1) { amlCasePort.openCase(match { it.alertCode == "FRAUD_REVIEW" && it.paymentId == id }) }
    }

    @Test
    fun `the flag is what changes the outcome - the same DECLINE flips from PROCEED to REJECT`() {
        val id = scored(FraudVerdict.DECLINE, synthetic = false)

        assertThat(activities(enforcement = false).fraudGate(id)).isEqualTo(FraudGateAction.PROCEED)
        assertThat(activities(enforcement = true).fraudGate(id)).isEqualTo(FraudGateAction.REJECT)
    }

    @Test
    fun `rejectForFraud records FRAUD_SUSPECTED, not SANCTIONS_HIT, and tells the customer nothing`() {
        val payment = payment(DomesticPaymentStatus.VALIDATED)
        coEvery { repository.findById(payment.id) } returns payment
        val updated = slot<DomesticPayment>()
        coEvery { repository.update(capture(updated), any()) } answers { firstArg() }
        every { eventPublisher.statusChangedPayload(any(), any()) } returns "{}"

        activities(enforcement = true).rejectForFraud(payment.id, FraudRejectCause.HOLD_TIMED_OUT)

        assertThat(updated.captured.status).isEqualTo(DomesticPaymentStatus.REJECTED)
        assertThat(updated.captured.rejectReason).isEqualTo(DomesticRejectReason.FRAUD_SUSPECTED)
        assertThat(updated.captured.rejectDetail).isNull()
        coVerify(exactly = 0) { notifications.notifyPaymentFailed(any(), any(), any(), any()) }
        assertThat(
            registry.get(FraudEnforcementMetrics.REJECTIONS_METRIC).tag("cause", "HOLD_TIMED_OUT").counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `every decision series exists at zero before the first payment`() {
        assertThat(registry.find(FraudEnforcementMetrics.DECISIONS_METRIC).counters()).hasSize(6)
        assertThat(registry.find(FraudEnforcementMetrics.REJECTIONS_METRIC).counters()).hasSize(3)
    }

    private fun decisions(decision: FraudGateAction, mode: String): Double =
        registry.get(FraudEnforcementMetrics.DECISIONS_METRIC)
            .tag("decision", decision.name)
            .tag("mode", mode)
            .counter()
            .count()

    private fun payment(status: DomesticPaymentStatus) = DomesticPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "dom-idem-fraud-gate",
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Alice Example",
        creditorAccountNumber = "9876543210",
        creditorBankCode = "0100",
        creditorName = "Brno Utility",
        amount = Money.of(BigDecimal("1500.00"), "CZK"),
        variableSymbol = "2026001",
        specificSymbol = null,
        constantSymbol = "0308",
        messageForPayee = "Utility bill",
        priority = DomesticPaymentPriority.URGENT,
        transferScope = DomesticTransferScope.EXTERNAL,
        technicalAccountCode = null,
        statementLabel = "Monthly settlement",
        endToEndId = "DOMU123",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        settledAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
