// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.exit

import com.openbank.pension.domain.model.PayoutForm
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.JurisdictionPack
import com.openbank.pension.infrastructure.pack.JurisdictionPackLoader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ExitDomainTest {

    private val now = Instant.parse("2026-10-09T10:00:00Z")
    private val today = LocalDate.parse("2026-10-09")
    private val dps: JurisdictionPack = JurisdictionPackLoader.loadAll().first { it.productLine == ProductLine.DPS }

    private fun withFees(feeRate: String, fixed: String, max: String?) = dps.copy(
        exit = dps.exit!!.copy(
            termination = dps.exit!!.termination.copy(
                feeRate = BigDecimal(feeRate),
                fixedFee = BigDecimal(fixed),
                maxFee = max?.let(::BigDecimal),
            ),
        ),
    )

    private val balance = IncentiveBalance(
        stateIncentivesToReturn = BigDecimal("12345.67"),
        stateIncentivesReceived = BigDecimal("12345.67"),
        deductedContributionsByYear = mapOf(
            2014 to BigDecimal("24000"),
            2017 to BigDecimal("10000"),
            2026 to BigDecimal("6000"),
        ),
        employerExemptByYear = mapOf(2026 to BigDecimal("1000.01")),
        ownContributionsNotDeducted = BigDecimal("50000"),
    )

    @Test
    fun `termination quote deducts fee, clawback and recapture inside the window, rounded per component`() {
        val q = ExitCalculator.terminationQuote(
            withFees("0.01", "100", "1000"),
            BigDecimal("200000.005"),
            balance,
            2026,
        )
        assertThat(q.redemptionValue).isEqualByComparingTo("200000.00")
        assertThat(q.surrenderFee).isEqualByComparingTo("1000.00") // 2000 + 100 capped at 1000
        assertThat(q.incentiveReturn).isEqualByComparingTo("12345.67")
        // years 2017..2026 only: (10000 + 6000) * 0.15; 2014 is outside the 10-year window
        assertThat(q.deductionRecapture).isEqualByComparingTo("2400.00")
        assertThat(q.employerExemptRecapture).isEqualByComparingTo("150.00") // 150.0015 -> HALF_EVEN
        assertThat(q.netPayout + q.totalDeductions).isEqualByComparingTo(q.redemptionValue)
        assertThat(q.shortfall).isEqualByComparingTo("0")
    }

    @Test
    fun `deductions larger than the pot pay nothing and report the shortfall`() {
        val q = ExitCalculator.terminationQuote(dps, BigDecimal("10000"), balance, 2026)
        assertThat(q.netPayout).isEqualByComparingTo("0")
        assertThat(q.shortfall).isEqualByComparingTo(q.totalDeductions - BigDecimal("10000"))
    }

    @Test
    fun `a pack without exit rules fails closed`() {
        assertThatThrownBy { ExitCalculator.terminationQuote(dps.copy(exit = null), BigDecimal.TEN, balance, 2026) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a partial withdrawal is taxed on its prorated share of the gains only`() {
        val q = ExitCalculator.payoutQuote(
            dps,
            PayoutForm.EARLY_WITHDRAWAL,
            BigDecimal("200000"),
            BigDecimal("50000"),
            balance,
        )
        // gains = 200000 - 50000 - 12345.67 = 137654.33; a quarter of it is taxable
        assertThat(q.taxableAmount).isEqualByComparingTo("34413.58")
        assertThat(q.taxWithheld).isEqualByComparingTo("5162.04")
        assertThat(q.netAmount).isEqualByComparingTo(q.grossAmount - q.taxWithheld)
    }

    @Test
    fun `a form the pack does not allow, or more than the value, is refused`() {
        val dip = JurisdictionPackLoader.loadAll().first { it.productLine == ProductLine.DIP }
        assertThatThrownBy {
            ExitCalculator.payoutQuote(dip, PayoutForm.ANNUITY, BigDecimal.TEN, BigDecimal.TEN, balance)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            ExitCalculator.payoutQuote(dps, PayoutForm.LUMP_SUM, BigDecimal.TEN, BigDecimal("11"), balance)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `split never creates or loses a cent`() {
        val parts = ExitMoney.split(
            BigDecimal("100.00"),
            listOf(BigDecimal("33.333"), BigDecimal("33.333"), BigDecimal("33.334")),
        )
        assertThat(parts.fold(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00")
        assertThat(ExitMoney.splitEvenly(BigDecimal("1000.00"), 7).fold(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("1000.00")
    }

    @Test
    fun `a schedule is the quote cut into months and sums back to it exactly`() {
        val q = ExitCalculator.payoutQuote(
            dps,
            PayoutForm.PHASED_WITHDRAWAL,
            BigDecimal("100000.01"),
            BigDecimal("100000.01"),
            balance,
            13,
        )
        val request = PayoutRequest.quote(UUID.randomUUID(), UUID.randomUUID(), q, 7, now)
            .confirm("CZ6508000000192000145399", "sca", "k1", today, now)
        val plan = request.schedule!!
        assertThat(plan.installments).hasSize(13)
        assertThat(plan.totalGross).isEqualByComparingTo(q.grossAmount)
        assertThat(plan.totalTax).isEqualByComparingTo(q.taxWithheld)
        assertThat(plan.totalNet).isEqualByComparingTo(q.netAmount)
        assertThat(plan.installments.first().dueDate).isEqualTo(LocalDate.parse("2026-11-01"))
        assertThat(plan.overdue(LocalDate.parse("2026-11-02")).map { it.seq }).containsExactly(1)
    }

    @Test
    fun `payout transitions are guarded and an unsettled payout cannot complete`() {
        val q = ExitCalculator.payoutQuote(
            dps,
            PayoutForm.LUMP_SUM,
            BigDecimal("1000"),
            BigDecimal("1000"),
            IncentiveBalance.EMPTY,
        )
        val quoted = PayoutRequest.quote(UUID.randomUUID(), UUID.randomUUID(), q, 7, now)
        assertThatThrownBy { quoted.complete(now) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { quoted.confirm("CZ6508000000192000145399", "s", "k", today, now.plusSeconds(8 * 86_400)) }
            .isInstanceOf(IllegalStateException::class.java)
        val paid = quoted.confirm("CZ6508000000192000145399", "s", "k", today, now)
            .markRedeemed(BigDecimal("1000"), now).markPaid("ref", now)
        assertThat(paid.complete(now).status).isEqualTo(PayoutStatus.COMPLETED)
    }

    @Test
    fun `a termination notice is signed once, before expiry, and the hash binds its amounts`() {
        val q = ExitCalculator.terminationQuote(dps, BigDecimal("5000"), IncentiveBalance.EMPTY, 2026)
        val notice = TerminationNotice.quote(UUID.randomUUID(), UUID.randomUUID(), q, 7, now)
        assertThat(notice.quoteHash).hasSize(64)
        assertThat(notice.copy(quote = q.copy(netPayout = BigDecimal("1.00"))).quoteHash).isNotEqualTo(notice.quoteHash)
        assertThatThrownBy { notice.sign("X", "s", "k", 30, now.plusSeconds(7 * 86_400), today) }
            .isInstanceOf(IllegalStateException::class.java)
        val signed = notice.sign("X", "s", "k", 30, now, today)
        assertThat(signed.effectiveDate).isEqualTo(today.plusDays(30))
        assertThatThrownBy {
            signed.sign("X", "s", "k", 30, now, today)
        }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { signed.markPaid("r", now) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(signed.markRedeemed(BigDecimal("4990"), now).navVariance).isEqualByComparingTo("-10.00")
    }

    private fun claim(): DeathClaim {
        val claimants = listOf(
            Claimant(UUID.randomUUID(), "A", null, BigDecimal("33.33"), false),
            Claimant(UUID.randomUUID(), "B", null, BigDecimal("33.33"), false),
            Claimant(UUID.randomUUID(), "C", null, BigDecimal("33.34"), false),
        )
        return DeathClaim.notify(UUID.randomUUID(), today, "cert-1", "op-1", claimants, "k", now)
    }

    @Test
    fun `claimant shares must total exactly 100`() {
        assertThatThrownBy {
            claim().replaceClaimants(listOf(Claimant(UUID.randomUUID(), "A", null, BigDecimal("99"), false)), now)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `approval is four-eyes, needs every claimant verified, and the shares sum to the distributable value`() {
        var c = claim()
        assertThatThrownBy { c.approve("op-2", BigDecimal("1000"), BigDecimal.ZERO, { BigDecimal.ZERO }, now) }
            .hasMessageContaining("verified")
        c.claimants.forEach { c = c.recordVerification(it.id, true, "CZ6508000000192000145399", "op-1", now) }
        assertThatThrownBy { c.approve("op-1", BigDecimal("1000"), BigDecimal.ZERO, { BigDecimal.ZERO }, now) }
            .hasMessageContaining("four-eyes")
        val approved = c.approve("op-2", BigDecimal("1000.01"), BigDecimal("0.01"), {
            it.multiply(BigDecimal("0.1"))
        }, now)
        assertThat(approved.claimants.fold(BigDecimal.ZERO) { a, x -> a + x.gross!! }).isEqualByComparingTo("1000.00")
        approved.claimants.forEach { assertThat(it.net).isEqualByComparingTo(it.gross!! - it.tax!!) }
        assertThatThrownBy { approved.settle(now) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `without designations the estate inherits only when the pack says so`() {
        val rules = dps.exit!!.death
        assertThat(DeathClaim.claimantsFrom(emptyList(), rules).single().estate).isTrue()
        assertThatThrownBy { DeathClaim.claimantsFrom(emptyList(), rules.copy(estateWhenNoBeneficiary = false)) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    // ---- ADR-0334 S8: payout-destination fraud controls --------------------------------------

    private val signedIban = "CZ6508000000192000145399"
    private val otherIban = "CZ5508000000001234567899"

    private fun runningPhased(): PayoutRequest {
        val q = ExitCalculator.payoutQuote(
            dps,
            PayoutForm.PHASED_WITHDRAWAL,
            BigDecimal("12000"),
            BigDecimal("12000"),
            balance,
            12,
        )
        return PayoutRequest.quote(UUID.randomUUID(), UUID.randomUUID(), q, 7, now)
            .confirm(signedIban, "sca", "k1", today, now)
    }

    @Test
    fun `the confirmation signature covers the account - another account is another hash`() {
        val p = runningPhased()
        assertThat(p.signingHash(signedIban)).isNotEqualTo(p.signingHash(otherIban))
        assertThat(p.signingHash(signedIban)).isNotEqualTo(p.quoteHash)
        val notice = TerminationNotice.quote(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ExitCalculator.terminationQuote(dps, BigDecimal("5000"), IncentiveBalance.EMPTY, 2026),
            7,
            now,
        )
        assertThat(notice.signingHash(signedIban)).isNotEqualTo(notice.signingHash(otherIban))
    }

    @Test
    fun `a held account change applies only after notification, after the hold, to later installments`() {
        val p = runningPhased()
        val changeDay = LocalDate.parse("2026-11-28")
        val held = p.changePayoutAccount(otherIban, "sca-2", changeDay, now)
        val from = held.pendingPayoutIbanFrom!!
        assertThat(from).describedAs("the hold is the aggregate's, not the caller's").isEqualTo(changeDay.plusDays(3))
        assertThat(held.payoutIban).describedAs("the signed account is never overwritten").isEqualTo(signedIban)
        // Bypass 1: not yet notified — the new account never applies, even long after the hold.
        assertThat(held.accountFor(from.plusMonths(2), from.plusMonths(2))).isEqualTo(signedIban)
        val notified = held.markAccountChangeNotified(now)
        // Bypass 2: an installment due after the hold but PAID before it ends (early/swept) keeps the signed account.
        assertThat(notified.accountFor(from.plusDays(1), changeDay)).isEqualTo(signedIban)
        // Bypass 3: an overdue installment due before the hold, paid after it, keeps the signed account.
        assertThat(notified.accountFor(from.minusDays(1), from.plusDays(5))).isEqualTo(signedIban)
        assertThat(notified.accountFor(from, from)).isEqualTo(otherIban)
        // Bypass 4: a second change while the first is held.
        assertThatThrownBy { notified.changePayoutAccount(signedIban, "sca-3", changeDay.plusDays(1), now) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `confirming and changing cannot be combined - a confirmed payout cannot be confirmed onto another account`() {
        val p = runningPhased()
        assertThatThrownBy { p.confirm(otherIban, "sca-x", "k2", today, now) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `nothing unsigned or single-payment is redirected`() {
        val quoted = PayoutRequest.quote(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ExitCalculator.payoutQuote(
                dps,
                PayoutForm.PHASED_WITHDRAWAL,
                BigDecimal("12000"),
                BigDecimal("12000"),
                balance,
                12,
            ),
            7,
            now,
        )
        assertThatThrownBy { quoted.changePayoutAccount(otherIban, "s", today, now) }
            .isInstanceOf(IllegalStateException::class.java)
        val lump = PayoutRequest.quote(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ExitCalculator.payoutQuote(dps, PayoutForm.LUMP_SUM, BigDecimal("1000"), BigDecimal("1000"), balance),
            7,
            now,
        ).confirm(signedIban, "s", "k", today, now)
        assertThatThrownBy { lump.changePayoutAccount(otherIban, "s2", today, now) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
