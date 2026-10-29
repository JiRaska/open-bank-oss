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
    fun `an intent from an earlier business day is escalated, and an operator's re-issue completes it`(): Unit =
        runBlocking {
            val b = book()
            service.settle(b.id, DealFixtures.approver)
            ledger.loseAnswerFor = b.id
            assertThatThrownBy { runBlocking { service.reverse(b.id, "wrong counterparty", DealFixtures.approver) } }
            later(Duration.ofDays(1))
            // Not resumed as the stored actor: their authority may have lapsed since.
            val run = service.reconcilePostingIntents(clock.instant())
            assertThat(run.completed).isZero()
            assertThat(run.escalated).isEqualTo(1)
            val key = intents.rows.values.single { it.first.event == PostingEvent.REVERSED }.first.idempotencyKey
            assertThat(intents.status(key)).isEqualTo(InMemoryIntents.Status.OPEN)
            assertThat(intents.rows.getValue(key).first.lastError).startsWith("escalated")
            assertThat(deals.rows.getValue(b.id).state).isEqualTo(DealState.SETTLED)

            // An operator re-issues the command through the (authorised) API path: same key, first day.
            assertThat(service.reverse(b.id, "wrong counterparty", DealFixtures.approver).state)
                .isEqualTo(DealState.REVERSED)
            val reversal = deals.journals.single { it.event == PostingEvent.REVERSED }
            assertThat(reversal.valueDate).isEqualTo(monday)
            assertThat(intents.status(key)).isEqualTo(InMemoryIntents.Status.COMPLETED)
        }

    @Test
    fun `an intent older than the resume window on the same day is escalated too`(): Unit = runBlocking {
        val b = book()
        ledger.loseAnswerFor = b.id
        assertThatThrownBy { runBlocking { service.settle(b.id, DealFixtures.approver) } }
        later(Duration.ofHours(5)) // 09:00 -> 14:00 UTC, same Prague day, past the 4h window
        val run = service.reconcilePostingIntents(clock.instant())
        assertThat(run.escalated).isEqualTo(1)
        assertThat(deals.rows.getValue(b.id).state).isEqualTo(DealState.BOOKED)
    }

    @Test
    fun `no accrual while a reversal intent is open - the resumed reversal announces the original journal`(): Unit =
        runBlocking {
            val b = book()
            service.settle(b.id, DealFixtures.approver)
            later(Duration.ofDays(1))
            service.accrueInterest(monday.plusDays(1))
            val accrualsBefore = deals.journals.count { it.event == PostingEvent.ACCRUED }
            ledger.loseAnswerFor = b.id
            assertThatThrownBy { runBlocking { service.reverse(b.id, "wrong rate", DealFixtures.approver) } }
            val key = intents.rows.values.single { it.first.event == PostingEvent.REVERSED }.first.idempotencyKey
            val originalLines = ledger.posted.first { it.idempotencyKey == key }.lines

            // A later accrual pass leaves the deal alone: the ledger's reversal offsets only what existed.
            service.accrueInterest(monday.plusDays(2))
            assertThat(deals.journals.count { it.event == PostingEvent.ACCRUED }).isEqualTo(accrualsBefore)
            later(Duration.ofMinutes(5))

            assertThat(service.reconcilePostingIntents(clock.instant()).completed).isEqualTo(1)
            // Re-posted with the same lines, and the event carries the ledger's original journal id.
            assertThat(ledger.posted.last { it.idempotencyKey == key }.lines).isEqualTo(originalLines)
            val reversedEvent = deals.events.last { it.eventType.contains("reversed") }
            assertThat(reversedEvent.payload).contains(java.util.UUID.nameUUIDFromBytes(key.toByteArray()).toString())
            assertThat(deals.rows.getValue(b.id).state).isEqualTo(DealState.REVERSED)
        }

    @Test
    fun `a COMPLETED intent answers with its recorded journal and never calls the ledger again`(): Unit = runBlocking {
        val b = book()
        val settled = service.settle(b.id, DealFixtures.approver)
        val key = settlementKey(b)
        val calls = ledger.posted.size
        // A stale copy of the deal (as a replayed command computed from an old read would see it).
        deals.rows[b.id] = b
        service.settle(b.id, DealFixtures.approver)
        assertThat(ledger.posted).hasSize(calls)
        assertThat(deals.journals.filter { it.idempotencyKey == key }).hasSize(1)
        assertThat(settled.state).isEqualTo(DealState.SETTLED)
    }

    @Test
    fun `an operator closes an open intent with a reason - a person only, an absent one is false`(): Unit =
        runBlocking {
            val b = book()
            ledger.loseAnswerFor = b.id
            assertThatThrownBy { runBlocking { service.settle(b.id, DealFixtures.approver) } }
            val agent = com.openbank.treasury.domain.model.Actor.fromPrincipalName("agent:treasury-drafter")
            assertThatThrownBy {
                runBlocking { service.closePostingIntent(b.id, PostingEvent.SETTLED, "no journal", agent) }
            }.isInstanceOf(com.openbank.treasury.domain.model.ActorNotPermittedException::class.java)
            assertThat(service.closePostingIntent(b.id, PostingEvent.MATURED, "nothing there", DealFixtures.approver))
                .isFalse()
            assertThat(
                service.closePostingIntent(b.id, PostingEvent.SETTLED, "ledger holds none", DealFixtures.approver),
            )
                .isTrue()
            val key = settlementKey(b)
            assertThat(intents.status(key)).isEqualTo(InMemoryIntents.Status.REFUSED)
            assertThat(intents.closedBy[key]).isEqualTo(DealFixtures.approver.id)
            assertThat(intents.rows.getValue(key).first.lastError).contains("ledger holds none")
        }

    @Test
    fun `a reversal refused on one day and retried the next posts under the retry's day`(): Unit = runBlocking {
        val b = book()
        service.settle(b.id, DealFixtures.approver)
        ledger.refuseFor = b.id
        assertThatThrownBy { runBlocking { service.reverse(b.id, "first try", DealFixtures.approver) } }
            .isInstanceOf(LedgerRefused::class.java)
        val key = intents.rows.values.single { it.first.event == PostingEvent.REVERSED }.first.idempotencyKey
        assertThat(intents.status(key)).isEqualTo(InMemoryIntents.Status.REFUSED)

        ledger.refuseFor = null
        later(Duration.ofDays(1))
        assertThat(service.reverse(b.id, "second try", DealFixtures.approver).state).isEqualTo(DealState.REVERSED)
        // The refused attempt left no journal, so nothing pins the first day: the retry's own day is posted.
        assertThat(deals.journals.single { it.idempotencyKey == key }.valueDate).isEqualTo(monday.plusDays(1))
        assertThat(ledger.entryDates.getValue(key)).isEqualTo(monday.plusDays(1))
        assertThat(intents.rows.getValue(key).first.reason).isEqualTo("second try")
    }

    @Test
    fun `a refusal on a resumed intent never closes it - an earlier attempt may have posted`(): Unit = runBlocking {
        val b = book()
        ledger.loseAnswerFor = b.id
        assertThatThrownBy { runBlocking { service.settle(b.id, DealFixtures.approver) } }
        ledger.refuseFor = b.id
        later(Duration.ofMinutes(5))
        val run = service.reconcilePostingIntents(clock.instant())
        assertThat(run.failures).hasSize(1)
        assertThat(intents.status(settlementKey(b))).isEqualTo(InMemoryIntents.Status.OPEN)
        // Nor does a client retry of the same command close it.
        assertThatThrownBy { runBlocking { service.settle(b.id, DealFixtures.approver) } }
            .isInstanceOf(LedgerRefused::class.java)
        assertThat(intents.status(settlementKey(b))).isEqualTo(InMemoryIntents.Status.OPEN)
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
