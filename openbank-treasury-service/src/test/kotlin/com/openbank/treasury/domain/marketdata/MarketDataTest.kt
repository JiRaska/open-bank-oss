// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.marketdata

import com.openbank.libs.finance.calendar.FinanceCalendar
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.FourEyesViolationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class MarketDataTest {
    private val creator = Actor("dana.dealer", ActorType.HUMAN)
    private val checker = Actor("adam.approver", ActorType.HUMAN)
    private val at = Instant.parse("2026-10-01T10:00:00Z")
    private val batchId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    // 2026-10-05 is a Monday.
    private val monday = LocalDate.of(2026, 10, 5)

    private val weekdays = object : FinanceCalendar {
        override val name = "WEEKDAYS"

        override fun isBusinessDay(date: LocalDate) =
            date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
    }

    private fun obs(
        date: LocalDate,
        value: String,
        status: ObservationStatus = ObservationStatus.ACTIVE,
        index: String = "PRIBOR_3M",
    ) = IndexObservation(index, date, BigDecimal(value), ObservationSource.IMPORT, batchId, status)

    private fun batch(kind: BatchKind = BatchKind.IMPORT, rows: Int = 2) = ObservationBatch.propose(
        batchId,
        kind,
        (0 until rows).map { Triple("PRIBOR_3M", monday.plusDays(it.toLong()), BigDecimal("3.5")) },
        "monthly import",
        "abc",
        creator,
        at,
    )

    @Test
    fun `an observation accepts the exact bounds and refuses just past them`() {
        assertThat(obs(monday, "100").value).isEqualByComparingTo("100")
        assertThat(obs(monday, "-100").value).isEqualByComparingTo("-100")
        assertThat(obs(monday, "1.12345678").value.scale()).isEqualTo(8)
        assertThatThrownBy { obs(monday, "100.00000001") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { obs(monday, "-100.01") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { obs(monday, "1.123456789") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { obs(monday, "1", index = "pribor") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { obs(monday, "1", index = "P") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the provenance hash is the sha-256 of the canonical form and ignores trailing zeros`() {
        val o = obs(monday, "3.7500")
        assertThat(o.sha256).isEqualTo(MarketDataFiles.sha256("PRIBOR_3M|2026-10-05|3.75|IMPORT".toByteArray()))
        assertThat(o.sha256).matches("[0-9a-f]{64}")
        assertThat(o.sha256)
            .isNotEqualTo(IndexObservation.hash("PRIBOR_3M", monday, BigDecimal("3.75"), ObservationSource.MANUAL))
            .isNotEqualTo(IndexObservation.hash("PRIBOR_3M", monday, BigDecimal("3.76"), ObservationSource.IMPORT))
    }

    @Test
    fun `a proposal maps the batch kind to the observation source and starts PENDING`() {
        val imp = batch(BatchKind.IMPORT)
        assertThat(imp.status).isEqualTo(BatchStatus.PENDING)
        assertThat(imp.rows).allMatch {
            it.source == ObservationSource.IMPORT && it.status == ObservationStatus.PENDING
        }
        assertThat(imp.rows.map { it.batchId }).containsOnly(batchId)
        assertThat(imp.createdBy).isEqualTo(creator)
        assertThat(imp.createdAt).isEqualTo(at)
        assertThat(imp.fileSha256).isEqualTo("abc")
        assertThat(imp.reason).isEqualTo("monthly import")

        val manual = batch(BatchKind.MANUAL_FIXING, rows = 1)
        assertThat(manual.rows.single().source).isEqualTo(ObservationSource.MANUAL)

        val system = Actor("system:feed", ActorType.SYSTEM)
        val feed = ObservationBatch.propose(
            batchId,
            BatchKind.FEED,
            listOf(Triple("ESTR", monday, BigDecimal("1.9"))),
            "feed",
            null,
            system,
            at,
        )
        assertThat(feed.rows.single().source).isEqualTo(ObservationSource.FEED)
    }

    @Test
    fun `only a human proposes an import or fixing and only the system proposes a feed`() {
        val agent = Actor("agent:x", ActorType.AI_AGENT)
        val system = Actor("system:feed", ActorType.SYSTEM)
        val row = listOf(Triple("ESTR", monday, BigDecimal("1.9")))
        assertThatThrownBy { ObservationBatch.propose(batchId, BatchKind.IMPORT, row, "r", null, agent, at) }
            .isInstanceOf(ActorNotPermittedException::class.java)
        assertThatThrownBy { ObservationBatch.propose(batchId, BatchKind.MANUAL_FIXING, row, "r", null, system, at) }
            .isInstanceOf(ActorNotPermittedException::class.java)
        assertThatThrownBy { ObservationBatch.propose(batchId, BatchKind.FEED, row, "r", null, creator, at) }
            .isInstanceOf(ActorNotPermittedException::class.java)
    }

    @Test
    fun `a batch refuses empty, duplicated, multi-row fixing and reasonless shapes`() {
        assertThatThrownBy { batch(rows = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            batch(BatchKind.MANUAL_FIXING, rows = 2)
        }.isInstanceOf(IllegalArgumentException::class.java)
        val dup = listOf(Triple("ESTR", monday, BigDecimal.ONE), Triple("ESTR", monday, BigDecimal.TEN))
        assertThatThrownBy { ObservationBatch.propose(batchId, BatchKind.IMPORT, dup, "r", null, creator, at) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val one = listOf(Triple("ESTR", monday, BigDecimal.ONE))
        assertThatThrownBy { ObservationBatch.propose(batchId, BatchKind.IMPORT, one, " ", null, creator, at) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ObservationBatch.propose(batchId, BatchKind.IMPORT, one, "x".repeat(1001), null, creator, at)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ObservationBatch.propose(batchId, BatchKind.IMPORT, one, "x".repeat(1000), null, creator, at).reason)
            .hasSize(1000)
        val foreign = obs(monday, "1").copy(batchId = UUID.randomUUID())
        assertThatThrownBy { ObservationBatch(batchId, BatchKind.IMPORT, listOf(foreign), "r", null, creator, at) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `approval by a different human activates every row`() {
        val later = at.plusSeconds(60)
        val approved = batch().approve(checker, later)
        assertThat(approved.status).isEqualTo(BatchStatus.APPROVED)
        assertThat(approved.decidedBy).isEqualTo(checker)
        assertThat(approved.decidedAt).isEqualTo(later)
        assertThat(approved.rows).hasSize(2).allMatch { it.status == ObservationStatus.ACTIVE }
    }

    @Test
    fun `rejection records the reason and rejects every row`() {
        val rejected = batch().reject(checker, "wrong file", at)
        assertThat(rejected.status).isEqualTo(BatchStatus.REJECTED)
        assertThat(rejected.decisionReason).isEqualTo("wrong file")
        assertThat(rejected.decidedBy).isEqualTo(checker)
        assertThat(rejected.decidedAt).isEqualTo(at)
        assertThat(rejected.rows).allMatch { it.status == ObservationStatus.REJECTED }
        assertThatThrownBy { batch().reject(checker, " ", at) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { batch().reject(checker, "x".repeat(1001), at) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(batch().reject(checker, "x".repeat(1000), at).decisionReason).hasSize(1000)
    }

    @Test
    fun `the creator, a non-human, or a second decision may not decide`() {
        assertThatThrownBy { batch().approve(creator, at) }.isInstanceOf(FourEyesViolationException::class.java)
        assertThatThrownBy { batch().reject(creator, "no", at) }.isInstanceOf(FourEyesViolationException::class.java)
        assertThatThrownBy { batch().approve(Actor("agent:x", ActorType.AI_AGENT), at) }
            .isInstanceOf(ActorNotPermittedException::class.java)
        assertThatThrownBy { batch().reject(Actor("system:x", ActorType.SYSTEM), "no", at) }
            .isInstanceOf(ActorNotPermittedException::class.java)
        assertThatThrownBy { batch().approve(checker, at).approve(checker, at) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { batch().reject(checker, "no", at).approve(checker, at) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    private val thresholds = QualityThresholds(staleAfterBusinessDays = 1, suspiciousJumpBp = 25)

    @Test
    fun `no active observation on or before the fixing date is MISSING`() {
        val active = listOf(
            obs(monday.plusDays(1), "3.5"),
            obs(monday, "3.5", status = ObservationStatus.PENDING),
            obs(monday, "3.5", index = "ESTR"),
        )
        val v = QualityGate.check("PRIBOR_3M", monday, active, thresholds, weekdays)
        assertThat(v.missing).isTrue()
        assertThat(v.passed).isFalse()
        assertThat(v.value).isNull()
        assertThat(v.observation).isNull()
        assertThat(v.reason).isEqualTo("MISSING: no ACTIVE observation of PRIBOR_3M on or before 2026-10-05")
    }

    @Test
    fun `a fresh in-band observation passes with its value`() {
        val active = listOf(obs(monday.minusDays(3), "3.50"), obs(monday, "3.75"), obs(monday.plusDays(1), "9"))
        val v = QualityGate.check("PRIBOR_3M", monday, active, thresholds, weekdays)
        assertThat(v.passed).isTrue()
        assertThat(v.reason).isNull()
        assertThat(v.value).isEqualByComparingTo("3.75")
        assertThat(v.previous).isEqualByComparingTo("3.50")
        assertThat(v.staleDays).isEqualTo(0)
        assertThat(v.jumpBp).isEqualTo(25) // exactly the threshold: not suspicious
        assertThat(v.suspicious).isFalse()
        assertThat(v.stale).isFalse()
        assertThat(v.missing).isFalse()
        assertThat(v.observation!!.date).isEqualTo(monday)
        assertThat(v.index).isEqualTo("PRIBOR_3M")
        assertThat(v.fixingDate).isEqualTo(monday)
    }

    @Test
    fun `a jump over the threshold is SUSPICIOUS and withholds the value`() {
        val active = listOf(obs(monday, "3.76"), obs(monday.minusDays(3), "3.50"))
        val v = QualityGate.check("PRIBOR_3M", monday, active, thresholds, weekdays)
        assertThat(v.jumpBp).isEqualTo(26)
        assertThat(v.suspicious).isTrue()
        assertThat(v.stale).isFalse()
        assertThat(v.passed).isFalse()
        assertThat(v.value).isNull()
        assertThat(v.reason).isEqualTo("SUSPICIOUS: a jump of 26 bp against the previous observation")
    }

    @Test
    fun `a downward jump is measured in absolute basis points rounded half up`() {
        val active = listOf(obs(monday.minusDays(3), "3.50"), obs(monday, "3.245"))
        val v = QualityGate.check("PRIBOR_3M", monday, active, thresholds, weekdays)
        assertThat(v.jumpBp).isEqualTo(26) // 25.5 bp rounds half up
        assertThat(v.suspicious).isTrue()
    }

    @Test
    fun `staleness counts business days on the calendar and the threshold is inclusive`() {
        val friday = monday.minusDays(3)
        val ok = QualityGate.check("PRIBOR_3M", monday, listOf(obs(friday, "3.5")), thresholds, weekdays)
        assertThat(ok.staleDays).isEqualTo(1)
        assertThat(ok.stale).isFalse()
        assertThat(ok.passed).isTrue()
        assertThat(ok.value).isEqualByComparingTo("3.5")
        assertThat(ok.previous).isNull()
        assertThat(ok.jumpBp).isNull()

        val v = QualityGate.check("PRIBOR_3M", monday, listOf(obs(friday.minusDays(1), "3.5")), thresholds, weekdays)
        assertThat(v.staleDays).isEqualTo(2)
        assertThat(v.stale).isTrue()
        assertThat(v.passed).isFalse()
        assertThat(v.value).isNull()
        assertThat(v.reason).isEqualTo("STALE: the latest observation is 2 business day(s) before 2026-10-05")
    }

    @Test
    fun `business days between counts strictly after from up to and including to`() {
        assertThat(QualityGate.businessDaysBetween(monday, monday, weekdays)).isEqualTo(0)
        assertThat(QualityGate.businessDaysBetween(monday, monday.plusDays(1), weekdays)).isEqualTo(1)
        assertThat(QualityGate.businessDaysBetween(monday, monday.plusDays(7), weekdays)).isEqualTo(5)
        assertThat(QualityGate.businessDaysBetween(monday.plusDays(4), monday.plusDays(6), weekdays)).isEqualTo(0)
        assertThat(QualityGate.businessDaysBetween(monday, monday.plusDays(3659), weekdays)).isLessThan(Int.MAX_VALUE)
        assertThat(QualityGate.businessDaysBetween(monday, monday.plusDays(4000), weekdays)).isEqualTo(Int.MAX_VALUE)
    }

    @Test
    fun `a verdict reports missing before stale before suspicious`() {
        val base = QualityVerdict("X1", monday, null, null, null, 3, 50, true, true, true)
        assertThat(base.reason).startsWith("MISSING")
        assertThat(base.copy(missing = false).reason).startsWith("STALE")
        assertThat(base.copy(missing = false, stale = false).reason).startsWith("SUSPICIOUS")
        assertThat(base.copy(missing = false, stale = false).passed).isFalse()
        assertThat(base.copy(missing = false, suspicious = false).passed).isFalse()
        assertThat(base.copy(stale = false, suspicious = false).passed).isFalse()
        assertThat(base.copy(missing = false, stale = false, suspicious = false).passed).isTrue()
    }
}
