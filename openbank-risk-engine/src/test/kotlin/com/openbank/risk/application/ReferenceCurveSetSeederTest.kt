// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application

import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.application.port.out.CurveSetRepository
import com.openbank.risk.application.port.out.SnapshotRunSummary
import com.openbank.risk.application.usecase.ReferenceCurveSetSeeder
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.curve.ReferenceCurveSet
import com.openbank.risk.domain.model.Provenance
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class ReferenceCurveSetSeederTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-01T06:00:00Z"), ZoneOffset.UTC)
    private val d1 = LocalDate.parse("2026-09-29")
    private val d2 = LocalDate.parse("2026-09-30")

    private fun run(asOf: LocalDate) = SnapshotRunSummary(
        id = UUID.randomUUID(),
        asOf = asOf,
        recordedAt = Instant.EPOCH,
        provenance = "synthetic",
        status = "TIED_OUT",
        positionCount = 1,
        mismatchCount = 0,
        requestedBy = null,
    )

    /** An in-memory repository keyed by id, with the same insert-if-absent semantics as Postgres. */
    private val stored = mutableMapOf<UUID, CurveSet>()
    private val repository = mockk<CurveSetRepository>().also { repo ->
        coEvery { repo.saveIfAbsent(any(), any()) } answers {
            val set = firstArg<CurveSet>()
            stored.putIfAbsent(set.id, set) == null
        }
    }
    private val snapshots = mockk<SnapshotUseCase>().also {
        // Two runs on one date, one on another: one set per DATE, not per run.
        coEvery { it.listRuns(any()) } returns listOf(run(d2), run(d2), run(d1))
    }

    @Test
    fun `seeds one reference set per run date and a second pass adds nothing`(): Unit = runBlocking {
        val seeder = ReferenceCurveSetSeeder(repository, snapshots, Provenance.SYNTHETIC, clock)

        val first = seeder.seedRecentRunDates()
        val second = seeder.seedRecentRunDates()

        assertThat(first.created).containsExactly(d1, d2)
        assertThat(first.alreadyPresent).isZero()
        assertThat(second.created).isEmpty()
        assertThat(second.alreadyPresent).isEqualTo(2)
        assertThat(stored.keys).containsExactlyInAnyOrder(ReferenceCurveSet.idFor(d1), ReferenceCurveSet.idFor(d2))
        assertThat(stored.values).allSatisfy { assertThat(it.provenance).isEqualTo(Provenance.SYNTHETIC) }
    }

    @Test
    fun `never seeds demo curves next to production data`() {
        val seeder = ReferenceCurveSetSeeder(repository, snapshots, Provenance.PRODUCTION, clock)

        assertThatThrownBy { runBlocking { seeder.seedRecentRunDates() } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("production")
        coVerify(exactly = 0) { repository.saveIfAbsent(any(), any()) }
    }
}
