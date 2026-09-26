// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.settlement.application.workflow

import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.settlement.application.port.out.SettlementCoverPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementProtocol
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.infrastructure.observability.SettlementMetricsAdapter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class LedgerSettlementActivitiesTest {
    private val cover = mockk<SettlementCoverPort>(relaxed = true)
    private val repository = mockk<SettlementRepository>()
    private val audit = mockk<AuditEventPublisher>(relaxed = true)
    private val registry = SimpleMeterRegistry()
    private val metrics = SettlementMetricsAdapter().apply { bindTo(registry) }
    private val activities = object : LedgerSettlementActivitiesImpl(cover, repository, metrics, audit) {
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    @Test
    fun `failed cover never increments its completed counter`() {
        val id = UUID.randomUUID()
        coEvery { cover.reservePayer(id) } throws IllegalStateException("unavailable")
        assertThatThrownBy { activities.reserveSettlementCover(id) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(count("cover_check", "failed")).isEqualTo(1.0)
        assertThat(count("cover_check", "completed")).isZero()
        coVerify(exactly = 0) { audit.publish(any()) }
    }

    @Test
    fun `confirmed cover increments completed only`() {
        activities.reserveSettlementCover(UUID.randomUUID())
        assertThat(count("cover_check", "completed")).isEqualTo(1.0)
        assertThat(count("cover_check", "failed")).isZero()
    }

    @Test
    fun `uncertainty returns the durable status when a late booking won`() {
        val id = UUID.randomUUID()
        coEvery { repository.recordProjectionUncertainty(id, SettlementStatus.LEDGER_STATE_UNKNOWN) } returns
            Settlement(
                id, UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "CZK", SettlementStatus.BOOKED,
                Instant.now(), Instant.now(), SettlementProtocol.LEDGER_PROJECTION,
            )
        assertThat(activities.recordProjectionOutcomeUnknown(id, true)).isEqualTo(SettlementStatus.BOOKED)
        assertThat(count("record_ledger_unknown", "completed")).isEqualTo(1.0)
        coVerify { audit.publish(match { it.result == AuditResult.SUCCESS && it.payload["status"] == "BOOKED" }) }
    }

    @Test
    fun `failed uncertainty persistence propagates and records a failed step`() {
        val id = UUID.randomUUID()
        coEvery { repository.recordProjectionUncertainty(id, SettlementStatus.BALANCE_STATE_UNKNOWN) } throws
            IllegalStateException("database unavailable")
        assertThatThrownBy { activities.recordProjectionOutcomeUnknown(id, false) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(count("record_balance_unknown", "failed")).isEqualTo(1.0)
        assertThat(count("record_balance_unknown", "completed")).isZero()
        coVerify(exactly = 0) { audit.publish(any()) }
    }

    private fun count(step: String, outcome: String): Double = registry.find(SettlementMetricsAdapter.SAGA_STEPS_METRIC)
        .tag("step", step).tag("outcome", outcome).counter()?.count() ?: 0.0
}
