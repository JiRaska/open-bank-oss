// SPDX-License-Identifier: Apache-2.0
package com.openbank.libs.persistence.outbox

import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AbstractOutboxOldestAgeGaugeTest {
    private class TestGauge(metrics: DomainMetrics, override val repository: OutboxRepositoryV2) :
        AbstractOutboxOldestAgeGauge(metrics) {
        override val service = "test-service"
        fun register() = registerOldestAgeGauge()
        suspend fun refresh(now: Instant) = refreshOldestAge(now)
    }

    @Test
    fun `cold gauge is zero without querying the repository`() {
        val metrics = mockk<DomainMetrics>()
        val repository = mockk<OutboxRepositoryV2>()
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxOldestAge("test-service", capture(supplier)) } returns Unit
        val gauge = TestGauge(metrics, repository)

        gauge.register()

        verify(exactly = 1) { metrics.registerOutboxOldestAge("test-service", any()) }
        assertThat(gauge.currentAgeSeconds()).isZero()
        assertThat(supplier.captured().toLong()).isZero()
        coVerify(exactly = 0) { repository.oldestProcessableAge(any()) }
    }

    @Test
    fun `refresh samples exact time and registered supplier follows cache including empty outbox`() {
        val metrics = mockk<DomainMetrics>()
        val repository = mockk<OutboxRepositoryV2>()
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxOldestAge("test-service", capture(supplier)) } returns Unit
        val now = Instant.parse("2026-10-01T12:00:00Z")
        coEvery { repository.oldestProcessableAge(now) } returns Duration.ofMillis(19500)
        coEvery { repository.oldestProcessableAge(now.plusSeconds(1)) } returns Duration.ofSeconds(7)
        coEvery { repository.oldestProcessableAge(now.plusSeconds(2)) } returns null
        val gauge = TestGauge(metrics, repository)
        gauge.register()

        runBlocking { gauge.refresh(now) }
        assertThat(gauge.currentAgeSeconds()).isEqualTo(19)
        assertThat(supplier.captured().toLong()).isEqualTo(19)
        runBlocking { gauge.refresh(now.plusSeconds(1)) }
        assertThat(gauge.currentAgeSeconds()).isEqualTo(7)
        assertThat(supplier.captured().toLong()).isEqualTo(7)
        runBlocking { gauge.refresh(now.plusSeconds(2)) }
        assertThat(gauge.currentAgeSeconds()).isZero()
        assertThat(supplier.captured().toLong()).isZero()
        coVerify(exactly = 1) { repository.oldestProcessableAge(now) }
        coVerify(exactly = 1) { repository.oldestProcessableAge(now.plusSeconds(1)) }
        coVerify(exactly = 1) { repository.oldestProcessableAge(now.plusSeconds(2)) }
    }
}
