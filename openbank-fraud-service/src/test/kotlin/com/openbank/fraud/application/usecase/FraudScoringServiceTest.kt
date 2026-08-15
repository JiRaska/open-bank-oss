// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.usecase

import com.openbank.fraud.application.port.out.FraudMetricsPort
import com.openbank.fraud.application.port.out.FraudScoreRepository
import com.openbank.fraud.application.port.out.MlModelPort
import com.openbank.fraud.application.port.out.PayeeHistoryRepository
import com.openbank.fraud.application.port.out.VelocityAggregateRepository
import com.openbank.fraud.domain.model.FraudVerdict
import com.openbank.fraud.domain.model.PayeeHistory
import com.openbank.fraud.domain.model.ScoreRequest
import com.openbank.fraud.domain.model.VelocityAggregate
import com.openbank.fraud.domain.model.VelocityWindow
import com.openbank.libs.domain.feature.OnlineFeatureStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class FraudScoringServiceTest {

    private val repository = mockk<FraudScoreRepository>()
    private val metrics = mockk<FraudMetricsPort>(relaxed = true)
    private val velocityRepo = mockk<VelocityAggregateRepository>()
    private val payeeHistoryRepo = mockk<PayeeHistoryRepository>()
    private val featureStore = mockk<OnlineFeatureStore>(relaxed = true)
    private val mlModel = mockk<MlModelPort>(relaxed = true)

    // shadowEnabled = false: these tests assert the rules behaviour; the shadow plane is covered by
    // FraudScoringShadowZeroDriftTest. With it off, featureStore/mlModel are never touched.
    private val service =
        FraudScoringService(
            repository,
            metrics,
            velocityRepo,
            payeeHistoryRepo,
            featureStore,
            mlModel,
            java.time.Clock.systemUTC(),
            false,
        ).also {
            // fraudHoldService is field-injected (LongParameterList, see the class KDoc) — a
            // plain constructor call here bypasses CDI, so it must be set manually or score()
            // throws UninitializedPropertyAccessException. relaxed: these tests assert scoring
            // behaviour, not the fraud-hold side effect (see FraudHoldServiceTest for that).
            it.fraudHoldService = mockk(relaxed = true)
        }

    private fun request(accountId: UUID = UUID.randomUUID()) = ScoreRequest(
        amount = BigDecimal("99.99"),
        currency = "EUR",
        rail = "SEPA",
        accountId = accountId,
        counterpartyId = UUID.randomUUID(),
    )

    private fun velocityAggregate(
        accountId: UUID,
        window: VelocityWindow,
        count: Long,
        totalAmount: BigDecimal = BigDecimal("100.00"),
    ) = VelocityAggregate(
        accountId = accountId,
        window = window,
        transactionCount = count,
        totalAmount = totalAmount,
        currency = "EUR",
        windowStart = Instant.now(),
    )

    private fun establishedPayeeHistory() = PayeeHistory(
        accountId = UUID.randomUUID(),
        payeeIdentifier = UUID.randomUUID().toString(),
        firstSeenAt = Instant.now().minusSeconds(86400),
        lastPaidAt = Instant.now(),
        paymentCount = 3L,
    )

    /**
     * Most tests in this class exercise a rule OTHER than [com.openbank.fraud.domain.rules.NewPayeeHighAmountReviewRule]
     * and must not have it fire incidentally — this stubs payeeHistoryRepo as an "established payee"
     * (a history row exists) so isNewPayee enrichment stays false by default. Tests that specifically
     * cover the new-payee rule override this with an explicit stub.
     */
    private fun stubEstablishedPayee() {
        coEvery { payeeHistoryRepo.findHistory(any(), any()) } returns establishedPayeeHistory()
    }

    @Test
    fun `scores via the rule engine and returns the baseline ALLOW verdict`(): Unit = runBlocking {
        coEvery { repository.save(any(), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(any(), any(), any()) } returns null
        stubEstablishedPayee()

        val result = service.score(request())

        assertThat(result.verdict).isEqualTo(FraudVerdict.ALLOW)
        assertThat(result.ruleVersion).isEqualTo("v4")
    }

    @Test
    fun `persists every scoring decision as an audit row`(): Unit = runBlocking {
        val req = request()
        val savedReq = slot<ScoreRequest>()
        coEvery { repository.save(capture(savedReq), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(any(), any(), any()) } returns null
        stubEstablishedPayee()

        service.score(req)

        coVerify(exactly = 1) { repository.save(any(), any()) }
        assertThat(savedReq.captured.amount).isEqualTo(req.amount)
        assertThat(savedReq.captured.currency).isEqualTo(req.currency)
    }

    @Test
    fun `records a verdict-tagged metric for every decision`(): Unit = runBlocking {
        val req = request()
        coEvery { repository.save(any(), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(any(), any(), any()) } returns null
        stubEstablishedPayee()

        service.score(req)

        verify(exactly = 1) { metrics.recordVerdict(FraudVerdict.ALLOW, req.rail) }
    }

    @Test
    fun `enriches request with velocity counters from repository`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val req = request(accountId = accountId)
        val savedReq = slot<ScoreRequest>()
        coEvery { repository.save(capture(savedReq), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(accountId, VelocityWindow.H1, "EUR") } returns
            velocityAggregate(accountId, VelocityWindow.H1, count = 7L)
        coEvery { velocityRepo.findAggregate(accountId, VelocityWindow.H24, "EUR") } returns
            velocityAggregate(accountId, VelocityWindow.H24, count = 30L)
        stubEstablishedPayee()

        service.score(req)

        assertThat(savedReq.captured.velocityH1Count).isEqualTo(7L)
        assertThat(savedReq.captured.velocityH24Count).isEqualTo(30L)
    }

    @Test
    fun `velocity counters default to zero when no aggregate exists`(): Unit = runBlocking {
        val req = request()
        val savedReq = slot<ScoreRequest>()
        coEvery { repository.save(capture(savedReq), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(any(), any(), any()) } returns null
        stubEstablishedPayee()

        service.score(req)

        assertThat(savedReq.captured.velocityH1Count).isEqualTo(0L)
        assertThat(savedReq.captured.velocityH24Count).isEqualTo(0L)
        assertThat(savedReq.captured.velocityH1TotalAmount).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `enriches request with h1 total amount from the same aggregate as the h1 count`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val req = request(accountId = accountId)
        val savedReq = slot<ScoreRequest>()
        coEvery { repository.save(capture(savedReq), any()) } returns UUID.randomUUID()
        // Kept comfortably below the EUR high-value cap (40,000) so this test isolates enrichment
        // plumbing from rule-firing behaviour (covered separately below).
        coEvery { velocityRepo.findAggregate(accountId, VelocityWindow.H1, "EUR") } returns
            velocityAggregate(accountId, VelocityWindow.H1, count = 3L, totalAmount = BigDecimal("4200.50"))
        coEvery { velocityRepo.findAggregate(accountId, VelocityWindow.H24, "EUR") } returns null
        stubEstablishedPayee()

        service.score(req)

        assertThat(savedReq.captured.velocityH1TotalAmount).isEqualByComparingTo(BigDecimal("4200.50"))
    }

    @Test
    fun `returns REVIEW when h1 high-value velocity cap is breached`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val req = request(accountId = accountId)
        coEvery { repository.save(any(), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(accountId, VelocityWindow.H1, "EUR") } returns
            velocityAggregate(accountId, VelocityWindow.H1, count = 2L, totalAmount = BigDecimal("1500000"))
        coEvery { velocityRepo.findAggregate(accountId, VelocityWindow.H24, "EUR") } returns null
        stubEstablishedPayee()

        val result = service.score(req)

        assertThat(result.verdict).isEqualTo(FraudVerdict.REVIEW)
        assertThat(result.reasons).contains("velocity-h1-amount-cap")
    }

    @Test
    fun `returns REVIEW when a single large transaction breaches the EUR amount threshold`(): Unit = runBlocking {
        val req = ScoreRequest(
            amount = BigDecimal("25000"),
            currency = "EUR",
            rail = "SEPA",
            accountId = UUID.randomUUID(),
        )
        coEvery { repository.save(any(), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(any(), any(), any()) } returns null

        val result = service.score(req)

        assertThat(result.verdict).isEqualTo(FraudVerdict.REVIEW)
        assertThat(result.reasons).contains("large-single-transaction")
    }

    @Test
    fun `returns REVIEW for a large EUR transaction that a currency-blind threshold would have missed`(): Unit =
        runBlocking {
            // Regression test for the cross-currency false-ALLOW finding from adversarial review:
            // EUR 480,000 (~CZK 12,000,000) is far below the raw CZK-calibrated figure (500,000) but
            // must still trip REVIEW once the threshold is per-currency.
            val req = ScoreRequest(
                amount = BigDecimal("480000"),
                currency = "EUR",
                rail = "SEPA",
                accountId = UUID.randomUUID(),
            )
            coEvery { repository.save(any(), any()) } returns UUID.randomUUID()
            coEvery { velocityRepo.findAggregate(any(), any(), any()) } returns null

            val result = service.score(req)

            assertThat(result.verdict).isEqualTo(FraudVerdict.REVIEW)
            assertThat(result.reasons).contains("large-single-transaction")
        }

    @Test
    fun `fails closed to REVIEW for an unmapped currency regardless of amount`(): Unit = runBlocking {
        val req = ScoreRequest(
            amount = BigDecimal("1.00"),
            currency = "USD",
            rail = "SEPA",
            accountId = UUID.randomUUID(),
        )
        coEvery { repository.save(any(), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(any(), any(), any()) } returns null

        val result = service.score(req)

        assertThat(result.verdict).isEqualTo(FraudVerdict.REVIEW)
        assertThat(result.reasons).contains(
            "large-single-transaction-unmapped-currency",
            "velocity-h1-amount-cap-unmapped-currency",
        )
    }

    @Test
    fun `returns REVIEW when h1 velocity cap is breached`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val req = request(accountId = accountId)
        coEvery { repository.save(any(), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(accountId, VelocityWindow.H1, "EUR") } returns
            velocityAggregate(accountId, VelocityWindow.H1, count = 10L)
        coEvery { velocityRepo.findAggregate(accountId, VelocityWindow.H24, "EUR") } returns null
        stubEstablishedPayee()

        val result = service.score(req)

        assertThat(result.verdict).isEqualTo(FraudVerdict.REVIEW)
    }

    @Test
    fun `skips velocity lookup when accountId is null`(): Unit = runBlocking {
        val req = ScoreRequest(
            amount = BigDecimal("50.00"),
            currency = "CZK",
            rail = "DOMESTIC",
            accountId = null,
        )
        coEvery { repository.save(any(), any()) } returns UUID.randomUUID()

        service.score(req)

        coVerify(exactly = 0) { velocityRepo.findAggregate(any(), any(), any()) }
    }

    @Test
    fun `skips payee-history lookup when counterpartyId is null`(): Unit = runBlocking {
        val req = ScoreRequest(
            amount = BigDecimal("50.00"),
            currency = "CZK",
            rail = "DOMESTIC",
            accountId = UUID.randomUUID(),
            counterpartyId = null,
        )
        coEvery { repository.save(any(), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(any(), any(), any()) } returns null

        service.score(req)

        coVerify(exactly = 0) { payeeHistoryRepo.findHistory(any(), any()) }
    }

    // ── NewPayeeHighAmountReviewRule enrichment (ADR-0084 §3 v4) ─────────────

    @Test
    fun `sets isNewPayee true when payeeHistoryRepo has no prior record for the pair`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val counterpartyId = UUID.randomUUID()
        val req = ScoreRequest(
            amount = BigDecimal("50.00"),
            currency = "EUR",
            rail = "SEPA",
            accountId = accountId,
            counterpartyId = counterpartyId,
        )
        val savedReq = slot<ScoreRequest>()
        coEvery { repository.save(capture(savedReq), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(any(), any(), any()) } returns null
        coEvery { payeeHistoryRepo.findHistory(accountId, counterpartyId.toString()) } returns null

        service.score(req)

        assertThat(savedReq.captured.isNewPayee).isTrue()
    }

    @Test
    fun `sets isNewPayee false when payeeHistoryRepo has a prior record for the pair`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val counterpartyId = UUID.randomUUID()
        val req = ScoreRequest(
            amount = BigDecimal("50.00"),
            currency = "EUR",
            rail = "SEPA",
            accountId = accountId,
            counterpartyId = counterpartyId,
        )
        val savedReq = slot<ScoreRequest>()
        coEvery { repository.save(capture(savedReq), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(any(), any(), any()) } returns null
        coEvery { payeeHistoryRepo.findHistory(accountId, counterpartyId.toString()) } returns establishedPayeeHistory()

        service.score(req)

        assertThat(savedReq.captured.isNewPayee).isFalse()
    }

    @Test
    fun `returns REVIEW for a genuinely new payee above the new-payee threshold`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val counterpartyId = UUID.randomUUID()
        val req = ScoreRequest(
            amount = BigDecimal("15000"),
            currency = "EUR",
            rail = "SEPA",
            accountId = accountId,
            counterpartyId = counterpartyId,
        )
        coEvery { repository.save(any(), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(any(), any(), any()) } returns null
        coEvery { payeeHistoryRepo.findHistory(accountId, counterpartyId.toString()) } returns null

        val result = service.score(req)

        assertThat(result.verdict).isEqualTo(FraudVerdict.REVIEW)
        assertThat(result.reasons).contains("new-payee-high-amount")
    }

    @Test
    fun `does NOT fire new-payee rule for an established payee at the same amount`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val counterpartyId = UUID.randomUUID()
        val req = ScoreRequest(
            amount = BigDecimal("15000"),
            currency = "EUR",
            rail = "SEPA",
            accountId = accountId,
            counterpartyId = counterpartyId,
        )
        coEvery { repository.save(any(), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(any(), any(), any()) } returns null
        coEvery { payeeHistoryRepo.findHistory(accountId, counterpartyId.toString()) } returns establishedPayeeHistory()

        val result = service.score(req)

        assertThat(result.reasons).doesNotContain("new-payee-high-amount")
    }
}
