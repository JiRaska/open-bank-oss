// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.parity

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.CorrectionDelta
import com.openbank.treasury.domain.model.CorrectionStaleException
import com.openbank.treasury.domain.model.CorrectionStatus
import com.openbank.treasury.domain.model.CorrectionTerms
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealCorrection
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.DealTransition
import com.openbank.treasury.domain.model.FourEyesViolationException
import com.openbank.treasury.domain.model.FullUnwindPinning
import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.MatrixPostings
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.PostingLine
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.model.RecordedJournal
import com.openbank.treasury.domain.model.ResolvedAccount
import com.openbank.treasury.domain.model.ReversalAccountsUnknownException
import com.openbank.treasury.domain.model.Side
import com.openbank.treasury.domain.reference.Ifrs9Category
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * ADR-0344 D7 corrections, pure: four-eyes on the decision, and the delta journals as the difference
 * between what the new and the old version imply for everything the deal has already posted.
 */
class CorrectionDeltaTest {
    private val fixture: JsonNode = ParityFixtures.fixtures().map { it.second }
        .first { it["id"].asText().startsWith("irs-czk-unadjusted") }
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private val dealer = Actor("dana.dealer", ActorType.HUMAN)
    private val submitter = Actor("sue.submitter", ActorType.HUMAN)
    private val editor = Actor("ed.editor", ActorType.HUMAN)
    private val backOffice = Actor("bert.backoffice", ActorType.HUMAN)
    private val approver = Actor("olga.approver", ActorType.HUMAN)
    private val agent = Actor("agent:treasury-drafter", ActorType.AI_AGENT)

    private fun booked(): Deal = ParityFixtures.deal(fixture["deal"]).let { d ->
        d.copy(
            state = DealState.SETTLED,
            createdBy = dealer,
            submittedBy = submitter,
            approvedBy = Actor("anna.approver", ActorType.HUMAN),
            history = listOf(
                DealTransition(null, DealState.DRAFT, dealer, at),
                DealTransition(DealState.DRAFT, DealState.DRAFT, editor, at, "draft edited (terms version 2)"),
                DealTransition(DealState.DRAFT, DealState.PENDING_APPROVAL, submitter, at),
            ),
            scheduleVersion = 1,
        )
    }

    private fun flows(deal: Deal): List<CashFlow> = runBlocking {
        ParityFixtures.scheduleService(
            fixture,
            deal,
        ).generate(deal.copy(scheduleVersion = 0), ParityFixtures.fixings(fixture)).flows
    }.filter { it.amount != null }

    private fun bumpedRate(deal: Deal): CorrectionTerms = CorrectionTerms(
        legs = deal.legs.mapIndexed { i, l ->
            val fixed = deal.legs.indexOfFirst { it.rate.rate != null }
            if (i == fixed) l.copy(rate = l.rate.copy(rate = checkNotNull(l.rate.rate) + BigDecimal("0.25"))) else l
        },
    )

    private fun proposal(deal: Deal, by: Actor = backOffice, unwind: Boolean = false) = DealCorrection.propose(
        UUID.randomUUID(),
        deal,
        if (unwind) CorrectionTerms() else bumpedRate(deal),
        unwind,
        "rate captured wrong",
        deal.valueDate,
        by,
        at,
    )

    @Test
    fun `four-eyes - the proposer, every term author, an earlier correction's author and an AI agent are refused`() {
        val deal = booked()
        val c = proposal(deal)
        listOf(backOffice, dealer, submitter, editor).forEach { who ->
            assertThatThrownBy { c.approve(who, deal, emptySet(), at) }
                .describedAs(who.id).isInstanceOf(FourEyesViolationException::class.java)
            assertThatThrownBy { c.reject(who, deal, emptySet(), "no", at) }
                .describedAs(who.id).isInstanceOf(FourEyesViolationException::class.java)
        }
        assertThatThrownBy { c.approve(approver, deal, setOf(approver.id), at) }
            .isInstanceOf(FourEyesViolationException::class.java)
        assertThatThrownBy {
            c.approve(agent, deal, emptySet(), at)
        }.isInstanceOf(ActorNotPermittedException::class.java)
        // An agent may PROPOSE, and is refused as an agent (not as the proposer) when it tries to decide its own.
        val byAgent = proposal(deal, by = agent)
        assertThatThrownBy { byAgent.approve(agent, deal, emptySet(), at) }
            .isInstanceOf(ActorNotPermittedException::class.java)
        assertThat(c.approve(approver, deal, emptySet(), at).status).isEqualTo(CorrectionStatus.APPROVED)
    }

