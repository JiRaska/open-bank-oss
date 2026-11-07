// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.swift.application.port.out.SwiftBookingAgeSnapshot
import com.openbank.swift.application.port.out.SwiftBookingQuery
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SwiftBookingAgeGaugeTest {
    private val start = Instant.parse("2026-01-02T00:00:00Z")
    private val query = mockk<SwiftBookingQuery>()
    private val registry = SimpleMeterRegistry()
    private var now = start
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private fun gauge() = SwiftBookingAgeGauge(query, registry, clock, mockk<DomainMetrics>(relaxed = true))
        .also { it.register() }
    private fun age() = registry.get(SwiftBookingAgeGauge.AGE_METRIC).gauge().value()

    @Test
    fun `startup is unknown and tracked pending age remains visible alongside unknown coverage`(): Unit = runBlocking {
        val gauge = gauge()
        assertThat(age()).isNaN()
        coEvery { query.bookingAgeSnapshot() } returns SwiftBookingAgeSnapshot(null, 0)
        gauge.refresh()
        assertThat(age()).isZero()
        coEvery { query.bookingAgeSnapshot() } returns SwiftBookingAgeSnapshot(start.minusSeconds(600), 2)
        gauge.refresh()
        assertThat(age()).isEqualTo(600.0)
        assertThat(registry.get(SwiftBookingAgeGauge.UNKNOWN_METRIC).gauge().value()).isEqualTo(2.0)
        assertThat(registry.meters.all { it.id.tags.map { tag -> tag.key } == listOf("rail") }).isTrue()
    }

    @Test
    fun `failed refresh retains timestamp and age keeps growing instead of freezing`(): Unit = runBlocking {
        val gauge = gauge()
        coEvery { query.bookingAgeSnapshot() } returns SwiftBookingAgeSnapshot(start.minusSeconds(60), 0)
        gauge.refresh()
        assertThat(age()).isEqualTo(60.0)
        coEvery { query.bookingAgeSnapshot() } throws IllegalStateException("synthetic read failure")
        now = start.plusSeconds(30)
        assertThatThrownBy { runBlocking { gauge.refresh() } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(age()).isEqualTo(90.0)
        val restarted = SwiftBookingAgeGauge(query, null, clock, mockk(relaxed = true))
        restarted.register()
        // No successful query means unknown, not a process-local fabricated success time.
        assertThatThrownBy { runBlocking { restarted.refresh() } }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `unknown legacy coverage does not hide numeric tracked empty lower bound`(): Unit = runBlocking {
        val gauge = gauge()
        coEvery { query.bookingAgeSnapshot() } returns SwiftBookingAgeSnapshot(null, 3)
        gauge.refresh()
        assertThat(age()).isZero()
        assertThat(registry.get(SwiftBookingAgeGauge.UNKNOWN_METRIC).gauge().value()).isEqualTo(3.0)
    }

    @Test
    fun `future tracked timestamp clamps to zero without changing unknown coverage`(): Unit = runBlocking {
        val gauge = gauge()
        coEvery { query.bookingAgeSnapshot() } returns SwiftBookingAgeSnapshot(start.plusSeconds(60), 1)
        gauge.refresh()
        assertThat(age()).isZero()
        assertThat(registry.get(SwiftBookingAgeGauge.UNKNOWN_METRIC).gauge().value()).isEqualTo(1.0)
    }

    @Test
    fun `failed initial query preserves unknown metrics`(): Unit = runBlocking {
        val gauge = gauge()
        coEvery { query.bookingAgeSnapshot() } throws IllegalStateException("synthetic read failure")
        assertThatThrownBy { runBlocking { gauge.refresh() } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(age()).isNaN()
        assertThat(registry.get(SwiftBookingAgeGauge.UNKNOWN_METRIC).gauge().value()).isNaN()
    }
}
