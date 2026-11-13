// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.application.usecase

import com.openbank.devops.application.port.incoming.GetDeliveryOverviewUseCase
import com.openbank.devops.application.port.out.DeliveryHistoryRepository
import com.openbank.devops.domain.model.DeliveryEvidenceState
import com.openbank.devops.domain.model.DeliveryObservation
import com.openbank.devops.domain.model.DeliveryOverview
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DeliveryHistoryServiceTest {
    private val now = Instant.parse("2026-01-02T12:03:14Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val overview = mockk<GetDeliveryOverviewUseCase>()
    private val repository = mockk<DeliveryHistoryRepository>()
    private fun service(enabled: Boolean) = DeliveryHistoryService(
        overview,
        repository,
        clock,
        "1.2.3",
        enabled,
        "0 */5 * * * ?",
    )

    private val sample = DeliveryOverview(
        now, now.minusSeconds(86400), now, "example/repo",
        DeliveryEvidenceState.UNAVAILABLE, "SOURCE_UNAVAILABLE", 0, emptyList(),
        DeliveryEvidenceState.PARTIAL, "PAGE_LIMIT", 5, 3, null,
    )

    @Test
    fun `disabled capture performs no upstream or database calls`(): Unit = runBlocking {
        service(false).capture()
        coVerify(exactly = 0) { overview.getOverview() }
        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `capture preserves original incomplete sample and derives its slot`(): Unit = runBlocking {
        coEvery { overview.getOverview() } returns sample
        val saved = slot<DeliveryObservation>()
        coEvery { repository.save(capture(saved)) } returns Unit
        service(true).capture()
        assertThat(saved.captured.slotStart).isEqualTo(Instant.parse("2026-01-02T12:00:00Z"))
        assertThat(saved.captured.overview).isEqualTo(sample)
        assertThat(saved.captured.recordedAt).isEqualTo(now)
        assertThat(saved.captured.collectorVersion).isEqualTo("1.2.3")
    }

    @Test
    fun `read is bounded and does not refresh data while disabled`(): Unit = runBlocking {
        coEvery { repository.findRecent(1) } returns emptyList()
        val result = service(false).getHistory(1)
        assertThat(result.samplingEnabled).isFalse()
        assertThat(result.observations).isEmpty()
        coVerify(exactly = 0) { overview.getOverview() }
        for (limit in listOf(0, -1, 289)) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service(true).getHistory(limit) } }
        }
    }
}
