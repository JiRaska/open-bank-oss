// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.schedule

import com.openbank.libs.observability.DomainMetrics
import com.openbank.risk.application.port.`in`.LimitAnalysis
import com.openbank.risk.application.port.`in`.LimitUseCase
import com.openbank.risk.application.port.`in`.SnapshotOutcome
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.application.port.out.LimitEventOutbox
import com.openbank.risk.application.port.out.SnapshotRunSummary
import com.openbank.risk.domain.limits.LimitDefinition
import com.openbank.risk.domain.limits.LimitEvaluation
import com.openbank.risk.domain.limits.LimitMetric
import com.openbank.risk.domain.limits.LimitSet
import com.openbank.risk.domain.limits.LimitStatus
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
import java.math.BigDecimal
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
        var nextStatus: TieOutStatus = TieOutStatus.TIED_OUT
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
                status = nextStatus,
                positionCount = 0,
                mismatches = emptyList(),
            )
            return SnapshotOutcome(run, replayed = nextReplayed)
        }

        override suspend fun getRun(id: UUID): SnapshotRun = error("not used")

        override suspend fun getPositions(id: UUID) = error("not used")

        override suspend fun getInstruments(id: UUID) = error("not used")
    }

    private class FakeLimits : LimitUseCase {
        val evaluated = mutableListOf<UUID>()
        private val set = LimitSet(
            "test",
            "7",
            "test",
            listOf(
                LimitDefinition("lcr-min", LimitMetric.LCR, BigDecimal.ONE, BigDecimal("1.1"), "c"),
                LimitDefinition("nsfr-min", LimitMetric.NSFR, BigDecimal.ONE, BigDecimal("1.05"), "c"),
            ),
        )

        override suspend fun evaluate(runId: UUID): LimitAnalysis {
            evaluated += runId
            val run = SnapshotRun(
                runId,
                LocalDate.parse("2026-09-26"),
                Instant.parse("2026-09-26T20:30:00Z"),
                "h",
                Provenance.SYNTHETIC,
                TieOutStatus.TIED_OUT,
                0,
                emptyList(),
            )
            return LimitAnalysis(
                run,
                set,
                listOf(
                    LimitEvaluation(set.limits[0], LimitStatus.BREACH, BigDecimal("0.9"), "b"),
                    LimitEvaluation(set.limits[1], LimitStatus.NOT_EVALUABLE, null, "gap"),
                ),
                null,
            )
        }
    }

    private class FakeOutbox : LimitEventOutbox {
        val recorded = mutableListOf<LimitAnalysis>()

        override suspend fun recordNonOk(analysis: LimitAnalysis, occurredAt: Instant): Int {
            recorded += analysis
            return 1
        }
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
        limits: LimitUseCase = FakeLimits(),
        outbox: LimitEventOutbox = FakeOutbox(),
    ): Triple<EodSnapshotScheduler, MeterRegistry, DomainMetrics> {
        val metrics = domainMetricsOn(registry)
        val s = EodSnapshotScheduler(useCase, clock, enabled = true, limits = limits, limitOutbox = outbox)
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
        val s = EodSnapshotScheduler(useCase, Clock.systemUTC(), enabled = false, FakeLimits(), FakeOutbox())
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

    @Test
    fun `a tied-out run has its limits evaluated, counted per status, and its non-OK limits recorded`() {
        val useCase = FakeSnapshotUseCase()
        val limits = FakeLimits()
        val outbox = FakeOutbox()
        val (s, registry, _) = scheduler(useCase, limits = limits, outbox = outbox)

        runBlocking { s.runOnce() }

        assertThat(limits.evaluated).hasSize(1)
        assertThat(outbox.recorded).hasSize(1)
        val evaluations = "openbank.risk.limit.evaluations"
        assertThat(
            registry.find(evaluations).tags("limit", "lcr-min", "status", "BREACH").counter()!!.count(),
        ).isEqualTo(1.0)
        // NOT_EVALUABLE is its own series, never folded into OK.
        assertThat(registry.find(evaluations).tags("limit", "nsfr-min", "status", "NOT_EVALUABLE").counter()!!.count())
            .isEqualTo(1.0)
        assertThat(registry.find(evaluations).tag("status", "OK").counter()).isNull()
    }

    @Test
    fun `a replayed run is re-evaluated too - the outbox is idempotent, and a lost write repairs itself`() {
        val useCase = FakeSnapshotUseCase().apply { nextReplayed = true }
        val outbox = FakeOutbox()
        val (s, _, _) = scheduler(useCase, outbox = outbox)

        runBlocking { s.runOnce() }

        assertThat(outbox.recorded).hasSize(1)
    }

    @Test
    fun `an untied run is never evaluated and records nothing`() {
        val useCase = FakeSnapshotUseCase().apply { nextStatus = TieOutStatus.UNTIED }
        val limits = FakeLimits()
        val outbox = FakeOutbox()
        val (s, registry, _) = scheduler(useCase, limits = limits, outbox = outbox)

        runBlocking { s.runOnce() }

        assertThat(limits.evaluated).isEmpty()
        assertThat(outbox.recorded).isEmpty()
        assertThat(registry.find("openbank.risk.limit.evaluations").counter()).isNull()
    }
}
