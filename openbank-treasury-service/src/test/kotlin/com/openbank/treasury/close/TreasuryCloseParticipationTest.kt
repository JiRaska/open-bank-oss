// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.close

import com.openbank.libs.close.CloseOutcome
import com.openbank.treasury.domain.model.DayStep
import com.openbank.treasury.domain.model.DayStepRecord
import com.openbank.treasury.domain.model.DayStepStatus
import com.openbank.treasury.infrastructure.close.TreasuryCloseParticipation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class TreasuryCloseParticipationTest {
    private val day = LocalDate.parse("2026-10-16")
    private val at = Instant.parse("2026-10-16T20:00:00Z")
    private val due = setOf(DayStep.ACCRUALS, DayStep.FIXINGS)

    private fun done(step: DayStep, n: Int) = DayStepRecord.of(day, step, n, 0, at)

    @Test
    fun `every due step done is RAN with the summed counts`() {
        val r = TreasuryCloseParticipation.report(day, due, listOf(done(DayStep.ACCRUALS, 3), done(DayStep.FIXINGS, 2)))
        assertThat(r!!.outcome).isEqualTo(CloseOutcome.RAN)
        assertThat(r.journalsPosted).isEqualTo(5)
        assertThat(r.itemsProcessed).isEqualTo(5)
    }

    @Test
    fun `a due step not yet run withholds the day`() {
        assertThat(TreasuryCloseParticipation.report(day, due, listOf(done(DayStep.ACCRUALS, 3)))).isNull()
    }

    @Test
    fun `a FAILED step withholds the day, it is never reported as ran`() {
        val failed = DayStepRecord.of(day, DayStep.FIXINGS, 1, 2, at)
        assertThat(failed.status).isEqualTo(DayStepStatus.FAILED)
        assertThat(TreasuryCloseParticipation.report(day, due, listOf(done(DayStep.ACCRUALS, 3), failed))).isNull()
    }

    @Test
    fun `only closed-day refusals make the step REFUSED, and the day REFUSED with no journals claimed`() {
        val refused = DayStepRecord.of(day, DayStep.FIXINGS, 0, 2, at, refused = 2)
        assertThat(refused.status).isEqualTo(DayStepStatus.REFUSED)
        val mixed = DayStepRecord.of(day, DayStep.FIXINGS, 0, 2, at, refused = 1)
        assertThat(mixed.status).isEqualTo(DayStepStatus.FAILED)
        val r = TreasuryCloseParticipation.report(day, due, listOf(done(DayStep.ACCRUALS, 3), refused))!!
        assertThat(r.outcome).isEqualTo(CloseOutcome.REFUSED)
        assertThat(r.journalsPosted).isZero()
        assertThat(r.reason).contains("FIXINGS").contains("closed")
    }

    @Test
    fun `no due step is SKIPPED with a reason, and a record of another day never counts`() {
        assertThat(
            TreasuryCloseParticipation.report(day, emptySet(), emptyList())!!.outcome,
        ).isEqualTo(CloseOutcome.SKIPPED)
        val other = DayStepRecord.of(day.minusDays(1), DayStep.ACCRUALS, 1, 0, at)
        assertThat(TreasuryCloseParticipation.report(day, setOf(DayStep.ACCRUALS), listOf(other))).isNull()
    }

    @Test
    fun `the due-step list is parsed strictly`() {
        assertThat(
            TreasuryCloseParticipation.dueSteps(" accruals, FIXINGS ,"),
        ).containsExactlyInAnyOrder(DayStep.ACCRUALS, DayStep.FIXINGS)
        assertThat(TreasuryCloseParticipation.dueSteps("")).isEmpty()
        assertThatThrownBy { TreasuryCloseParticipation.dueSteps("ACCRUALS,SETTLEMENTS") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("SETTLEMENTS")
        assertThatThrownBy { DayStepRecord.of(day, DayStep.ACCRUALS, 0, 1, at, refused = 2) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
