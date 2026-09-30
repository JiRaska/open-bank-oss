// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonMapperBuilder
import com.openbank.risk.application.port.out.LedgerPort
import com.openbank.risk.application.port.out.LendingPort
import com.openbank.risk.application.port.out.SnapshotRepository
import com.openbank.risk.application.port.out.SnapshotRunSummary
import com.openbank.risk.application.usecase.SnapshotService
import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.Fixtures.lendingLoan
import com.openbank.risk.domain.irrbb.IrrbbParameters
import com.openbank.risk.domain.irrbb.PostShockFloor
import com.openbank.risk.domain.irrbb.ShockSizes
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.LedgerInputs
import com.openbank.risk.domain.model.LoanContract
import com.openbank.risk.domain.model.ModelVersions
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.Provenance
import com.openbank.risk.domain.model.SnapshotRun
import com.openbank.risk.domain.model.TieOutStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR-0314 D2: rerunning the same manifest yields byte-identical results, and the manifest names
 * the versions it ran under.
 *
 * Each run is built by a FRESH service over a FRESH store, so the second build really recomputes
 * everything instead of replaying the first run by its natural key.
 */
class SnapshotReproducibilityTest {

    private class FixedLedger(private val inputs: LedgerInputs) : LedgerPort {
        override suspend fun read(asOf: LocalDate): LedgerInputs = inputs
    }

    private class FixedLending(private val loans: List<LoanContract>) : LendingPort {
        override suspend fun readLoanBook(asOf: LocalDate): List<LoanContract> = loans
    }

    private class Store : SnapshotRepository {
        val runs = mutableListOf<SnapshotRun>()
        val positions = mutableMapOf<UUID, List<Position>>()
        val instruments = mutableMapOf<UUID, List<Instrument>>()

        override suspend fun findByNaturalKey(asOf: LocalDate, inputHash: String) =
            runs.firstOrNull { it.asOf == asOf && it.inputHash == inputHash }

        override suspend fun findById(id: UUID) = runs.firstOrNull { it.id == id }

        override suspend fun saveIfAbsent(run: SnapshotRun, positions: List<Position>, instruments: List<Instrument>) =
            findByNaturalKey(run.asOf, run.inputHash) ?: run.also {
                runs += it
                this.positions[it.id] = positions
                this.instruments[it.id] = instruments
            }

        override suspend fun findPositions(runId: UUID) = positions[runId].orEmpty()

        override suspend fun findInstruments(runId: UUID) = instruments[runId].orEmpty()

        override suspend fun listRecent(limit: Int) = emptyList<SnapshotRunSummary>()
    }

    private val mapper = jacksonMapperBuilder()
        .addModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .build()

    private val clock = Clock.fixed(Instant.parse("2026-10-01T06:00:00Z"), ZoneOffset.UTC)
    private val loanA = lendingLoan(id = Fixtures.LOAN_A)
    private val loanB = lendingLoan(id = Fixtures.LOAN_B, principal = "24000.00", paid = 3)
    private val loans = listOf(loanA, loanB)

    private val versions = ModelVersions(
        engineVersion = "0.21.0",
        capitalSetId = "eu-crr3-sa",
        capitalSetVersion = "2025.1",
        liquiditySetId = "eu-2015-61",
        liquiditySetVersion = "2025.1",
        irrbbShockSetVersion = ModelVersions.irrbbFingerprint(irrbb("200/250/100")),
        irrbbShockSource = "BCBS d368 Annex 2",
        minReservesSetId = "cnb-min-reserves",
        minReservesSetVersion = "2025.1",
        behaviouralModelId = "nmd-linear-core",
        behaviouralModelVersion = "1.0.0",
    )

    private fun irrbb(eur: String, floor: String? = null) = IrrbbParameters(
        shockSizes = mapOf("EUR" to ShockSizes.parse(eur)),
        shockSource = "BCBS d368 Annex 2",
        floor = floor?.let(PostShockFloor::parse),
        floorSource = "test",
    )

    private data class Built(val run: SnapshotRun, val positions: String, val instruments: String)

    private fun build(v: ModelVersions?, ledgerInputs: LedgerInputs = inputs()): Built = runBlocking {
        val store = Store()
        val service = SnapshotService(
            FixedLedger(ledgerInputs),
            store,
            clock,
            Provenance.SYNTHETIC,
            lending = FixedLending(loans),
            modelVersions = v,
        )
        val run = service.createSnapshot(Fixtures.AS_OF).run
        assertThat(run.status).isEqualTo(TieOutStatus.TIED_OUT)
        Built(
            run,
            mapper.writeValueAsString(service.getPositions(run.id)),
            mapper.writeValueAsString(service.getInstruments(run.id)),
        )
    }

    private fun inputs(): LedgerInputs = Fixtures.tiedOutWithLoans(
        loans.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.outstandingPrincipal) },
    )

    @Test
    fun `the same inputs build byte-identical positions and instruments and the same input hash`() {
        val first = build(versions)
        val second = build(versions)

        assertThat(first.positions).isNotBlank().isEqualTo(second.positions)
        assertThat(first.instruments).contains(Fixtures.LOAN_A.toString()).isEqualTo(second.instruments)
        assertThat(first.run.inputHash).isEqualTo(second.run.inputHash)
        assertThat(first.run.modelVersions).isEqualTo(versions).isEqualTo(second.run.modelVersions)
        assertThat(first.run.ledgerCutOff).isEqualTo(clock.instant())
    }

    @Test
    fun `a changed parameter-set version changes the manifest but not the positions or the input hash`() {
        val before = build(versions)
        val after = build(versions.copy(capitalSetVersion = "2026.1"))

        assertThat(after.run.modelVersions).isNotEqualTo(before.run.modelVersions)
        assertThat(after.run.modelVersions?.capitalSetVersion).isEqualTo("2026.1")
        assertThat(after.positions).isEqualTo(before.positions)
        assertThat(after.instruments).isEqualTo(before.instruments)
        assertThat(after.run.inputHash).isEqualTo(before.run.inputHash)
    }

    /** Negative control: the comparison above CAN see a difference — a changed ledger changes both. */
    @Test
    fun `a changed ledger changes the positions and the input hash`() {
        val base = build(versions)
        val moved = inputs().let { i ->
            i.copy(
                trialBalance = i.trialBalance + Fixtures.tb("1001", "ASSET", "EUR", "1", "0") +
                    Fixtures.tb("3000", "EQUITY", "EUR", "0", "1"),
            )
        }
        val changed = build(versions, moved)

        assertThat(changed.positions).isNotEqualTo(base.positions)
        assertThat(changed.run.inputHash).isNotEqualTo(base.run.inputHash)
    }

    @Test
    fun `the IRRBB fingerprint is stable under formatting and moves with any shock or floor change`() {
        val base = ModelVersions.irrbbFingerprint(irrbb("200/250/100"))

        assertThat(ModelVersions.irrbbFingerprint(irrbb("200.0/250.00/100"))).isEqualTo(base)
        assertThat(base).startsWith("sha256:").hasSize("sha256:".length + 16)
        assertThat(ModelVersions.irrbbFingerprint(irrbb("200/250/101"))).isNotEqualTo(base)
        assertThat(ModelVersions.irrbbFingerprint(irrbb("200/250/100", floor = "-150/3"))).isNotEqualTo(base)
    }

    @Test
    fun `a run built without versions records none, as a historic run reads back`() {
        assertThat(build(null).run.modelVersions).isNull()
    }
}
