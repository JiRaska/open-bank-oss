// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.usecase

import com.openbank.fraud.application.port.out.FraudMetricsPort
import com.openbank.fraud.application.port.out.FraudScoreRepository
import com.openbank.fraud.application.port.out.MlModelPort
import com.openbank.fraud.application.port.out.VelocityAggregateRepository
import com.openbank.fraud.domain.model.FraudVerdict
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
    private val featureStore = mockk<OnlineFeatureStore>(relaxed = true)
    private val mlModel = mockk<MlModelPort>(relaxed = true)

    // shadowEnabled = false: these tests assert the rules behaviour; the shadow plane is covered by
    // FraudScoringShadowZeroDriftTest. With it off, featureStore/mlModel are never touched.
    private val service =
        FraudScoringService(
            repository,
            metrics,
            velocityRepo,
            featureStore,
            mlModel,
            java.time.Clock.systemUTC(),
            false,
        )

    private fun request(accountId: UUID = UUID.randomUUID()) = ScoreRequest(
        amount = BigDecimal("99.99"),
        currency = "EUR",
        rail = "SEPA",
        accountId = accountId,
        counterpartyId = UUID.randomUUID(),
    )

    private fun velocityAggregate(accountId: UUID, window: VelocityWindow, count: Long) = VelocityAggregate(
        accountId = accountId,
        window = window,
        transactionCount = count,
        totalAmount = BigDecimal("100.00"),
        currency = "EUR",
        windowStart = Instant.now(),
    )

    @Test
    fun `scores via the rule engine and returns the baseline ALLOW verdict`(): Unit = runBlocking {
        coEvery { repository.save(any(), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(any(), any(), any()) } returns null

        val result = service.score(request())

        assertThat(result.verdict).isEqualTo(FraudVerdict.ALLOW)
        assertThat(result.ruleVersion).isEqualTo("v2")
    }

    @Test
    fun `persists every scoring decision as an audit row`(): Unit = runBlocking {
        val req = request()
        val savedReq = slot<ScoreRequest>()
        coEvery { repository.save(capture(savedReq), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(any(), any(), any()) } returns null

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

        service.score(req)

        assertThat(savedReq.captured.velocityH1Count).isEqualTo(0L)
        assertThat(savedReq.captured.velocityH24Count).isEqualTo(0L)
    }

    @Test
    fun `returns REVIEW when h1 velocity cap is breached`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val req = request(accountId = accountId)
        coEvery { repository.save(any(), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(accountId, VelocityWindow.H1, "EUR") } returns
            velocityAggregate(accountId, VelocityWindow.H1, count = 10L)
        coEvery { velocityRepo.findAggregate(accountId, VelocityWindow.H24, "EUR") } returns null

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
}
