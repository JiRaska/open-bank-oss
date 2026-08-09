// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.usecase

import com.openbank.fraud.application.port.out.FraudMetricsPort
import com.openbank.fraud.application.port.out.FraudScoreRepository
import com.openbank.fraud.application.port.out.MlModelPort
import com.openbank.fraud.application.port.out.PayeeHistoryRepository
import com.openbank.fraud.application.port.out.VelocityAggregateRepository
import com.openbank.fraud.domain.model.ScoreRequest
import com.openbank.fraud.domain.rules.FraudRuleEngine
import com.openbank.libs.domain.feature.FeatureValue
import com.openbank.libs.domain.feature.OnlineFeatureStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The zero-drift guarantee of ADR-0139 phase-1 shadow mode: whatever the feature store and the ML
 * model return — including adversarial values or exceptions — the returned [com.openbank.fraud.domain.model.FraudScore]
 * is **byte-identical** to the pure rules-only verdict. The shadow plane logs and meters; it never
 * touches the decision.
 */
class FraudScoringShadowZeroDriftTest {

    private val clock = Clock.fixed(Instant.parse("2026-06-29T10:30:00Z"), ZoneOffset.UTC)

    private fun service(
        shadowEnabled: Boolean,
        featureStore: OnlineFeatureStore,
        mlModel: MlModelPort,
    ): FraudScoringService {
        val repository = mockk<FraudScoreRepository>()
        val metrics = mockk<FraudMetricsPort>(relaxed = true)
        val velocityRepo = mockk<VelocityAggregateRepository>()
        val payeeHistoryRepo = mockk<PayeeHistoryRepository>()
        coEvery { repository.save(any(), any()) } returns UUID.randomUUID()
        coEvery { velocityRepo.findAggregate(any(), any(), any()) } returns null
        // Stubbed identically for both shadowOn/shadowOff service() calls in every test below, so
        // isNewPayee enrichment is consistent across both sides of each zero-drift comparison — this
        // suite is about shadow-plane zero-drift, not the new-payee rule itself (covered separately
        // in FraudRuleEngineTest / FraudScoringServiceTest).
        coEvery { payeeHistoryRepo.findHistory(any(), any()) } returns null
        return FraudScoringService(
            repository,
            metrics,
            velocityRepo,
            payeeHistoryRepo,
            featureStore,
            mlModel,
            clock,
            shadowEnabled,
        ).also {
            // Field-injected (LongParameterList) — see FraudScoringServiceTest for why this must
            // be set manually outside CDI.
            it.fraudHoldService = mockk(relaxed = true)
        }
    }

    private fun requests(): List<ScoreRequest> = buildList {
        val rails = listOf("SEPA", "DOMESTIC", "INSTANT")
        for (i in 0 until 40) {
            add(
                ScoreRequest(
                    amount = BigDecimal(i * 137 % 9000),
                    currency = if (i % 2 == 0) "EUR" else "CZK",
                    rail = rails[i % rails.size],
                    accountId = if (i % 5 == 0) null else UUID.nameUUIDFromBytes("acc-$i".toByteArray()),
                    velocityH1Count = (i % 15).toLong(),
                    velocityH24Count = (i % 60).toLong(),
                ),
            )
        }
    }

    @Test
    fun `shadow with adversarial feature and model values never changes the verdict`() {
        val adversarialStore = mockk<OnlineFeatureStore>()
        coEvery { adversarialStore.read(any(), any(), any()) } returns
            FeatureValue.Fresh(value = 999_999.0, asOf = Instant.parse("2026-06-29T10:29:00Z"), sourceOffset = 1)
        val adversarialModel = mockk<MlModelPort>()
        every { adversarialModel.scoreShadow(any()) } returns 0.999

        val shadowOn = service(shadowEnabled = true, adversarialStore, adversarialModel)

        runBlocking {
            for (request in requests()) {
                val enriched = if (request.accountId != null) {
                    request.copy(velocityH1Count = 0, velocityH24Count = 0)
                } else {
                    request
                }
                val expected = FraudRuleEngine.score(enriched)
                assertThat(shadowOn.score(request)).isEqualTo(expected)
            }
        }
    }

    @Test
    fun `shadow failures (store and model throwing) never change the verdict`() {
        val throwingStore = mockk<OnlineFeatureStore>()
        coEvery { throwingStore.read(any(), any(), any()) } throws IllegalStateException("redis down")
        val throwingModel = mockk<MlModelPort>()
        every { throwingModel.scoreShadow(any()) } throws IllegalStateException("model boom")

        val shadowOn = service(shadowEnabled = true, throwingStore, throwingModel)
        val shadowOff = service(shadowEnabled = false, throwingStore, throwingModel)

        runBlocking {
            for (request in requests()) {
                assertThat(shadowOn.score(request)).isEqualTo(shadowOff.score(request))
            }
        }
    }

    @Test
    fun `stale and missing features are tolerated and do not change the verdict`() {
        val store = mockk<OnlineFeatureStore>()
        coEvery { store.read(any(), any(), any()) } returnsMany listOf(
            FeatureValue.Stale(asOf = Instant.parse("2026-06-29T09:00:00Z"), sourceOffset = 1),
            FeatureValue.Missing,
        )
        val model = mockk<MlModelPort>(relaxed = true)
        every { model.scoreShadow(any()) } returns 0.5

        val shadowOn = service(shadowEnabled = true, store, model)
        val shadowOff = service(shadowEnabled = false, store, model)

        runBlocking {
            val request = ScoreRequest(
                amount = BigDecimal.TEN,
                currency = "EUR",
                rail = "SEPA",
                accountId = UUID.randomUUID(),
            )
            assertThat(shadowOn.score(request)).isEqualTo(shadowOff.score(request))
        }
    }
}
