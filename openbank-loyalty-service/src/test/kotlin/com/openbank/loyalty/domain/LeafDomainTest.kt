// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class LeavesTest {
    @Test
    fun `leaves cannot be negative`() {
        assertThatThrownBy { Leaves.of(-1) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `subtraction below zero is refused rather than wrapping`() {
        assertThatThrownBy { Leaves.of(1) - Leaves.of(2) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * The closed-loop invariant, asserted structurally. If someone adds `toMoney()`,
     * `toMinorUnits()`, a currency field or a rate multiplication to [Leaves], this test fails —
     * which is the only place that boundary can be defended, because prose in a KDoc is not a
     * control. ADR-0282 D1 depends on it: a unit with a fiat price is a unit of account.
     */
    @Test
    fun `Leaves exposes no conversion toward money`() {
        val forbidden = listOf("toMoney", "toMinorUnits", "toBigDecimal", "toCurrency", "times", "div")
        val declared = Leaves::class.java.declaredMethods.map { it.name }
        assertThat(declared).doesNotContainAnyElementsOf(forbidden)
    }
}

class LeafEarnSourceTest {
    @Test
    fun `the catalogue is fully initialised and has no null entries`() {
        assertThat(LeafEarnSource.ALL).doesNotContainNull()
        assertThat(LeafEarnSource.ALL.map { it.id }).doesNotHaveDuplicates()
    }

    /**
     * Exhaustiveness: a variant added to the sealed class without being registered in `ALL` fails
     * here. The `when` below has no `else`, so it also stops compiling — two independent guards
     * on the same property, which is deliberate for a catalogue whose whole job is being closed.
     */
    @Test
    fun `every variant is registered and reachable by id`() {
        LeafEarnSource.ALL.forEach { source ->
            val label = when (source) {
                LeafEarnSource.SavingsRateSustained -> "savings-rate"
                LeafEarnSource.EmergencyBufferReached -> "buffer"
                LeafEarnSource.OnTimeRepayment -> "repayment"
                LeafEarnSource.SavingsGoalReached -> "goal"
                LeafEarnSource.CurrencyDiversification -> "diversification"
                LeafEarnSource.EducationalContentCompletion -> "education"
                LeafEarnSource.LoginStreak -> "streak"
                LeafEarnSource.TenureAnniversary -> "tenure"
                LeafEarnSource.FeedbackGiven -> "feedback"
                LeafEarnSource.QualifiedReferral -> "referral"
            }
            assertThat(label).isNotBlank()
            assertThat(LeafEarnSource.byId(source.id)).isSameAs(source)
        }
    }

    /**
     * ADR-0220 D3 rule 1 / ADR-0282 D3. Reads the identifiers rather than trusting review: a
     * variant named for spend or credit uptake fails here on the day it is written.
     */
    @Test
    fun `no earn source rewards spend or credit uptake`() {
        val banned = listOf("SPEND", "CARD", "PURCHASE", "CREDIT_UPTAKE", "UTILISATION", "UTILIZATION", "OVERDRAFT")
        LeafEarnSource.ALL.forEach { source ->
            assertThat(banned.none { source.id.contains(it) })
                .withFailMessage("earn source %s rewards credit or spend, which ADR-0282 D3 forbids", source.id)
                .isTrue()
        }
    }
}

class EarnCatalogTest {
    @Test
    fun `every declared earn source has a reviewed rule`() {
        LeafEarnSource.ALL.forEach { source ->
            val rule = EarnCatalog.ruleFor(source)
            assertThat(rule.leaves.value).isPositive()
            assertThat(rule.validity).isEqualTo(EarnRule.DEFAULT_VALIDITY)
        }
    }
}

class AnnualCapTest {
    @Test
    fun `an award that fits under the cap is granted`() {
        val decision = AnnualCap.evaluate(Leaves.of(100), Leaves.of(50))
        assertThat(decision).isEqualTo(AnnualCap.Decision.Grant)
    }

    /**
     * The load-bearing assertion of the whole cap design: refusal is its OWN value, so no caller
     * can read it as a grant. `is Capped` and `== Grant` cannot both hold, and a boolean would
     * have let them.
     */
    @Test
    fun `an award that would exceed the cap is Capped and is not a Grant`() {
        val alreadyEarned = AnnualCap.PER_PARTY_PER_YEAR - Leaves.of(10)
        val decision = AnnualCap.evaluate(alreadyEarned, Leaves.of(50))
        assertThat(decision).isInstanceOf(AnnualCap.Decision.Capped::class.java)
        assertThat(decision).isNotEqualTo(AnnualCap.Decision.Grant)
        assertThat((decision as AnnualCap.Decision.Capped).remaining).isEqualTo(Leaves.of(10))
    }

    @Test
    fun `a party already at the cap has zero headroom, never negative`() {
        val decision = AnnualCap.evaluate(AnnualCap.PER_PARTY_PER_YEAR + Leaves.of(1), Leaves.of(1))
        assertThat((decision as AnnualCap.Decision.Capped).remaining).isEqualTo(Leaves.ZERO)
    }
}

class BenefitCatalogTest {
    @Test
    fun `a benefit is priced in leaves and in nothing else`() {
        val fields = Benefit::class.java.declaredFields.map { it.name.lowercase() }
        assertThat(fields).noneMatch { it.contains("currency") || it.contains("amount") || it.contains("rate") }
    }

    @Test
    fun `every catalogue entry names the engine that delivers it`() {
        assertThat(BenefitCatalog.ALL).isNotEmpty
        BenefitCatalog.ALL.values.forEach { assertThat(it.engine).isNotNull() }
        assertThat(
            BenefitCatalog.ALL.values.map {
                it.engine
            },
        ).containsExactlyInAnyOrder(*BenefitEngine.entries.toTypedArray())
    }
}

class LeafLedgerTest {
    private val party = UUID.randomUUID()
    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun lot(at: Instant, amount: Int, validity: Duration = Duration.ofDays(365)) = LeafLedgerEntry(
        id = UUID.randomUUID(),
        partyId = party,
        type = LeafEntryType.EARN,
        leaves = Leaves.of(amount),
        remaining = Leaves.of(amount),
        earnSource = LeafEarnSource.LoginStreak,
        ruleVersion = EarnCatalog.RULE_VERSION,
        correlationEventId = UUID.randomUUID(),
        occurredAt = at,
        expiresAt = at.plus(validity),
    )

    @Test
    fun `balance counts only unspent unexpired lots`() {
        val lots = listOf(
            lot(t0, 100, Duration.ofDays(1)),
            lot(t0.plusSeconds(60), 50),
        )
        assertThat(LeafLedger.balance(lots, t0.plusSeconds(120))).isEqualTo(Leaves.of(150))
        // Two days later the first lot has expired and stops counting.
        assertThat(LeafLedger.balance(lots, t0.plus(Duration.ofDays(2)))).isEqualTo(Leaves.of(50))
    }

    @Test
    fun `allocation consumes the oldest lot first`() {
        val oldest = lot(t0, 100)
        val newer = lot(t0.plusSeconds(600), 100)
        val allocation = LeafLedger.allocate(listOf(newer, oldest), Leaves.of(150), t0.plusSeconds(700))
        val debits = (allocation as LeafLedger.Allocation.Resolved).debits
        assertThat(debits.map { it.lotId }).containsExactly(oldest.id, newer.id)
        assertThat(debits.map { it.amount.value }).containsExactly(100, 50)
    }

    @Test
    fun `an unaffordable redemption reports the shortfall instead of allocating partially`() {
        val allocation = LeafLedger.allocate(listOf(lot(t0, 10)), Leaves.of(300), t0.plusSeconds(1))
        assertThat(allocation).isInstanceOf(LeafLedger.Allocation.Insufficient::class.java)
        assertThat((allocation as LeafLedger.Allocation.Insufficient).available).isEqualTo(Leaves.of(10))
    }

    @Test
    fun `an expired lot is never spendable, however large`() {
        val expired = lot(t0, 10_000, Duration.ofDays(1))
        val at = t0.plus(Duration.ofDays(2))
        assertThat(LeafLedger.balance(listOf(expired), at)).isEqualTo(Leaves.ZERO)
        assertThat(LeafLedger.expirableLots(listOf(expired), at)).containsExactly(expired)
    }
}

class LeafLedgerEntryTest {
    @Test
    fun `an EARN entry without an expiry cannot be constructed`() {
        assertThatThrownBy {
            LeafLedgerEntry(
                id = UUID.randomUUID(),
                partyId = UUID.randomUUID(),
                type = LeafEntryType.EARN,
                leaves = Leaves.of(10),
                remaining = Leaves.of(10),
                earnSource = LeafEarnSource.LoginStreak,
                ruleVersion = "v1",
                correlationEventId = UUID.randomUUID(),
                occurredAt = Instant.EPOCH,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a BURN entry must name the benefit it paid for`() {
        assertThatThrownBy {
            LeafLedgerEntry(
                id = UUID.randomUUID(),
                partyId = UUID.randomUUID(),
                type = LeafEntryType.BURN,
                leaves = Leaves.of(10),
                remaining = Leaves.ZERO,
                ruleVersion = "v1",
                correlationEventId = UUID.randomUUID(),
                occurredAt = Instant.EPOCH,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
