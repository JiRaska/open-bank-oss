// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class AccountingDayTest {

    private val date = LocalDate.of(2026, 7, 31)
    private val openedAt = Instant.parse("2026-07-31T06:00:00Z")

    private fun openDay() = AccountingDayRecord.open(date, openedAt, "operator-maker")

    @Nested
    inner class Progression {

        @Test
        fun `a new day is OPEN and accepts postings`() {
            val day = openDay()

            assertThat(day.status).isEqualTo(AccountingDayStatus.OPEN)
            assertThat(day.acceptsPostings).isTrue()
            assertThat(day.cutoffAt).isNull()
            assertThat(day.version).isZero()
        }

        @Test
        fun `the full lifecycle advances one step at a time and stamps each stage`() {
            val cutoffAt = Instant.parse("2026-07-31T20:00:00Z")
            val tiedOutAt = Instant.parse("2026-07-31T20:30:00Z")
            val lockedAt = Instant.parse("2026-08-01T06:00:00Z")

            val locked = openDay()
                .transitionTo(AccountingDayStatus.CUTOFF, "op-a", cutoffAt)
                .transitionTo(AccountingDayStatus.TIED_OUT, "op-b", tiedOutAt)
                .transitionTo(AccountingDayStatus.LOCKED, "op-c", lockedAt)

            assertThat(locked.status).isEqualTo(AccountingDayStatus.LOCKED)
            assertThat(locked.acceptsPostings).isFalse()
            assertThat(locked.version).isEqualTo(3L)
            // Append-only: an earlier stage's timestamp is never overwritten by a later one.
            assertThat(locked.cutoffAt).isEqualTo(cutoffAt)
            assertThat(locked.tiedOutAt).isEqualTo(tiedOutAt)
            assertThat(locked.lockedAt).isEqualTo(lockedAt)
            assertThat(locked.lastTransitionBy).isEqualTo("op-c")
        }

        @Test
        fun `only OPEN accepts postings`() {
            assertThat(AccountingDayStatus.OPEN.acceptsPostings).isTrue()
            listOf(
                AccountingDayStatus.CUTOFF,
                AccountingDayStatus.TIED_OUT,
                AccountingDayStatus.LOCKED,
            ).forEach { assertThat(it.acceptsPostings).describedAs(it.name).isFalse() }
        }
    }

    @Nested
    inner class MonotonicityIsTheWholePoint {

        @Test
        fun `skipping a stage is refused`() {
            assertThatThrownBy { openDay().transitionTo(AccountingDayStatus.TIED_OUT, "op", openedAt) }
                .isInstanceOf(LedgerConflictException::class.java)
                .hasMessageContaining("OPEN → TIED_OUT")
                .hasMessageContaining("CUTOFF")
        }

        /**
         * A repeat is a conflict, NOT an idempotent no-op. An idempotent-looking repeat would hide
         * an operator driving the wrong day — they would get a 200 and believe they had moved
         * something.
         */
        @Test
        fun `repeating the current stage is refused`() {
            val cutoff = openDay().transitionTo(AccountingDayStatus.CUTOFF, "op", openedAt)

            assertThatThrownBy { cutoff.transitionTo(AccountingDayStatus.CUTOFF, "op", openedAt) }
                .isInstanceOf(LedgerConflictException::class.java)
                .hasMessageContaining("CUTOFF → CUTOFF")
        }

        /**
         * There is deliberately no reopen. A day that must be corrected after cutoff is corrected
         * FORWARD — rewriting a tied-out day in place is the operation ADR-0207 removes.
         */
        @Test
        fun `a closed day can never be reopened`() {
            val tiedOut = openDay()
                .transitionTo(AccountingDayStatus.CUTOFF, "op", openedAt)
                .transitionTo(AccountingDayStatus.TIED_OUT, "op", openedAt)

            assertThatThrownBy { tiedOut.transitionTo(AccountingDayStatus.OPEN, "op", openedAt) }
                .isInstanceOf(LedgerConflictException::class.java)
                .hasMessageContaining("TIED_OUT → OPEN")
        }

        @Test
        fun `LOCKED is terminal and says so`() {
            val locked = openDay()
                .transitionTo(AccountingDayStatus.CUTOFF, "op", openedAt)
                .transitionTo(AccountingDayStatus.TIED_OUT, "op", openedAt)
                .transitionTo(AccountingDayStatus.LOCKED, "op", openedAt)

            assertThat(locked.status.next).isNull()
            assertThatThrownBy { locked.transitionTo(AccountingDayStatus.OPEN, "op", openedAt) }
                .isInstanceOf(LedgerConflictException::class.java)
                .hasMessageContaining("terminal")
        }
    }

    @Nested
    inner class EveryTransitionHasAnActor {

        @Test
        fun `a blank actor is refused on transition`() {
            assertThatThrownBy { openDay().transitionTo(AccountingDayStatus.CUTOFF, "  ", openedAt) }
                .isInstanceOf(LedgerValidationException::class.java)
                .hasMessageContaining("actor")
        }

        @Test
        fun `a blank actor is refused on open`() {
            assertThatThrownBy { AccountingDayRecord.open(date, openedAt, "") }
                .isInstanceOf(LedgerValidationException::class.java)
                .hasMessageContaining("actor")
        }
    }

    @Nested
    inner class LockDecisions {

        @Test
        fun `an unopened day is not refused - absence is not evidence`() {
            val decision = DayLockDecision.unknownDay(date)

            assertThat(decision.wouldRefuse).isFalse()
            assertThat(decision.status).isNull()
        }

        @Test
        fun `an OPEN day is allowed`() {
            assertThat(DayLockDecision.allowed(openDay()).wouldRefuse).isFalse()
        }

        @Test
        fun `a closed day is refused with a reason that names the remedy`() {
            val cutoff = openDay().transitionTo(AccountingDayStatus.CUTOFF, "op", openedAt)

            val decision = DayLockDecision.refused(cutoff)

            assertThat(decision.wouldRefuse).isTrue()
            assertThat(decision.status).isEqualTo(AccountingDayStatus.CUTOFF)
            assertThat(decision.reason).contains("correct forward")
        }
    }
}
