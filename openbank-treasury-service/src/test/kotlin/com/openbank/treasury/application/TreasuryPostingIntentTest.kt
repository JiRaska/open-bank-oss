// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.treasury.application.port.`in`.DraftDealCommand
import com.openbank.treasury.application.port.out.CounterpartyRepository
import com.openbank.treasury.application.port.out.FxMidRatePort
import com.openbank.treasury.application.port.out.FxRateTolerance
import com.openbank.treasury.application.usecase.TreasuryDealService
import com.openbank.treasury.domain.DealFixtures
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.infrastructure.LegWireJackson
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

/** #13883: settle, mature and reverse record a posting intent before the ledger is contacted. */
class TreasuryPostingIntentTest {
    private val monday = DealFixtures.MONDAY
    private var clock: Clock = Clock.fixed(monday.atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private val intents = InMemoryIntents()
    private val deals = InMemoryDeals(intents)
    private val ledger = RecordingLedger()
    private val cps = object : CounterpartyRepository {
        val all = listOf(DealFixtures.bankA)
        override suspend fun findById(id: String) = all.find { it.id == id }
        override suspend fun list() = all
    }
    private val mapper = LegWireJackson.register(ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule()))
    private val service get() = TreasuryDealService(
        deals, cps, ledger, mapper, clock, FxMidRatePort.NONE, FxRateTolerance.DISABLED,
        confirmationRequired = false,
        productLimits = DealFixtures.permissiveProductLimits,
        intents = intents,
    )

    private suspend fun book(): Deal {
        val c = DraftDealCommand(
            ProductType.MM_PLACEMENT, "SIMBK-A", "CZK", BigDecimal("100000.00"), BigDecimal("4.00"),
            null, monday, monday.plusDays(7), null,
        )
        val d = service.draft(c, DealFixtures.dealer)
        service.submit(d.id, DealFixtures.dealer)
        return service.approve(d.id, DealFixtures.approver)
    }

    private fun later(by: Duration) {
        clock = Clock.offset(clock, by)
    }

    private fun settlementKey(deal: Deal) = intents.rows.values.map { it.first }
        .first { it.dealId == deal.id && it.event == PostingEvent.SETTLED }
        .idempotencyKey

    @Test
    fun `settle records the intent before the ledger call and completes it with the state change`(): Unit =
        runBlocking {
            val b = book()
            val seenAtPost = mutableListOf<InMemoryIntents.Status?>()
            ledger.beforePost = { key -> seenAtPost += intents.status(key) }
            assertThat(service.settle(b.id, DealFixtures.approver).state).isEqualTo(DealState.SETTLED)
            assertThat(seenAtPost).containsExactly(InMemoryIntents.Status.OPEN)
            val key = ledger.posted.single { it.event == PostingEvent.SETTLED }.idempotencyKey
            assertThat(intents.status(key)).isEqualTo(InMemoryIntents.Status.COMPLETED)
        }

    @Test
    fun `a lost ledger answer leaves the intent open, and the reconciler completes it with one journal`(): Unit =
        runBlocking {
            val b = book()
            ledger.loseAnswerFor = b.id
            assertThatThrownBy { runBlocking { service.settle(b.id, DealFixtures.approver) } }
                .hasMessageContaining("answer was lost")
            val key = ledger.posted.single { it.event == PostingEvent.SETTLED }.idempotencyKey
            // The ledger holds the journal, treasury neither recorded nor applied it: the intent is the only trace.
            assertThat(intents.status(key)).isEqualTo(InMemoryIntents.Status.OPEN)
            assertThat(deals.journals).noneMatch { it.idempotencyKey == key }
            assertThat(deals.rows.getValue(b.id).state).isEqualTo(DealState.BOOKED)

            // An intent younger than the grace period is left to the command that may still be running.
            assertThat(service.reconcilePostingIntents(clock.instant()).completed).isZero()

            later(Duration.ofMinutes(5))
            val run = service.reconcilePostingIntents(clock.instant())
            assertThat(run.completed).isEqualTo(1)
            assertThat(run.failures).isEmpty()
            assertThat(deals.rows.getValue(b.id).state).isEqualTo(DealState.SETTLED)
            assertThat(intents.status(key)).isEqualTo(InMemoryIntents.Status.COMPLETED)
            assertThat(deals.journals.filter { it.idempotencyKey == key }).hasSize(1)
            // Re-posted under the SAME key, so the ledger answered with its original journal.
            assertThat(ledger.posted.map { it.idempotencyKey }.filter { it == key }).hasSize(2)
            assertThat(deals.journals.single { it.idempotencyKey == key }.journalId)
                .isEqualTo(java.util.UUID.nameUUIDFromBytes(key.toByteArray()))
        }

