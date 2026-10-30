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
import com.openbank.treasury.application.port.out.PostingIntentCloseOutcome
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
    private var claimBatch = 100

    private val service get() = TreasuryDealService(
        deals, cps, ledger, mapper, clock, FxMidRatePort.NONE, FxRateTolerance.DISABLED,
        confirmationRequired = false,
        productLimits = DealFixtures.permissiveProductLimits,
        intents = intents,
        claimBatch = claimBatch,
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
    fun `an intent a day old is escalated, and an operator's re-issue through the API completes it`(): Unit =
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
            val deferred = service.accrueInterest(monday.plusDays(2))
            assertThat(deals.journals.count { it.event == PostingEvent.ACCRUED }).isEqualTo(accrualsBefore)
            // Deferred for THIS deal only: the pass still completes (nothing unassessed, no failure).
            assertThat(deferred.deferred).isEqualTo(1)
            assertThat(deferred.unassessed).isZero()
            assertThat(deferred.failures).isEmpty()
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

    private val operator = com.openbank.treasury.domain.model.Actor(
        "olga.operator",
        com.openbank.treasury.domain.model.ActorType.HUMAN,
    )

    @Test
    fun `an operator closes an open intent with a reason - a person only, never the four-eyes parties`(): Unit =
        runBlocking {
            val b = book()
            ledger.loseAnswerFor = b.id
            // DealFixtures.approver booked the deal AND decided the settlement.
            assertThatThrownBy { runBlocking { service.settle(b.id, DealFixtures.approver) } }
            val agent = com.openbank.treasury.domain.model.Actor.fromPrincipalName("agent:treasury-drafter")
            assertThatThrownBy {
                runBlocking { service.closePostingIntent(b.id, PostingEvent.SETTLED, "no journal", agent) }
            }.isInstanceOf(com.openbank.treasury.domain.model.ActorNotPermittedException::class.java)
            assertThatThrownBy {
                runBlocking { service.closePostingIntent(b.id, PostingEvent.SETTLED, "mine", DealFixtures.approver) }
            }.isInstanceOf(com.openbank.treasury.domain.model.FourEyesViolationException::class.java)
            val key = settlementKey(b)
            assertThat(intents.status(key)).isEqualTo(InMemoryIntents.Status.OPEN)
            later(Duration.ofMinutes(5))
            assertThat(
                service.closePostingIntent(b.id, PostingEvent.MATURED, "nothing there", operator),
            ).isEqualTo(PostingIntentCloseOutcome.NOT_OPEN)
            assertThat(
                service.closePostingIntent(b.id, PostingEvent.SETTLED, "ledger holds none", operator),
            ).isEqualTo(PostingIntentCloseOutcome.CLOSED)
            assertThat(intents.status(key)).isEqualTo(InMemoryIntents.Status.CLOSED_BY_OPERATOR)
            assertThat(intents.closedBy[key]).isEqualTo(operator.id)
        }

    @Test
    fun `the deal's approver may not close a posting someone else decided`(): Unit = runBlocking {
        val b = book()
        ledger.loseAnswerFor = b.id
        // The market (a different actor) decides the settlement; the approver booked the deal.
        val market = com.openbank.treasury.domain.model.Actor.SIMULATED_MARKET
        assertThatThrownBy { runBlocking { service.settle(b.id, market) } }
        assertThatThrownBy {
            runBlocking { service.closePostingIntent(b.id, PostingEvent.SETTLED, "approver", DealFixtures.approver) }
        }.isInstanceOf(com.openbank.treasury.domain.model.FourEyesViolationException::class.java)
        later(Duration.ofMinutes(5))
        assertThat(
            service.closePostingIntent(b.id, PostingEvent.SETTLED, "third person", operator),
        ).isEqualTo(PostingIntentCloseOutcome.CLOSED)
    }

    @Test
    fun `a re-issue after an operator close keeps the first value day and the history keeps every decision`(): Unit =
        runBlocking {
            val b = book()
            service.settle(b.id, DealFixtures.approver)
            ledger.loseAnswerFor = b.id
            assertThatThrownBy { runBlocking { service.reverse(b.id, "wrong rate", DealFixtures.approver) } }
            val key = intents.rows.values.single { it.first.event == PostingEvent.REVERSED }.first.idempotencyKey
            later(Duration.ofMinutes(5))
            assertThat(
                service.closePostingIntent(b.id, PostingEvent.REVERSED, "investigating", operator),
            ).isEqualTo(PostingIntentCloseOutcome.CLOSED)

            later(Duration.ofDays(1))
            // The ledger may hold the first attempt's journal: the re-issue asks for that one, day included.
            assertThat(service.reverse(b.id, "wrong rate", DealFixtures.approver).state).isEqualTo(DealState.REVERSED)
            assertThat(deals.journals.single { it.idempotencyKey == key }.valueDate).isEqualTo(monday)
            assertThat(intents.closedBy[key]).isEqualTo(operator.id)
            assertThat(intents.history.filter { it[0] == key }.map { it[1] }).containsExactly(
                "OPENED",
                "CLOSED_BY_OPERATOR",
                "REOPENED_AFTER_OPERATOR_CLOSE",
                "COMPLETED",
            )
            assertThat(intents.history.single { it[0] == key && it[1] == "CLOSED_BY_OPERATOR" })
                .containsExactly(key, "CLOSED_BY_OPERATOR", operator.id, "investigating", monday)
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
    fun `a coded refusal closes a resumed intent past the grace, never a client retry inside it`(): Unit = runBlocking {
        val b = book()
        ledger.loseAnswerFor = b.id
        assertThatThrownBy { runBlocking { service.settle(b.id, DealFixtures.approver) } }
        ledger.refuseFor = b.id
        // Inside the grace the first attempt may still be in flight in the ledger: stays OPEN.
        assertThatThrownBy { runBlocking { service.settle(b.id, DealFixtures.approver) } }
            .isInstanceOf(LedgerRefused::class.java)
        assertThat(intents.status(settlementKey(b))).isEqualTo(InMemoryIntents.Status.OPEN)
        // Past it, the ledger's refusal (raised after its replay check) proves no journal exists.
        later(Duration.ofMinutes(5))
        val run = service.reconcilePostingIntents(clock.instant())
        assertThat(run.failures).hasSize(1)
        assertThat(intents.status(settlementKey(b))).isEqualTo(InMemoryIntents.Status.REFUSED)
    }

    @Test
    fun `an intent decided at 23 59 50 and reconciled after midnight is resumed, on its own value day`(): Unit =
        runBlocking {
            clock = Clock.fixed(
                monday.atTime(
                    23,
                    59,
                    50,
                ).atZone(com.openbank.libs.domain.calendar.AccountingClock.BANK_ZONE).toInstant(),
                ZoneOffset.UTC,
            )
            val b = book()
            ledger.loseAnswerFor = b.id
            assertThatThrownBy { runBlocking { service.settle(b.id, DealFixtures.approver) } }
            later(Duration.ofSeconds(40)) // 00:00:30 the next Prague day
            val run = service.reconcilePostingIntents(clock.instant())
            assertThat(run.escalated).isZero()
            assertThat(run.completed).isEqualTo(1)
            assertThat(deals.journals.single { it.event == PostingEvent.SETTLED }.valueDate).isEqualTo(monday)
        }

    @Test
    fun `escalated intents are never re-claimed - more than a batch cannot starve a resumable one`(): Unit =
        runBlocking {
            claimBatch = 1
            val stale = (1..2).map {
                val d = book()
                ledger.loseAnswerFor = d.id
                assertThatThrownBy { runBlocking { service.settle(d.id, DealFixtures.approver) } }
                d
            }
            later(Duration.ofHours(5))
            val fresh = book()
            ledger.loseAnswerFor = fresh.id
            assertThatThrownBy { runBlocking { service.settle(fresh.id, DealFixtures.approver) } }
            later(Duration.ofMinutes(5))
            val runs = (1..3).map { service.reconcilePostingIntents(clock.instant()) }
            assertThat(runs.sumOf { it.escalated }).isEqualTo(2)
            assertThat(runs.sumOf { it.completed }).isEqualTo(1)
            assertThat(deals.rows.getValue(fresh.id).state).isEqualTo(DealState.SETTLED)
            stale.forEach { assertThat(deals.rows.getValue(it.id).state).isEqualTo(DealState.BOOKED) }
        }

    @Test
    fun `an operator cannot close an intent still inside the grace`(): Unit = runBlocking {
        val b = book()
        ledger.loseAnswerFor = b.id
        assertThatThrownBy {
            runBlocking { service.settle(b.id, com.openbank.treasury.domain.model.Actor.SIMULATED_MARKET) }
        }
        assertThat(
            service.closePostingIntent(b.id, PostingEvent.SETTLED, "too soon", operator),
        ).isEqualTo(PostingIntentCloseOutcome.IN_FLIGHT)
        later(Duration.ofMinutes(5))
        assertThat(
            service.closePostingIntent(b.id, PostingEvent.SETTLED, "after the grace", operator),
        ).isEqualTo(PostingIntentCloseOutcome.CLOSED)
    }

    @Test
    fun `a journal applied after an operator close is recorded beside the close, never over it`(): Unit = runBlocking {
        val b = book()
        val market = com.openbank.treasury.domain.model.Actor.SIMULATED_MARKET
        ledger.loseAnswerFor = b.id
        assertThatThrownBy { runBlocking { service.settle(b.id, market) } }
        val key = settlementKey(b)
        later(Duration.ofMinutes(5))
        assertThat(
            service.closePostingIntent(b.id, PostingEvent.SETTLED, "racing", operator),
        ).isEqualTo(PostingIntentCloseOutcome.CLOSED)
        intents.complete(key) // the in-flight command's commit landing after the close
        // Applied all the same, recorded and counted; the close stays on record and the slot is released.
        assertThat(intents.status(key)).isEqualTo(InMemoryIntents.Status.COMPLETED)
        assertThat(intents.closedBy[key]).isEqualTo(operator.id)
        assertThat(intents.lateApplied).containsExactly(key)
        assertThat(intents.history.filter { it[0] == key }.map { it[1] })
            .containsExactly("OPENED", "CLOSED_BY_OPERATOR", "JOURNAL_APPLIED_AFTER_CLOSE")
    }

    @Test
    fun `an open intent defers only its own deal - every other deal accrues and the pass completes`(): Unit =
        runBlocking {
            val blocked = book()
            val other = book()
            service.settle(blocked.id, DealFixtures.approver)
            service.settle(other.id, DealFixtures.approver)
            later(Duration.ofDays(1))
            ledger.loseAnswerFor = blocked.id
            assertThatThrownBy { runBlocking { service.reverse(blocked.id, "wrong rate", DealFixtures.approver) } }
            val run = service.accrueInterest(monday.plusDays(1))
            assertThat(run.deferred).isEqualTo(1)
            assertThat(run.unassessed).isZero()
            assertThat(run.failures).isEmpty()
            assertThat(run.deals).isEqualTo(2)
            assertThat(deals.journals.filter { it.event == PostingEvent.ACCRUED }.map { it.dealId }.toSet())
                .containsExactly(other.id)
        }

    @Test
    fun `a re-issue after a close is the re-issuer's decision - in flight it cannot be closed, nor by them`(): Unit =
        runBlocking {
            val b = book()
            val market = com.openbank.treasury.domain.model.Actor.SIMULATED_MARKET
            ledger.loseAnswerFor = b.id
            assertThatThrownBy { runBlocking { service.settle(b.id, market) } }
            later(Duration.ofMinutes(5))
            assertThat(service.closePostingIntent(b.id, PostingEvent.SETTLED, "first close", operator))
                .isEqualTo(PostingIntentCloseOutcome.CLOSED)
            // The operator re-issues it and the answer is lost again: the intent is theirs, and fresh in time.
            ledger.loseAnswerFor = b.id
            assertThatThrownBy { runBlocking { service.settle(b.id, operator) } }
            val key = settlementKey(b)
            assertThat(intents.rows.getValue(key).first.actor).isEqualTo(operator)
            val thirdPerson = com.openbank.treasury.domain.model.Actor(
                "pavel.backoffice",
                com.openbank.treasury.domain.model.ActorType.HUMAN,
            )
            assertThat(service.closePostingIntent(b.id, PostingEvent.SETTLED, "racing the re-issue", thirdPerson))
                .isEqualTo(PostingIntentCloseOutcome.IN_FLIGHT)
            later(Duration.ofMinutes(5))
            assertThatThrownBy {
                runBlocking { service.closePostingIntent(b.id, PostingEvent.SETTLED, "my own re-issue", operator) }
            }.isInstanceOf(com.openbank.treasury.domain.model.FourEyesViolationException::class.java)
            assertThat(service.closePostingIntent(b.id, PostingEvent.SETTLED, "after the grace", thirdPerson))
                .isEqualTo(PostingIntentCloseOutcome.CLOSED)
        }

    @Test
    fun `an operator-closed reversal keeps the deal from accruing until the reversal is re-issued`(): Unit =
        runBlocking {
            val b = book()
            service.settle(b.id, DealFixtures.approver)
            ledger.loseAnswerFor = b.id // the ledger holds the reversal; treasury never applies it
            assertThatThrownBy { runBlocking { service.reverse(b.id, "wrong rate", DealFixtures.approver) } }
            later(Duration.ofMinutes(5))
            assertThat(service.closePostingIntent(b.id, PostingEvent.REVERSED, "investigating", operator))
                .isEqualTo(PostingIntentCloseOutcome.CLOSED)
            assertThat(deals.rows.getValue(b.id).state).isEqualTo(DealState.SETTLED)
            val run = service.accrueInterest(monday.plusDays(3))
            assertThat(run.deferred).isEqualTo(1)
            assertThat(deals.journals.filter { it.event == PostingEvent.ACCRUED }).isEmpty()
            // The re-issue completes the reversal with the ledger's original journal; nothing to accrue after.
            assertThat(service.reverse(b.id, "wrong rate", DealFixtures.approver).state).isEqualTo(DealState.REVERSED)
        }

    @Test
    fun `a journal recorded while a reversal is being decided stops the reversal before the ledger`(): Unit =
        runBlocking {
            val b = book()
            service.settle(b.id, DealFixtures.approver)
            // An accrual pass already past its per-day check records a journal just as the reversal opens its intent.
            intents.onOpen = {
                intents.onOpen = {}
                deals.recordJournal(
                    com.openbank.treasury.application.port.out.LedgerJournalRef(
                        b.id,
                        PostingEvent.ACCRUED,
                        "treasury:${b.id}:accrued:${monday.plusDays(1)}",
                        java.util.UUID.randomUUID(),
                        clock.instant(),
                        valueDate = monday.plusDays(1),
                    ),
                )
            }
            assertThatThrownBy { runBlocking { service.reverse(b.id, "wrong rate", DealFixtures.approver) } }
                .isInstanceOf(com.openbank.treasury.application.port.out.DealChangedException::class.java)
            val key = intents.rows.values.single { it.first.event == PostingEvent.REVERSED }.first.idempotencyKey
            assertThat(ledger.posted.map { it.idempotencyKey }).doesNotContain(key)
            assertThat(intents.status(key)).isEqualTo(InMemoryIntents.Status.REFUSED)
            assertThat(deals.rows.getValue(b.id).state).isEqualTo(DealState.SETTLED)
        }

    @Test
    fun `a failed reversal blocks a maturity, open or closed by an operator`(): Unit = runBlocking {
        val b = book()
        service.settle(b.id, DealFixtures.approver)
        ledger.loseAnswerFor = b.id // the ledger holds the reversal; treasury never applies it
        assertThatThrownBy { runBlocking { service.reverse(b.id, "wrong rate", DealFixtures.approver) } }
        later(Duration.ofDays(7))
        val market = com.openbank.treasury.domain.model.Actor.SIMULATED_MARKET
        assertThatThrownBy { runBlocking { service.mature(b.id, market) } }
            .isInstanceOf(com.openbank.treasury.application.port.out.DealChangedException::class.java)
        assertThat(
            intents.closeByOperator(
                settlementKeyOf(b, PostingEvent.REVERSED),
                operator,
                "investigating",
                clock.instant(),
            ),
        )
            .isEqualTo(PostingIntentCloseOutcome.CLOSED)
        assertThatThrownBy { runBlocking { service.mature(b.id, market) } }
            .isInstanceOf(com.openbank.treasury.application.port.out.DealChangedException::class.java)
        assertThat(ledger.posted.map { it.event }).doesNotContain(PostingEvent.MATURED)
        assertThat(deals.rows.getValue(b.id).state).isEqualTo(DealState.SETTLED)
    }

    @Test
    fun `an accrual day in flight blocks a reversal, and a reversal in flight defers the accrual`(): Unit =
        runBlocking {
            val b = book()
            service.settle(b.id, DealFixtures.approver)
            later(Duration.ofDays(1))
            // Order 1: the accrual day's answer is lost - its ACCRUE intent stays OPEN.
            ledger.loseAnswerFor = b.id
            val run = service.accrueInterest(monday.plusDays(1))
            assertThat(run.failures).hasSize(1)
            assertThatThrownBy { runBlocking { service.reverse(b.id, "wrong rate", DealFixtures.approver) } }
                .isInstanceOf(com.openbank.treasury.application.port.out.DealChangedException::class.java)
            assertThat(ledger.posted.map { it.event }).doesNotContain(PostingEvent.REVERSED)
            // The reconciler completes the accrual day; now the reversal goes through and offsets it.
            later(Duration.ofMinutes(5))
            assertThat(service.reconcilePostingIntents(clock.instant()).completed).isEqualTo(1)
            assertThat(service.reverse(b.id, "wrong rate", DealFixtures.approver).state).isEqualTo(DealState.REVERSED)
            val reversal = ledger.posted.last { it.event == PostingEvent.REVERSED }
            assertThat(deals.journals.filter { it.event == PostingEvent.ACCRUED }).hasSize(1)
            assertThat(reversal.lines).isNotEmpty()
        }

    @Test
    fun `a settlement whose answer was lost blocks reversing the deal`(): Unit = runBlocking {
        val b = book()
        ledger.loseAnswerFor = b.id // the ledger holds the settlement; the deal still reads BOOKED
        assertThatThrownBy { runBlocking { service.settle(b.id, DealFixtures.approver) } }
        // Reversing an unsettled deal posts nothing, so without the check it would leave the ledger's
        // settlement unreversed behind a REVERSED deal.
        assertThatThrownBy { runBlocking { service.reverse(b.id, "cancel it", DealFixtures.approver) } }
            .isInstanceOf(com.openbank.treasury.application.port.out.DealChangedException::class.java)
        assertThat(deals.rows.getValue(b.id).state).isEqualTo(DealState.BOOKED)
    }

    @Test
    fun `reversing an unsettled deal claims the lifecycle slot, then releases it with no journal`(): Unit =
        runBlocking {
            val b = book()
            assertThat(service.reverse(b.id, "never traded", DealFixtures.approver).state).isEqualTo(DealState.REVERSED)
            val key = settlementKeyOf(b, PostingEvent.REVERSED)
            assertThat(intents.status(key)).isEqualTo(InMemoryIntents.Status.REFUSED)
            assertThat(ledger.posted).isEmpty()
        }

    @Test
    fun `an unposted reversal in flight refuses a settlement, and a settlement in flight refuses it`(): Unit =
        runBlocking {
            val b = book()
            // Reversal first: its claim is OPEN while it decides; a settlement must not post.
            var raced = false
            intents.afterOpen = {
                intents.afterOpen = {}
                raced = true
                assertThatThrownBy { runBlocking { service.settle(b.id, DealFixtures.approver) } }
                    .isInstanceOf(com.openbank.treasury.application.port.out.DealChangedException::class.java)
            }
            service.reverse(b.id, "never traded", DealFixtures.approver)
            assertThat(raced).describedAs("the settlement raced the reversal's open claim").isTrue()
            assertThat(ledger.posted.map { it.event }).doesNotContain(PostingEvent.SETTLED)

            // Settlement first: its intent is OPEN (answer lost); an unposted reversal must not commit.
            val c = book()
            ledger.loseAnswerFor = c.id
            assertThatThrownBy { runBlocking { service.settle(c.id, DealFixtures.approver) } }
            assertThatThrownBy { runBlocking { service.reverse(c.id, "never traded", DealFixtures.approver) } }
                .isInstanceOf(com.openbank.treasury.application.port.out.DealChangedException::class.java)
            assertThat(deals.rows.getValue(c.id).state).isEqualTo(DealState.BOOKED)
        }

    @Test
    fun `an accrual day stuck for 5 hours (ledger outage) is still resumed - it never escalates`(): Unit = runBlocking {
        val b = book()
        service.settle(b.id, DealFixtures.approver)
        later(Duration.ofDays(1))
        ledger.loseAnswerFor = b.id
        assertThat(service.accrueInterest(monday.plusDays(1)).failures).hasSize(1)
        later(Duration.ofHours(5))
        val run = service.reconcilePostingIntents(clock.instant())
        assertThat(run.escalated).isZero()
        assertThat(run.completed).isEqualTo(1)
        assertThat(intents.rows.values.single { it.first.action.name == "ACCRUE" }.second)
            .isEqualTo(InMemoryIntents.Status.COMPLETED)
    }

    @Test
    fun `an accrual journal recorded after its intent was refused is recorded as a late application`(): Unit =
        runBlocking {
            val b = book()
            service.settle(b.id, DealFixtures.approver)
            later(Duration.ofDays(1))
            ledger.beforePost = { key ->
                if (key.contains(":accrued:")) {
                    ledger.beforePost = {}
                    runBlocking { intents.refuse(key, "raced") }
                }
            }
            service.accrueInterest(monday.plusDays(1))
            val key = intents.rows.values.single { it.first.action.name == "ACCRUE" }.first.idempotencyKey
            assertThat(intents.lateApplied).containsExactly(key)
            assertThat(intents.history.filter { it[0] == key }.map { it[1] })
                .containsExactly("OPENED", "REFUSED", "JOURNAL_APPLIED_AFTER_CLOSE")
        }

    private fun settlementKeyOf(deal: Deal, event: PostingEvent) =
        intents.rows.values.map { it.first }.first { it.dealId == deal.id && it.event == event }.idempotencyKey

    @Test
    fun `maturity records and completes its intent`(): Unit = runBlocking {
        val b = book()
        service.settle(b.id, DealFixtures.approver)
        later(Duration.ofDays(7))
        ledger.loseAnswerFor = null
        assertThat(service.mature(b.id, DealFixtures.approver).state).isEqualTo(DealState.MATURED)
        val key = ledger.posted.single { it.event == PostingEvent.MATURED }.idempotencyKey
        assertThat(intents.status(key)).isEqualTo(InMemoryIntents.Status.COMPLETED)
        val (accruals, lifecycle) = intents.rows.values.partition { it.first.action.name == "ACCRUE" }
        assertThat(lifecycle.map { it.first.action.name }).containsExactly("SETTLE", "MATURE")
        // Each accrual day took its own intent before its ledger call, completed when recorded.
        assertThat(accruals).isNotEmpty().allMatch { it.second == InMemoryIntents.Status.COMPLETED }
    }
}
