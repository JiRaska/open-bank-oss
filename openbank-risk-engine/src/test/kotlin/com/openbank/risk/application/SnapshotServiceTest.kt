// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application

import com.openbank.risk.application.port.out.LedgerPort
import com.openbank.risk.application.port.out.SnapshotNotFoundException
import com.openbank.risk.application.port.out.SnapshotRepository
import com.openbank.risk.application.port.out.UntiedSnapshotException
import com.openbank.risk.application.usecase.SnapshotService
import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.Fixtures.BOB
import com.openbank.risk.domain.Fixtures.sl
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.LedgerInputs
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.Provenance
import com.openbank.risk.domain.model.SnapshotRun
import com.openbank.risk.domain.model.TieOutStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class SnapshotServiceTest {

    private class FakeLedger(var inputs: LedgerInputs) : LedgerPort {
        override suspend fun read(asOf: LocalDate): LedgerInputs = inputs
    }

    private class InMemoryRepository : SnapshotRepository {
        val runs = mutableListOf<SnapshotRun>()
        val positions = mutableMapOf<UUID, List<Position>>()

        override suspend fun findByNaturalKey(asOf: LocalDate, inputHash: String) =
            runs.firstOrNull { it.asOf == asOf && it.inputHash == inputHash }

        override suspend fun findById(id: UUID) = runs.firstOrNull { it.id == id }

        val instruments = mutableMapOf<UUID, List<Instrument>>()

        override suspend fun saveIfAbsent(
            run: SnapshotRun,
            positions: List<Position>,
            instruments: List<Instrument>,
        ): SnapshotRun {
            findByNaturalKey(run.asOf, run.inputHash)?.let { return it }
            runs += run
            this.positions[run.id] = positions
            this.instruments[run.id] = instruments
            return run
        }

        override suspend fun findPositions(runId: UUID) = positions[runId].orEmpty()

        override suspend fun findInstruments(runId: UUID) = instruments[runId].orEmpty()
    }

    private val clock = Clock.fixed(Instant.parse("2026-10-01T06:00:00Z"), ZoneOffset.UTC)
    private val ledger = FakeLedger(Fixtures.tiedOut())
    private val repository = InMemoryRepository()
    private val service = SnapshotService(ledger, repository, clock, Provenance.SYNTHETIC)

    @Test
    fun `a tied-out run is stored with its manifest and serves its positions`(): Unit = runBlocking {
        val outcome = service.createSnapshot(Fixtures.AS_OF)

        assertThat(outcome.replayed).isFalse()
        val run = outcome.run
        assertThat(run.status).isEqualTo(TieOutStatus.TIED_OUT)
        assertThat(run.recordedAt).isEqualTo(clock.instant())
        assertThat(run.provenance).isEqualTo(Provenance.SYNTHETIC)
        assertThat(run.inputHash).hasSize(64)
        assertThat(run.positionCount).isEqualTo(3)
        assertThat(service.getPositions(run.id)).hasSize(3)
    }

    @Test
    fun `the same as-of and the same ledger returns the existing run`(): Unit = runBlocking {
        val first = service.createSnapshot(Fixtures.AS_OF)
        val second = service.createSnapshot(Fixtures.AS_OF)

        assertThat(second.replayed).isTrue()
        assertThat(second.run.id).isEqualTo(first.run.id)
        assertThat(repository.runs).hasSize(1)
    }

    @Test
    fun `the same as-of with a changed ledger is a new run`(): Unit = runBlocking {
        val first = service.createSnapshot(Fixtures.AS_OF)
        ledger.inputs = Fixtures.tiedOut().copy(subLedger = Fixtures.tiedOut().subLedger + sl(BOB, "USD", "0", "1"))

        val second = service.createSnapshot(Fixtures.AS_OF)

        assertThat(second.replayed).isFalse()
        assertThat(second.run.id).isNotEqualTo(first.run.id)
        assertThat(second.run.inputHash).isNotEqualTo(first.run.inputHash)
        assertThat(repository.runs).hasSize(2)
    }

    @Test
    fun `an untied run is stored but its positions are refused with the mismatches`(): Unit = runBlocking {
        ledger.inputs = Fixtures.tiedOut().copy(subLedger = Fixtures.tiedOut().subLedger.dropLast(1))

        val run = service.createSnapshot(Fixtures.AS_OF).run

        assertThat(run.status).isEqualTo(TieOutStatus.UNTIED)
        assertThat(repository.runs).hasSize(1)
        assertThatThrownBy { runBlocking { service.getPositions(run.id) } }
            .isInstanceOf(UntiedSnapshotException::class.java)
            .extracting { (it as UntiedSnapshotException).mismatches.single().glAccountCode }
            .isEqualTo("2100")
    }

    @Test
    fun `an unknown run is not found`() {
        assertThatThrownBy { runBlocking { service.getRun(UUID.randomUUID()) } }
            .isInstanceOf(SnapshotNotFoundException::class.java)
    }

    @Test
    fun `provenance accepts only the two ADR-0313 values`() {
        assertThat(Provenance.parse("production")).isEqualTo(Provenance.PRODUCTION)
        assertThatThrownBy { Provenance.parse("prod") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