    @Test
    fun `a correction proposed against an older version is stale, and a decided one cannot be decided again`() {
        val deal = booked()
        val c = proposal(deal)
        assertThatThrownBy { c.approve(approver, deal.copy(version = deal.version + 1), emptySet(), at) }
            .isInstanceOf(CorrectionStaleException::class.java)
        val rejected = c.reject(approver, deal, emptySet(), "not needed", at)
        assertThatThrownBy {
            rejected.approve(approver, deal, emptySet(), at)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `only a live leg-described ACTUAL deal is corrected - a legacy deal and a cancelled one are refused`() {
        val deal = booked()
        assertThatThrownBy {
            proposal(deal.copy(state = DealState.CANCELLED))
        }.isInstanceOf(IllegalStateException::class.java)
        val legacy = deal.copy(product = ProductType.MM_PLACEMENT, legs = emptyList())
        assertThatThrownBy {
            DealCorrection.Companion.propose(
                UUID.randomUUID(),
                legacy,
                bumpedRate(deal),
                false,
                "x",
                deal.valueDate,
                backOffice,
                at,
            )
        }
            .isInstanceOf(Exception::class.java)
    }

    @Test
    fun `an unchanged correction implies no delta, and a rate change posts exactly the difference per event`() {
        val deal = booked()
        val base = flows(deal)
        val reached = CorrectionDelta.Reached(true, true, MatrixPostings.lastPaymentDate(deal, base), false)
        val id = UUID.randomUUID()
        assertThat(CorrectionDelta.compute(id, deal, base, deal, base, Ifrs9Category.AMORTISED_COST, reached)).isEmpty()

        val target = deal.withCorrectedTerms(bumpedRate(deal))
        val corrected = flows(target)
        val deltas = CorrectionDelta.compute(id, deal, base, target, corrected, Ifrs9Category.AMORTISED_COST, reached)
        assertThat(deltas).isNotEmpty
        assertThat(deltas.map { it.spec.event }.toSet()).containsExactly(PostingEvent.CORRECTION)
        assertThat(deltas.map { it.spec.idempotencyKey }).doesNotHaveDuplicates()
        assertThat(deltas.map { it.spec.idempotencyKey }).allMatch { it.length <= MAX_LEDGER_KEY }
        // old postings + delta == what the new version implies, account by account
        assertThat(net(implied(deal, base, reached)) + net(deltas.flatMap { it.spec.lines }))
            .isEqualTo(net(implied(target, corrected, reached)))
        // Only interest changed: settlement is untouched.
        assertThat(deltas.map { it.sourceEvent }).doesNotContain(PostingEvent.SETTLEMENT)
    }

    @Test
    fun `a full unwind reverses everything the deal posted, leaving every account at zero`() {
        val deal = booked()
        val base = flows(deal)
        val reached = CorrectionDelta.Reached(true, true, MatrixPostings.lastPaymentDate(deal, base), false)
        val deltas = CorrectionDelta.compute(
            UUID.randomUUID(),
            deal,
            base,
            null,
            emptyList(),
            Ifrs9Category.AMORTISED_COST,
            reached,
        )
        val total = net(implied(deal, base, reached)).toMutableMap()
        net(deltas.flatMap { it.spec.lines }).forEach { (k, v) -> total.merge(k, v, BigDecimal::add) }
        assertThat(total.values.filter { it.signum() != 0 }).isEmpty()
        val unwound = deal.correct(CorrectionTerms(), true, approver, "booked in error", at)
        assertThat(unwound.state).isEqualTo(DealState.REVERSED)
        assertThat(unwound.version).isEqualTo(deal.version + 1)
    }

    @Test
    fun `a full unwind clears each historical GL account by what it received, when the map moved mid-life`() {
        val deal = booked()
        val base = flows(deal)
        val reached = CorrectionDelta.Reached(true, true, MatrixPostings.lastPaymentDate(deal, base), false)
        val category = Ifrs9Category.AMORTISED_COST
        val posted = CorrectionDelta.postedSpecs(deal, base, category, reached)
        val accruals = posted.filterKeys { it.first == PostingEvent.ACCRUAL }.keys.map { it.third }.sorted()
        assertThat(accruals.size).isGreaterThan(1)
        val repointed = accruals[accruals.size / 2]

        // Accruals before the re-point landed on account A of their class, the later ones on B.
        fun account(spec: JournalSpec, date: java.time.LocalDate) = spec.lines.map { l ->
            val moved = spec.event == PostingEvent.ACCRUAL && !date.isBefore(repointed)
            ResolvedAccount(
                l.accountingClass,
                l.role,
                l.currency,
                UUID.nameUUIDFromBytes(
                    "${l.accountingClass}/${l.role}/${l.currency}/${if (moved) "B" else "A"}".toByteArray(),
                ),
            )
        }
        val recorded = posted.flatMap { (slot, specs) ->
            specs.map { it.idempotencyKey to RecordedJournal(UUID.randomUUID(), account(it, slot.third)) }
        }.toMap()
        val deltas = CorrectionDelta.compute(UUID.randomUUID(), deal, base, null, emptyList(), category, reached)
        val pinned = deltas.map { d ->
            FullUnwindPinning.pin(
                d.spec,
                posted[Triple(d.sourceEvent, d.key, d.valueDate)].orEmpty(),
                emptyList(),
                recorded,
            )
        }

        val postedLegs = posted.flatMap { (slot, specs) ->
            specs.flatMap { spec ->
                spec.lines.zip(account(spec, slot.third)).map { (l, a) -> l.copy(glAccountId = a.glAccountId) }
            }
        }
        assertThat(netPerAccount(postedLegs + pinned.flatMap { it.lines }).filterValues { it.signum() != 0 }).isEmpty()
        val accountsPerClass = pinned.flatMap { it.lines }
            .groupBy { Triple(it.accountingClass, it.role, it.currency) }
            .mapValues { (_, ls) -> ls.mapNotNull { it.glAccountId }.toSet() }
        assertThat(accountsPerClass.values.any { it.size == 2 })
            .describedAs("an accrual class unwound on both its historical accounts")
            .isTrue()

        val withoutOne = recorded - posted.getValue(Triple(PostingEvent.ACCRUAL, repointed.toString(), repointed))
            .first().idempotencyKey
        val slot = deltas.first { it.sourceEvent == PostingEvent.ACCRUAL && it.valueDate == repointed }
        assertThatThrownBy {
            FullUnwindPinning.pin(
                slot.spec,
                posted[Triple(slot.sourceEvent, slot.key, slot.valueDate)].orEmpty(),
                emptyList(),
                withoutOne,
            )
        }.isInstanceOf(ReversalAccountsUnknownException::class.java)
    }

    private fun netPerAccount(legs: List<PostingLine>): Map<UUID, BigDecimal> =
        legs.groupBy { checkNotNull(it.glAccountId) { "every leg is pinned to a recorded account" } }
            .mapValues { (_, ls) ->
                ls.fold(BigDecimal.ZERO) { acc, l -> if (l.side == Side.DEBIT) acc + l.amount else acc - l.amount }
            }

    @Test
    fun `the changed slots are exactly those whose economics differ`() {
        val deal = booked()
        val base = flows(deal)
        assertThat(CorrectionDelta.changes(base, base)).isEmpty()
        val target = deal.withCorrectedTerms(bumpedRate(deal))
        val changed = CorrectionDelta.changes(base, flows(target))
        assertThat(changed).isNotEmpty
        assertThat(changed.map { it.slot.legIndex }.toSet()).containsExactly(
            deal.legs.indexOfFirst {
                it.rate.rate !=
                    null
            },
        )
    }

    private fun implied(deal: Deal, flows: List<CashFlow>, reached: CorrectionDelta.Reached) = buildList {
        MatrixPostings.trade(deal)?.let { addAll(it.lines) }
        MatrixPostings.settlement(deal, flows, Ifrs9Category.AMORTISED_COST)?.let { addAll(it.lines) }
        MatrixPostings.run(
            deal,
            flows,
            Ifrs9Category.AMORTISED_COST,
            null,
            emptySet(),
            checkNotNull(reached.lastAccrual),
        )
            .forEach { addAll(it.spec.lines) }
    }

    private fun net(lines: List<com.openbank.treasury.domain.model.PostingLine>): Map<String, BigDecimal> {
        val out = sortedMapOf<String, BigDecimal>()
        lines.forEach { l ->
            out.merge(
                "${l.accountingClass}/${l.role}/${l.currency}",
                if (l.side ==
                    Side.DEBIT
                ) {
                    l.amount
                } else {
                    l.amount.negate()
                },
                BigDecimal::add,
            )
        }
        return out.filterValues { it.signum() != 0 }.mapValues { it.value.stripTrailingZeros() }
    }

    private operator fun Map<String, BigDecimal>.plus(other: Map<String, BigDecimal>): Map<String, BigDecimal> {
        val out = sortedMapOf<String, BigDecimal>()
        (this.entries + other.entries).forEach { (k, v) -> out.merge(k, v, BigDecimal::add) }
        return out.filterValues { it.signum() != 0 }.mapValues { it.value.stripTrailingZeros() }
    }

    private companion object {
        const val MAX_LEDGER_KEY = 128
    }
}
