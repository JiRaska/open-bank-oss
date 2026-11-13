// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.marketdata

import com.openbank.libs.domain.calendar.BusinessCalendar
import com.openbank.libs.finance.calendar.Calendars
import com.openbank.treasury.domain.marketdata.BatchKind
import com.openbank.treasury.domain.marketdata.BatchStatus
import com.openbank.treasury.domain.marketdata.IndexObservation
import com.openbank.treasury.domain.marketdata.MarketDataFiles
import com.openbank.treasury.domain.marketdata.ObservationBatch
import com.openbank.treasury.domain.marketdata.ObservationSource
import com.openbank.treasury.domain.marketdata.ObservationStatus
import com.openbank.treasury.domain.marketdata.QualityGate
import com.openbank.treasury.domain.marketdata.QualityThresholds
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.FourEyesViolationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class MarketDataTest {
    private val anna = Actor("anna.backoffice", ActorType.HUMAN)
    private val petr = Actor("petr.approver", ActorType.HUMAN)
    private val agent = Actor("agent:treasury-drafter", ActorType.AI_AGENT)
    private val at = Instant.parse("2026-10-16T08:00:00Z")
    private val cal = Calendars.of(BusinessCalendar.certis())
    private val thresholds = QualityThresholds(staleAfterBusinessDays = 1, suspiciousJumpBp = 50)
    private val fri = LocalDate.parse("2026-10-16")

    private fun obs(date: LocalDate, v: String, status: ObservationStatus = ObservationStatus.ACTIVE) =
        IndexObservation("PRIBOR_3M", date, BigDecimal(v), ObservationSource.IMPORT, UUID.randomUUID(), status)

    private fun batch(by: Actor = anna) = ObservationBatch.propose(
        UUID.randomUUID(),
        BatchKind.IMPORT,
        listOf(Triple("PRIBOR_3M", fri, BigDecimal("3.52"))),
        "daily import",
        "ab".repeat(32),
        by,
        at,
    )

    @Test
    fun `a good observation passes and carries its value`() {
        val v = QualityGate.check(
            "PRIBOR_3M",
            fri,
            listOf(obs(fri.minusDays(1), "3.50"), obs(fri, "3.52")),
            thresholds,
            cal,
        )
        assertThat(v.passed).isTrue()
        assertThat(v.value).isEqualByComparingTo("3.52")
        assertThat(v.jumpBp).isEqualTo(2)
    }

    @Test
    fun `missing, stale and jump each block, and a blocked verdict carries no value to fix from`() {
        val missing = QualityGate.check("PRIBOR_3M", fri, emptyList(), thresholds, cal)
        assertThat(missing.missing).isTrue()
        assertThat(missing.value).isNull()
        // Tuesday's value on Friday: 3 business days old, threshold 1.
        val stale = QualityGate.check("PRIBOR_3M", fri, listOf(obs(fri.minusDays(3), "3.50")), thresholds, cal)
        assertThat(stale.stale).isTrue()
        assertThat(stale.value).isNull()
        assertThat(stale.reason).startsWith("STALE")
        val jump = QualityGate.check(
            "PRIBOR_3M",
            fri,
            listOf(obs(fri.minusDays(1), "3.50"), obs(fri, "4.10")),
            thresholds,
            cal,
        )
        assertThat(jump.suspicious).isTrue()
        assertThat(jump.jumpBp).isEqualTo(60)
        assertThat(jump.value).isNull()
    }

    @Test
    fun `only ACTIVE observations count, and a later date is never used`() {
        val v = QualityGate.check(
            "PRIBOR_3M",
            fri,
            listOf(obs(fri, "3.52", ObservationStatus.PENDING), obs(fri.plusDays(3), "3.60")),
            thresholds,
            cal,
        )
        assertThat(v.missing).isTrue()
    }

    @Test
    fun `a weekend does not make Friday's value stale on Monday`() {
        val mon = fri.plusDays(3)
        val v = QualityGate.check("PRIBOR_3M", mon, listOf(obs(fri, "3.52")), thresholds, cal)
        assertThat(v.staleDays).isEqualTo(1)
        assertThat(v.passed).isTrue()
    }

    @Test
    fun `four-eyes - the creator, an AI agent and a service cannot approve, a different human can`() {
        assertThatThrownBy { batch().approve(anna, at) }.isInstanceOf(FourEyesViolationException::class.java)
        assertThatThrownBy { batch().reject(anna, "no", at) }.isInstanceOf(FourEyesViolationException::class.java)
        assertThatThrownBy { batch().approve(agent, at) }.isInstanceOf(ActorNotPermittedException::class.java)
        assertThatThrownBy { batch().approve(Actor("service-account-x", ActorType.SERVICE), at) }
            .isInstanceOf(ActorNotPermittedException::class.java)
        val ok = batch().approve(petr, at)
        assertThat(ok.status).isEqualTo(BatchStatus.APPROVED)
        assertThat(ok.rows).allMatch { it.status == ObservationStatus.ACTIVE }
        assertThatThrownBy { ok.approve(petr, at) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `an AI agent cannot even propose market data, nor a manual fixing`() {
        assertThatThrownBy { batch(agent) }.isInstanceOf(ActorNotPermittedException::class.java)
        assertThatThrownBy {
            ObservationBatch.propose(
                UUID.randomUUID(),
                BatchKind.MANUAL_FIXING,
                listOf(Triple("ESTR", fri, BigDecimal.ONE)),
                "r",
                null,
                agent,
                at,
            )
        }.isInstanceOf(ActorNotPermittedException::class.java)
    }

    @Test
    fun `the provenance hash is stable over trailing zeros and changes with the source`() {
        val a = IndexObservation.hash("ESTR", fri, BigDecimal("1.9300"), ObservationSource.IMPORT)
        assertThat(
            a,
        ).isEqualTo(IndexObservation.hash("ESTR", fri, BigDecimal("1.93"), ObservationSource.IMPORT)).hasSize(64)
        assertThat(a).isNotEqualTo(IndexObservation.hash("ESTR", fri, BigDecimal("1.93"), ObservationSource.MANUAL))
    }

    @Test
    fun `csv import is bounded and refuses the whole file on one bad row`() {
        val rows = MarketDataFiles.parseCsv(
            "index,date,value\nPRIBOR_3M,2026-10-16,3.52\nESTR,2026-10-16,1.93\n".toByteArray(),
        )
        assertThat(rows).hasSize(2)
        assertThatThrownBy { MarketDataFiles.parseCsv("PRIBOR_3M,16.10.2026,3.52".toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("row 1")
        assertThatThrownBy { MarketDataFiles.parseCsv("=cmd,2026-10-16,1".toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MarketDataFiles.parseCsv("ESTR,2026-10-16,250".toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MarketDataFiles.parseCsv(ByteArray(MarketDataFiles.MAX_BYTES + 1) { 'a'.code.toByte() }) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val tooMany = (0..MarketDataFiles.MAX_ROWS).joinToString("\n") { "ESTR,2026-10-16,1" }
        assertThatThrownBy {
            MarketDataFiles.parseCsv(tooMany.toByteArray())
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a batch names each index-date once`() {
        assertThatThrownBy {
            ObservationBatch.propose(
                UUID.randomUUID(),
                BatchKind.IMPORT,
                listOf(Triple("ESTR", fri, BigDecimal.ONE), Triple("ESTR", fri, BigDecimal.TEN)),
                "dup",
                null,
                anna,
                at,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `csv export neutralises a formula-leading cell but keeps negative numbers`() {
        assertThat(MarketDataFiles.cell("=HYPERLINK(1)")).isEqualTo("\"'=HYPERLINK(1)\"")
        assertThat(MarketDataFiles.cell("-0.25")).isEqualTo("\"-0.25\"")
        assertThat(MarketDataFiles.cell("@x")).isEqualTo("\"'@x\"")
    }
}
