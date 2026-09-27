// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application

import com.openbank.risk.application.port.`in`.CapitalAnalysis
import com.openbank.risk.application.port.`in`.SnapshotOutcome
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.application.port.out.FxFixingRate
import com.openbank.risk.application.port.out.FxFixingRepository
import com.openbank.risk.application.port.out.SnapshotRunSummary
import com.openbank.risk.application.usecase.CapitalService
import com.openbank.risk.domain.capital.CapitalTestParameters
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import com.openbank.risk.domain.model.Provenance
import com.openbank.risk.domain.model.SnapshotRun
import com.openbank.risk.domain.model.TieOutStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Which fixing the CZK total asks for: the one in effect at 00:00 Prague of asOf, under
 * fx-service's validity window (a Friday fixing is valid Fri 00:00 - Mon 00:00 Prague).
 */
class CapitalServiceTest {

    private val prague = ZoneId.of("Europe/Prague")
    private val runId = UUID.randomUUID()

    private fun fixing(date: String, rate: String): FxFixingRate {
        val d = LocalDate.parse(date)
        return FxFixingRate(
            "CNB", d, "EUR", "CZK", BigDecimal(rate), UUID.randomUUID(),
            d.atStartOfDay(prague).toInstant(), d.plusDays(3).atStartOfDay(prague).toInstant(), Instant.EPOCH,
        )
    }

    /** The fx-service window rule over a list, so the service's instant is what decides. */
    private class WindowRepo(val rates: List<FxFixingRate>) : FxFixingRepository {
        val asked = mutableListOf<Instant>()
        override suspend fun insertIfAbsent(rates: List<FxFixingRate>) = 0
        override suspend fun inEffect(source: String, currency: String, quoteCurrency: String, at: Instant) =
            rates.filter { it.currency == currency && !it.validFrom.isAfter(at) && it.validTo.isAfter(at) }
                .maxByOrNull { it.validFrom }
                .also { asked += at }
    }

    private class Snapshots(val asOf: LocalDate, val positions: List<Position>) : SnapshotUseCase {
        override suspend fun listRuns(limit: Int): List<SnapshotRunSummary> = emptyList()
        override suspend fun createSnapshot(asOf: LocalDate): SnapshotOutcome = error("unused")
        override suspend fun getRun(id: UUID) =
            SnapshotRun(id, asOf, Instant.EPOCH, "h", Provenance.SYNTHETIC, TieOutStatus.TIED_OUT, 1, emptyList())
        override suspend fun getPositions(id: UUID) = positions
        override suspend fun getInstruments(id: UUID): List<Instrument> = emptyList()
    }

    private val book = listOf(
        Position(PositionKind.GL_ACCOUNT, "1001", "ASSET", "CZK", null, BigDecimal("1500")),
        Position(PositionKind.GL_ACCOUNT, "1002", "ASSET", "EUR", null, BigDecimal("100")),
    )

    private fun analyse(asOf: String, repo: WindowRepo): CapitalAnalysis = runBlocking {
        CapitalService(Snapshots(LocalDate.parse(asOf), book), CapitalTestParameters.shipped(), repo).analyse(runId)
    }

    @Test
    fun `a weekend asOf takes the Friday fixing, not a later one`() {
        val repo =
            WindowRepo(
                listOf(fixing("2026-09-24", "24.10"), fixing("2026-09-25", "24.335"), fixing("2026-09-28", "25")),
            )
        val r = analyse("2026-09-27", repo).result // Sunday
        assertThat(repo.asked).containsExactly(LocalDate.parse("2026-09-27").atStartOfDay(prague).toInstant())
        assertThat(r.fxRates.single().fixingDate).isEqualTo(LocalDate.parse("2026-09-25"))
        // 1500 × 150% + 100 × 24.335 × 150% = 2250 + 3650.25
        assertThat(r.total!!.totalRwa).isEqualByComparingTo("5900.25")
    }

    @Test
    fun `a business day takes its own fixing`() {
        val r = analyse("2026-09-25", WindowRepo(listOf(fixing("2026-09-24", "24.10"), fixing("2026-09-25", "24.335"))))
        assertThat(r.result.fxRates.single().rate).isEqualByComparingTo("24.335")
    }

    @Test
    fun `no fixing in effect leaves the total unstated with the currency and the date`() {
        val r = analyse("2026-09-25", WindowRepo(listOf(fixing("2026-09-21", "24.10")))).result // expired Thu
        assertThat(r.total).isNull()
        assertThat(r.totalNotStated).contains("EUR").contains("2026-09-25")
    }
}
