// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.nostro

import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.BreakAlertPolicy
import com.openbank.treasury.domain.model.BreakChanges
import com.openbank.treasury.domain.model.BreakResolutionKind
import com.openbank.treasury.domain.model.BreakSide
import com.openbank.treasury.domain.model.BusinessDays
import com.openbank.treasury.domain.model.FourEyesViolationException
import com.openbank.treasury.domain.model.LedgerNostroLine
import com.openbank.treasury.domain.model.NostroBreak
import com.openbank.treasury.domain.model.NostroBreakBook
import com.openbank.treasury.domain.model.NostroMatcher
import com.openbank.treasury.domain.model.Side
import com.openbank.treasury.infrastructure.iso20022.Camt053Parser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class NostroBreakBookTest {

    private val statement = Camt053Parser.parse(NostroFixtures.xml())
    private val stmtUuid = UUID.randomUUID()
    private val day1 = LocalDate.parse("2026-09-25") // a Friday
    private val lines = NostroFixtures.ledgerLines()

    private fun reconcile(ledgerLines: List<LedgerNostroLine>) =
        NostroMatcher.match(statement, "1001", ledgerLines, null, null, "not stated")

    private fun observe(
        existing: List<NostroBreak>,
        ledgerLines: List<LedgerNostroLine>,
        today: LocalDate,
    ): BreakChanges = NostroBreakBook.observe(reconcile(ledgerLines), stmtUuid, existing, today) { UUID.randomUUID() }

    private fun BreakChanges.applyTo(existing: List<NostroBreak>): List<NostroBreak> {
        val changed = (resolved + reopened).associateBy { it.id }
        return existing.map { changed[it.id] ?: it } + opened
    }

    @Test
    fun `each unmatched item on either side opens one break, stated from our side`() {
        val c = observe(emptyList(), lines, day1)

        assertThat(c.resolved).isEmpty()
        assertThat(c.opened).hasSize(2)
        val stmt = c.opened.single { it.side == BreakSide.STATEMENT }
        assertThat(stmt.amount).isEqualByComparingTo("5000.00")
        assertThat(stmt.ourSide).isEqualTo(Side.DEBIT) // correspondent CRDT = money into our nostro
        assertThat(stmt.reference).isEqualTo("SYNTH-SVCR-0003")
        assertThat(stmt.statementSequence).isEqualTo(3)
        assertThat(stmt.firstSeenOn).isEqualTo(day1)
        val ledger = c.opened.single { it.side == BreakSide.LEDGER }
        assertThat(ledger.amount).isEqualByComparingTo("42.00")
        assertThat(ledger.ourSide).isEqualTo(Side.CREDIT)
        assertThat(ledger.ledgerLineId).isEqualTo(lines[2].lineId)
    }

    @Test
    fun `observing the same reconciliation again opens nothing and keeps the first-seen day`() {
        val first = observe(emptyList(), lines, day1).applyTo(emptyList())
        val again = observe(first, lines, day1.plusDays(3))
        assertThat(again.isEmpty).isTrue()
        assertThat(first.map { it.firstSeenOn }).containsOnly(day1)
    }

    @Test
    fun `a break resolves the day a later reconciliation matches it`() {
        val first = observe(emptyList(), lines, day1).applyTo(emptyList())
        // The ledger now books the 5000 the correspondent showed, and the 42 fee is reversed away.
        val late = NostroFixtures.line("5000.00", Side.DEBIT, description = "late booking SYNTH-SVCR-0003")
        val c = observe(first, lines.take(2) + late, day1.plusDays(4))

        assertThat(c.opened).isEmpty()
        assertThat(c.resolved).hasSize(2)
        assertThat(c.resolved.map { it.resolvedOn }).containsOnly(day1.plusDays(4))
        val after = c.applyTo(first)
        assertThat(after.filter { it.open }).isEmpty()
        assertThat(after.first().ageBusinessDays(LocalDate.parse("2026-12-31"))).isEqualTo(2) // frozen at resolution
    }

    @Test
    fun `a ledger break outside the statement period is not resolved by this statement`() {
        val elsewhere = NostroBreak(
            id = UUID.randomUUID(),
            breakKey = NostroBreak.ledgerKey(UUID.randomUUID()),
            iban = NostroFixtures.IBAN,
            glCode = "1001",
            currency = "CZK",
            side = BreakSide.LEDGER,
            ourSide = Side.CREDIT,
            amount = BigDecimal.ONE,
            bookingDate = day1.minusDays(10),
            reference = null,
            statementUuid = UUID.randomUUID(),
            statementSequence = null,
            ledgerLineId = UUID.randomUUID(),
            firstSeenOn = day1.minusDays(10),
        )
        val c = observe(listOf(elsewhere), lines, day1)
        assertThat(c.resolved).isEmpty()
    }

    @Test
    fun `a resolved break that is unmatched again reopens with its original first-seen day`() {
        val first = observe(emptyList(), lines, day1).applyTo(emptyList())
        val resolved = first.map { it.copy(resolvedOn = day1.plusDays(1)) }
        val c = observe(resolved, lines, day1.plusDays(5))
        assertThat(c.reopened).hasSize(2)
        assertThat(c.reopened.map { it.firstSeenOn }).containsOnly(day1)
        assertThat(c.opened).isEmpty()
    }

    // --- four-eyes manual resolution ---

    private val anna = Actor("anna.approver", ActorType.HUMAN)
    private val sara = Actor("sara.senior", ActorType.HUMAN)
    private val reason = "  Synthetic E2E demo item, investigated: no real cash movement.  "
    private val at = Instant.parse("2026-10-05T10:00:00Z")

    private fun aBreak() = observe(emptyList(), lines, day1).opened.single { it.side == BreakSide.STATEMENT }

    private fun closed(): NostroBreak = aBreak().proposeResolution(reason, anna, at).approveResolution(sara, day1, at)

    @Test
    fun `a proposal leaves the break open and ageing, a different person's approval closes it as MANUAL`() {
        val policy = BreakAlertPolicy(ageDays = 1, minAmount = BigDecimal.ZERO)
        val later = day1.plusDays(10)
        val proposed = aBreak().proposeResolution(reason, anna, at)

        assertThat(proposed.open).isTrue()
        assertThat(proposed.resolutionProposed).isTrue()
        assertThat(policy.isAged(proposed, later)).isTrue()
        assertThat(proposed.resolutionReason).isEqualTo(reason.trim())

        val r = proposed.approveResolution(sara, later, at)
        assertThat(r.open).isFalse()
        assertThat(r.resolvedOn).isEqualTo(later)
        assertThat(r.resolutionKind).isEqualTo(BreakResolutionKind.MANUAL)
        assertThat(r.proposedBy).isEqualTo("anna.approver")
        assertThat(r.resolvedBy).isEqualTo("sara.senior")
        assertThat(policy.isAged(r, later)).isFalse()
    }

    @Test
    fun `approving a proposal other than the one read is a conflict`() {
        val proposed = aBreak().proposeResolution(reason, anna, at)
        assertThatThrownBy { proposed.approveResolution(sara, day1, at.plusSeconds(1)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("changed since it was read")
    }

    @Test
    fun `the proposer may not approve their own proposal`() {
        val proposed = aBreak().proposeResolution(reason, anna, at)
        assertThatThrownBy {
            proposed.approveResolution(anna, day1, at)
        }.isInstanceOf(FourEyesViolationException::class.java)
    }

    @Test
    fun `approving without a proposal, approving twice, or proposing twice is a conflict`() {
        assertThatThrownBy {
            aBreak().approveResolution(sara, day1, at)
        }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            closed().approveResolution(sara, day1, at)
        }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            closed().proposeResolution(reason, anna, at)
        }.isInstanceOf(IllegalStateException::class.java)
        val proposed = aBreak().proposeResolution(reason, anna, at)
        assertThatThrownBy {
            proposed.proposeResolution(reason, sara, at)
        }.isInstanceOf(IllegalStateException::class.java)
        val matched = aBreak().matched(day1)
        assertThatThrownBy {
            matched.proposeResolution(reason, anna, at)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a rejection clears the proposal and the break stays open for a new one`() {
        val rejected = aBreak().proposeResolution(
            reason,
            anna,
            at,
        ).rejectResolution("Not explained yet, ask again.", sara)
        assertThat(rejected.open).isTrue()
        assertThat(rejected.resolutionProposed).isFalse()
        assertThat(rejected.resolutionReason).isNull()
        assertThatThrownBy { aBreak().rejectResolution("Nothing to reject here.", sara) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(rejected.proposeResolution(reason, sara, at).proposedBy).isEqualTo("sara.senior")
    }

    @Test
    fun `a reason shorter than 10 or longer than 500 characters after trimming is refused`() {
        val brk = aBreak()
        listOf("", "   short  ", "123456789", "x".repeat(501)).forEach { bad ->
            assertThatThrownBy { brk.proposeResolution(bad, anna, at) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("reason")
        }
        assertThat(brk.proposeResolution("1234567890", anna, at).resolutionReason).isEqualTo("1234567890")
        assertThat(brk.proposeResolution("x".repeat(500), anna, at).resolutionReason).hasSize(500)
    }

    @Test
    fun `only a person may propose or approve, never an agent or a service-account`() {
        val machines =
            listOf(
                Actor.fromPrincipalName("agent:treasury-dealing-assistant"),
                Actor.fromPrincipalName("service-account-x"),
            )
        machines.forEach { m ->
            assertThatThrownBy {
                aBreak().proposeResolution(reason, m, at)
            }.isInstanceOf(ActorNotPermittedException::class.java)
            assertThatThrownBy { aBreak().proposeResolution(reason, anna, at).approveResolution(m, day1, at) }
                .isInstanceOf(ActorNotPermittedException::class.java)
        }
    }

    @Test
    fun `a manual resolution cannot be half-recorded or self-approved by construction`() {
        val brk = aBreak()
        assertThatThrownBy { brk.copy(resolvedOn = day1, resolutionKind = BreakResolutionKind.MANUAL) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val proposed = brk.proposeResolution(reason, anna, at)
        assertThatThrownBy {
            proposed.copy(resolvedOn = day1, resolutionKind = BreakResolutionKind.MANUAL, resolvedBy = "anna.approver")
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { brk.copy(resolutionKind = BreakResolutionKind.MATCHED) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a manually resolved break listed again by a later reconciliation stays resolved (sticky)`() {
        val first = observe(emptyList(), lines, day1).applyTo(emptyList())
        val closedAll = first.map {
            it.proposeResolution(reason, anna, at).approveResolution(sara, day1.plusDays(1), at)
        }

        // The same statement re-reconciled with the same ledger: both items are listed again.
        val c = observe(closedAll, lines, day1.plusDays(5))

        assertThat(c.reopened).isEmpty()
        assertThat(c.opened).isEmpty()
        assertThat(c.resolved).isEmpty()
    }

    @Test
    fun `a match while a proposal is pending resolves it as MATCHED and clears the proposal`() {
        val first = observe(emptyList(), lines, day1).applyTo(emptyList())
        val pending = first.map { it.proposeResolution(reason, anna, at) }
        val late = NostroFixtures.line("5000.00", Side.DEBIT, description = "late booking SYNTH-SVCR-0003")
        val c = observe(pending, lines.take(2) + late, day1.plusDays(4))
        assertThat(c.resolved).isNotEmpty()
        c.resolved.forEach {
            assertThat(it.resolutionKind).isEqualTo(BreakResolutionKind.MATCHED)
            assertThat(it.proposedBy).isNull()
        }
    }

    @Test
    fun `a matched break that is unmatched again reopens with every resolution field cleared`() {
        val first = observe(emptyList(), lines, day1).applyTo(emptyList())
        val late = NostroFixtures.line("5000.00", Side.DEBIT, description = "late booking SYNTH-SVCR-0003")
        val matched = observe(first, lines.take(2) + late, day1.plusDays(4)).applyTo(first)
        assertThat(matched.filter { !it.open }.map { it.resolutionKind }).containsOnly(BreakResolutionKind.MATCHED)

        val c = observe(matched, lines, day1.plusDays(6))

        assertThat(c.reopened).isNotEmpty()
        c.reopened.forEach {
            assertThat(it.open).isTrue()
            assertThat(it.resolutionKind).isNull()
            assertThat(it.resolvedBy).isNull()
            assertThat(it.firstSeenOn).isEqualTo(day1)
        }
    }

    @Test
    fun `business days skip the weekend and count neither the start day nor a backwards span`() {
        val fri = LocalDate.parse("2026-09-25")
        assertThat(BusinessDays.between(fri, fri)).isZero()
        assertThat(BusinessDays.between(fri, fri.plusDays(1))).isZero() // Saturday
        assertThat(BusinessDays.between(fri, fri.plusDays(3))).isEqualTo(1) // Monday
        assertThat(BusinessDays.between(fri, fri.plusDays(7))).isEqualTo(5)
        assertThat(BusinessDays.between(fri, fri.minusDays(3))).isZero()
    }

    @Test
    fun `the policy alerts at the age threshold, not a day before, and only over the amount`() {
        val b = observe(emptyList(), lines, day1).opened.single { it.side == BreakSide.STATEMENT } // 5000
        val policy = BreakAlertPolicy(3, BigDecimal("1000"))
        val tue = LocalDate.parse("2026-09-29") // 2 business days after Friday
        val wed = LocalDate.parse("2026-09-30") // 3
        assertThat(policy.isAged(b, tue)).isFalse()
        assertThat(policy.isAged(b, wed)).isTrue()
        assertThat(BreakAlertPolicy(3, BigDecimal("5000.01")).isAged(b, wed)).isFalse()
        assertThat(policy.isAged(b.copy(resolvedOn = tue), wed)).isFalse()
    }

    @Test
    fun `a nonsensical policy is refused at construction`() {
        assertThatThrownBy { BreakAlertPolicy(0, BigDecimal.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { BreakAlertPolicy(3, BigDecimal("-1")) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
