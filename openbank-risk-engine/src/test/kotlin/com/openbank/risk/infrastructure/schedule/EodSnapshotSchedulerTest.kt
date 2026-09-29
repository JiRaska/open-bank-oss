// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.schedule

import com.openbank.libs.observability.DomainMetrics
import com.openbank.risk.application.port.`in`.SnapshotOutcome
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.application.port.out.SnapshotRunSummary
import com.openbank.risk.domain.model.Provenance
import com.openbank.risk.domain.model.SnapshotRun
import com.openbank.risk.domain.model.TieOutStatus
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Fast, no-Quarkus coverage of [EodSnapshotScheduler.runOnce] — the scheduler's actual business
 * logic, split out from the `@Scheduled` method precisely so it can be driven directly here. The
 * Vert.x-context / real-scheduler-dispatch half of the contract (does `@Scheduled` actually reach
 * this code at all off a bare executor thread) is covered separately by
 * `EodSnapshotSchedulerVertxContextIT` — a direct call like the ones in this file supplies a Vert.x
 * context the real scheduler does not, so it cannot stand in for that IT (#2148/#2187).
 */
class EodSnapshotSchedulerTest {

    private class FakeSnapshotUseCase : SnapshotUseCase {
        var nextReplayed: Boolean = false
        var lastAsOf: LocalDate? = null
        var lastRequestedBy: String? = null
        var calls: Int = 0

        override suspend fun listRuns(limit: Int): List<SnapshotRunSummary> = emptyList()

        override suspend fun createSnapshot(asOf: LocalDate, requestedBy: String?): SnapshotOutcome {
            calls++
            lastAsOf = asOf
            lastRequestedBy = requestedBy
            val run = SnapshotRun(
                id = UUID.randomUUID(),
                asOf = asOf,
                recordedAt = Instant.EPOCH,
                inputHash = "h",
                provenance = Provenance.SYNTHETIC,
                status = TieOutStatus.TIED_OUT,
                positionCount = 0,
                mismatches = emptyList(),
            )
            return SnapshotOutcome(run, replayed = nextReplayed)
        }

        override suspend fun getRun(id: UUID): SnapshotRun = error("not used")

        override suspend fun getPositions(id: UUID) = error("not used")

        override suspend fun getInstruments(id: UUID) = error("not used")
    }

    /** Same wiring as `DomainMetricsTest.withRegistry` — a resolvable registry, full instrumentation. */
    private fun domainMetricsOn(reg: MeterRegistry): DomainMetrics {
        val inst = mockk<Instance<MeterRegistry>>()
        every { inst.isResolvable } returns true
        every { inst.get() } returns reg
        return DomainMetrics().apply { registryInstance = inst }
    }

    private fun scheduler(
        useCase: SnapshotUseCase,
        registry: MeterRegistry = SimpleMeterRegistry(),
        clock: Clock = Clock.fixed(Instant.parse("2026-09-26T21:59:00Z"), ZoneOffset.UTC),
    ): Triple<EodSnapshotScheduler, MeterRegistry, DomainMetrics> {
        val metrics = domainMetricsOn(registry)
        val s = EodSnapshotScheduler(useCase, clock, enabled = true)
        s.domainMetrics = metrics
        s.meterRegistry = registry
        s.onStart(mockk(relaxed = true))
        return Triple(s, registry, metrics)
    }

    @Test
    fun `a fresh business day creates a snapshot and counts it as created, never replayed`() {
        val useCase = FakeSnapshotUseCase().apply { nextReplayed = false }
        val (s, registry, _) = scheduler(useCase)

        runBlocking { s.runOnce() }

        assertThat(useCase.calls).isEqualTo(1)
        assertThat(useCase.lastRequestedBy).isEqualTo("system:risk-engine-eod-snapshot")
        val created = registry.find("openbank.risk.eod_snapshot.runs").tag("outcome", "created").counter()
        val replayed = registry.find("openbank.risk.eod_snapshot.runs").tag("outcome", "replayed").counter()
        assertThat(created!!.count()).isEqualTo(1.0)
        assertThat(replayed!!.count()).isEqualTo(0.0)
    }

    @Test
    fun `a run already recorded for today is a replay — counted distinctly, not as an error`() {
        val useCase = FakeSnapshotUseCase().apply { nextReplayed = true }
        val (s, registry, _) = scheduler(useCase)

        runBlocking { s.runOnce() }

        // The no-op path must NOT share the 'created' outcome — a replay is not a fresh run, and
        // the two outcomes are separate counter series rather than one boolean (CLAUDE.md's
        // "successful no-op" / PushResult.skipped() lesson).
        val created = registry.find("openbank.risk.eod_snapshot.runs").tag("outcome", "created").counter()
        val replayed = registry.find("openbank.risk.eod_snapshot.runs").tag("outcome", "replayed").counter()
        assertThat(created!!.count()).isEqualTo(0.0)
        assertThat(replayed!!.count()).isEqualTo(1.0)
    }

    @Test
    fun `the as-of date is computed in Europe-Prague, not UTC`() {
        // 22:30 Europe/Prague on 2026-09-26 (CEST, UTC+2) is 20:30 UTC on the same calendar day —
        // but a tick a few hours later, still the same Prague business day, is already the NEXT
        // UTC day. A UTC-derived as-of would silently snapshot the wrong day near midnight.
        val useCase = FakeSnapshotUseCase()
        val lateNightPragueClock = Clock.fixed(Instant.parse("2026-09-26T22:15:00Z"), ZoneOffset.UTC)
        val (s, _, _) = scheduler(useCase, clock = lateNightPragueClock)

        runBlocking { s.runOnce() }

        assertThat(useCase.lastAsOf).isEqualTo(LocalDate.parse("2026-09-27"))
        assertThat(ZoneId.of("Europe/Prague")).isNotNull() // sanity: the zone id this test relies on
    }

    @Test
    fun `a disabled scheduler never calls the use case`() {
        val useCase = FakeSnapshotUseCase()
        val s = EodSnapshotScheduler(useCase, Clock.systemUTC(), enabled = false)
        s.domainMetrics = domainMetricsOn(SimpleMeterRegistry())
        s.meterRegistry = SimpleMeterRegistry()

        runBlocking { s.createEodSnapshot() }

        assertThat(useCase.calls).isEqualTo(0)
    }

    @Test
    fun `an enabled scheduler reaches the use case`() {
        val useCase = FakeSnapshotUseCase()
        val (s, _, _) = scheduler(useCase)

        runBlocking { s.createEodSnapshot() }

        assertThat(useCase.calls).isEqualTo(1)
    }
}
