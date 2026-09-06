// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.observability

import com.openbank.kyc.application.OrphanedPartyDetector
import com.openbank.kyc.application.OrphanedPartyReport
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The properties this control depends on, none of which any other test asserts: a failed pass
 * must NOT publish "no orphans" (an unreachable party-service would clear a firing alert), the
 * denominator must be published so a scan of zero parties is distinguishable from a clean
 * register, and a cold pod must read 0 rather than a sentinel that fires the alert at boot.
 */
class OrphanedPartyGaugeTest {

    private val now = Instant.parse("2026-03-01T00:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val detector = mockk<OrphanedPartyDetector>()

    private fun report(orphans: List<UUID>, oldest: Instant?, scanned: Long) =
        OrphanedPartyReport(
            orphanedPartyIds = orphans,
            oldestOrphanCreatedAt = oldest,
            partiesScanned = scanned,
            checkedAt = now,
        )

    private fun metricsOver(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun gauge(registry: MeterRegistry, name: String): Double? =
        registry.find(name).tag("service", "kyc").gauge()?.value()

    private fun successRecorded(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "kyc-orphaned-party-detection")
        .gauge()?.value()

    private fun gaugeOver(registry: MeterRegistry, enabled: Boolean = true) =
        OrphanedPartyGauge(detector, registry, clock, metricsOver(registry), enabled)
            .also { it.register() }

    @Test
    fun `every gauge reads zero before the first pass, so a cold pod cannot fire the alert`() {
        val registry = SimpleMeterRegistry()
        gaugeOver(registry)

        assertThat(gauge(registry, "openbank.kyc.orphaned.parties")).isEqualTo(0.0)
        assertThat(gauge(registry, "openbank.kyc.orphan.detection.parties.scanned")).isEqualTo(0.0)
        assertThat(gauge(registry, "openbank.kyc.orphaned.parties.oldest.age.seconds")).isEqualTo(0.0)
    }

    @Test
    fun `a successful pass publishes the orphan count, the denominator and the oldest age`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val g = gaugeOver(registry)
        coEvery { detector.detect() } returns report(
            orphans = listOf(UUID.randomUUID(), UUID.randomUUID()),
            oldest = now.minusSeconds(7200),
            scanned = 73,
        )

        g.refresh()

        assertThat(gauge(registry, "openbank.kyc.orphaned.parties")).isEqualTo(2.0)
        assertThat(gauge(registry, "openbank.kyc.orphan.detection.parties.scanned")).isEqualTo(73.0)
        assertThat(gauge(registry, "openbank.kyc.orphaned.parties.oldest.age.seconds")).isEqualTo(7200.0)
    }

    @Test
    fun `a clean pass publishes a scanned denominator, so zero orphans is not zero work`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val g = gaugeOver(registry)
        coEvery { detector.detect() } returns report(orphans = emptyList(), oldest = null, scanned = 73)

        g.refresh()

        assertThat(gauge(registry, "openbank.kyc.orphaned.parties")).isEqualTo(0.0)
        assertThat(gauge(registry, "openbank.kyc.orphan.detection.parties.scanned")).isEqualTo(73.0)
        assertThat(gauge(registry, "openbank.kyc.orphaned.parties.oldest.age.seconds")).isEqualTo(0.0)
    }

    @Test
    fun `an oldest timestamp in the future clamps the age to zero instead of going negative`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val g = gaugeOver(registry)
        coEvery { detector.detect() } returns report(
            orphans = listOf(UUID.randomUUID()),
            oldest = now.plusSeconds(600),
            scanned = 1,
        )

        g.refresh()

        assertThat(gauge(registry, "openbank.kyc.orphaned.parties.oldest.age.seconds")).isEqualTo(0.0)
    }

    @Test
    fun `a failed pass keeps the previous values and does not record a liveness success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val g = gaugeOver(registry)
        g.onStart(StartupEvent())
        coEvery { detector.detect() } returns report(
            orphans = listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
            oldest = now.minusSeconds(60),
            scanned = 50,
        )
        g.refresh()
        assertThat(successRecorded(registry)).isEqualTo(1.0)

        coEvery { detector.detect() } throws IllegalStateException("party-service unreachable")
        g.refresh()

        // NOT reset to 0 — an unreachable party-service must never publish "no orphans".
        assertThat(gauge(registry, "openbank.kyc.orphaned.parties")).isEqualTo(3.0)
        assertThat(gauge(registry, "openbank.kyc.orphan.detection.parties.scanned")).isEqualTo(50.0)
        assertThat(successRecorded(registry)).isEqualTo(1.0)
    }

    @Test
    fun `a disabled job never calls the detector and records no liveness success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val g = gaugeOver(registry, enabled = false)
        g.onStart(StartupEvent())

        g.refresh()

        coVerify(exactly = 0) { detector.detect() }
        assertThat(successRecorded(registry)).isEqualTo(0.0)
    }

    @Test
    fun `a disabled job still registers the liveness heartbeat`() {
        val registry = SimpleMeterRegistry()
        gaugeOver(registry, enabled = false).onStart(StartupEvent())

        // A disabled control that publishes no heartbeat is indistinguishable from a broken one.
        assertThat(successRecorded(registry)).isNotNull()
    }

    @Test
    fun `an absent meter registry leaves refresh working rather than throwing`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val g = OrphanedPartyGauge(detector, null, clock, metricsOver(registry), true).also { it.register() }
        g.onStart(StartupEvent())
        coEvery { detector.detect() } returns report(listOf(UUID.randomUUID()), now.minusSeconds(5), 9)

        g.refresh()

        assertThat(successRecorded(registry)).isEqualTo(1.0)
    }
}