    @Test
    fun `a reconciliation that fails keeps the intent open with its cause and attempt count`(): Unit = runBlocking {
        val b = book()
        ledger.loseAnswerFor = b.id
        assertThatThrownBy { runBlocking { service.settle(b.id, DealFixtures.approver) } }
        ledger.failFor = b.id // the ledger is now unreachable: nothing is known, nothing is closed
        later(Duration.ofMinutes(5))
        val run = service.reconcilePostingIntents(clock.instant())
        assertThat(run.completed).isZero()
        assertThat(run.failures).hasSize(1)
        val intent = intents.rows.getValue(settlementKey(b))
        assertThat(intent.second).isEqualTo(InMemoryIntents.Status.OPEN)
        assertThat(intent.first.attempts).isEqualTo(1)
        assertThat(intent.first.lastError).contains("ledger unavailable")
        assertThat(deals.rows.getValue(b.id).state).isEqualTo(DealState.BOOKED)
    }

    @Test
    fun `a definitive ledger refusal closes the intent REFUSED, and a retry reopens it`(): Unit = runBlocking {
        val b = book()
        ledger.refuseFor = b.id
        assertThatThrownBy { runBlocking { service.settle(b.id, DealFixtures.approver) } }
            .isInstanceOf(LedgerRefused::class.java)
        assertThat(intents.status(settlementKey(b))).isEqualTo(InMemoryIntents.Status.REFUSED)
        later(Duration.ofMinutes(5))
        assertThat(service.reconcilePostingIntents(clock.instant()).completed).isZero()

        ledger.refuseFor = null
        assertThat(service.settle(b.id, DealFixtures.approver).state).isEqualTo(DealState.SETTLED)
        assertThat(intents.status(settlementKey(b))).isEqualTo(InMemoryIntents.Status.COMPLETED)
    }

    @Test
    fun `a reversal resumed on a later day posts the journal valued on the day it was decided`(): Unit = runBlocking {
        val b = book()
        service.settle(b.id, DealFixtures.approver)
        ledger.loseAnswerFor = b.id
        assertThatThrownBy { runBlocking { service.reverse(b.id, "wrong counterparty", DealFixtures.approver) } }
        later(Duration.ofDays(1))
        assertThat(service.reconcilePostingIntents(clock.instant()).completed).isEqualTo(1)
        assertThat(deals.rows.getValue(b.id).state).isEqualTo(DealState.REVERSED)
        val reversal = deals.journals.single { it.event == PostingEvent.REVERSED }
        assertThat(reversal.valueDate).isEqualTo(monday)
        val intent = intents.rows.getValue(reversal.idempotencyKey)
        assertThat(intent.second).isEqualTo(InMemoryIntents.Status.COMPLETED)
        assertThat(intent.first.reason).isEqualTo("wrong counterparty")
    }

    @Test
    fun `maturity records and completes its intent`(): Unit = runBlocking {
        val b = book()
        service.settle(b.id, DealFixtures.approver)
        later(Duration.ofDays(7))
        ledger.loseAnswerFor = null
        assertThat(service.mature(b.id, DealFixtures.approver).state).isEqualTo(DealState.MATURED)
        val key = ledger.posted.single { it.event == PostingEvent.MATURED }.idempotencyKey
        assertThat(intents.status(key)).isEqualTo(InMemoryIntents.Status.COMPLETED)
        assertThat(intents.rows.values.map { it.first.action.name }).containsExactly("SETTLE", "MATURE")
    }
}
