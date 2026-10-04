// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.schedule

import com.openbank.fx.application.port.`in`.CnbPolicyRateUseCase
import com.openbank.fx.application.port.out.CnbPolicyRateUpsertOutcome
import com.openbank.fx.domain.cnb.CnbPolicyInstrument
import com.openbank.fx.domain.cnb.CnbPolicyRateUpsert
import com.openbank.fx.infrastructure.observability.CnbPolicyRateMetrics
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The heartbeat is asserted through `openbank_workflow_success_recorded` (0/1), never through the
 * age gauge's magnitude: the age is seeded at registration (#4208), so "small" no longer separates
 * a run that succeeded from one that never did.
 */
class CnbPolicyRateIngestionSchedulerTest {

    private val registry = SimpleMeterRegistry()
    private val instance = mockk<Instance<MeterRegistry>>().also {
        every { it.isResolvable } returns true
        every { it.get() } returns registry
    }
    private val domainMetrics = DomainMetrics().apply { registryInstance = instance }
    private val rowMetrics = CnbPolicyRateMetrics().apply { registryInstance = instance }
    private val useCase: CnbPolicyRateUseCase = mockk()
    private val scheduler = CnbPolicyRateIngestionScheduler(useCase, domainMetrics, rowMetrics).also {
        it.onStart(mockk(relaxed = true))
    }

    private fun successRecorded(workflow: String): Double = registry.get(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, workflow).gauge().value()

    private fun minReserves() = mapOf(
        CnbPolicyInstrument.MIN_RESERVE_RATIO to outcome(),
        CnbPolicyInstrument.MIN_RESERVE_REMUNERATION to outcome(),
    )

    private fun outcome(revised: Int = 0) =
        CnbPolicyRateUpsertOutcome(CnbPolicyRateUpsert(1, 2, revised), emptyList(), 1 + revised)

    @Test
    fun `nothing is recorded as a success before a run`() {
        assertThat(successRecorded(CnbPolicyRateIngestionScheduler.WORKFLOW_NAME)).isZero()
    }

    @Test
    fun `a run where every feed succeeds records the heartbeat and each feed's freshness`(): Unit = runBlocking {
        coEvery { useCase.ingest(any()) } returns outcome(revised = 1)
        coEvery { useCase.ingestMinimumReserves() } returns minReserves()

        scheduler.ingestPolicyRates()

        CnbPolicyInstrument.FEED_BACKED.forEach { coVerify(exactly = 1) { useCase.ingest(it) } }
        assertThat(successRecorded(CnbPolicyRateIngestionScheduler.WORKFLOW_NAME)).isEqualTo(1.0)
        (CnbPolicyRateIngestionScheduler.FEED_NAMES.values + CnbPolicyRateIngestionScheduler.FEED_MIN_RESERVES).forEach {
            assertThat(successRecorded("feed-$it")).describedAs(it).isEqualTo(1.0)
        }
        assertThat(
            registry.get("openbank.fx.cnb.policy.rate.rows")
                .tags("instrument", "REPO_2W", "outcome", "revised").counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `one dead feed does not stop the others, and the heartbeat does not advance`(): Unit = runBlocking {
        coEvery { useCase.ingest(CnbPolicyInstrument.REPO_2W) } throws IOException("connection reset")
        coEvery { useCase.ingest(CnbPolicyInstrument.DISCOUNT) } returns outcome()
        coEvery { useCase.ingest(CnbPolicyInstrument.LOMBARD) } returns outcome()
        coEvery { useCase.ingestMinimumReserves() } returns minReserves()

        scheduler.ingestPolicyRates() // must not throw

        coVerify(exactly = 1) { useCase.ingest(CnbPolicyInstrument.LOMBARD) }
        assertThat(successRecorded(CnbPolicyRateIngestionScheduler.WORKFLOW_NAME)).isZero()
        assertThat(successRecorded("feed-${CnbPolicyRateIngestionScheduler.FEED_REPO}")).isZero()
        assertThat(successRecorded("feed-${CnbPolicyRateIngestionScheduler.FEED_LOMBARD}")).isEqualTo(1.0)
        assertThat(
            registry.get(
                "openbank.feed.fetch",
            ).tags("feed", CnbPolicyRateIngestionScheduler.FEED_REPO, "outcome", "unreachable")
                .counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `a malformed feed is classified as a parse error`(): Unit = runBlocking {
        coEvery { useCase.ingest(any()) } throws IllegalArgumentException("2 malformed row(s)")
        coEvery { useCase.ingestMinimumReserves() } returns minReserves()

        scheduler.ingestPolicyRates()

        assertThat(
            registry.get(
                "openbank.feed.fetch",
            ).tags("feed", CnbPolicyRateIngestionScheduler.FEED_DISCOUNT, "outcome", "parse_error")
                .counter().count(),
        ).isEqualTo(1.0)
        assertThat(successRecorded(CnbPolicyRateIngestionScheduler.WORKFLOW_NAME)).isZero()
    }

    @Test
    fun `a failed workbook is its own feed outcome and holds the heartbeat, the rate feeds still advance`(): Unit =
        runBlocking {
            coEvery { useCase.ingest(any()) } returns outcome()
            coEvery { useCase.ingestMinimumReserves() } throws IllegalArgumentException("not a single rate")

            scheduler.ingestPolicyRates()

            assertThat(successRecorded(CnbPolicyRateIngestionScheduler.WORKFLOW_NAME)).isZero()
            assertThat(successRecorded("feed-${CnbPolicyRateIngestionScheduler.FEED_MIN_RESERVES}")).isZero()
            assertThat(successRecorded("feed-${CnbPolicyRateIngestionScheduler.FEED_REPO}")).isEqualTo(1.0)
            assertThat(
                registry.get("openbank.feed.fetch")
                    .tags("feed", CnbPolicyRateIngestionScheduler.FEED_MIN_RESERVES, "outcome", "parse_error")
                    .counter().count(),
            ).isEqualTo(1.0)
        }
}
