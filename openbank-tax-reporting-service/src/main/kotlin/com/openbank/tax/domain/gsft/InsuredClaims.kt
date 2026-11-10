// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.gsft

import com.openbank.tax.domain.model.requireValid
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/*
 * Insured claims per depositor for the deposit-insurance payout (issue #15356; obligation
 * rows `gsft-depositor-data` and `gsft-test-delivery` of the regulatory obligations register).
 *
 * WHY THIS IS A PURE FUNCTION OF ONE DAY
 *   The bank must hand GSFT the depositor data within 3 working days of the relevant day
 *   (rozhodný den, zákon č. 21/1992 Sb. § 41d odst. 4). Nothing can be built after the event in
 *   that window, so the calculation must be runnable at any moment over a snapshot "as of day D"
 *   — and identical in the yearly TEST delivery and the real one (§ 41n). This file is that
 *   calculation and nothing else: no I/O, no clock, no file format. The delivery format (vyhláška
 *   č. 71/2011 Sb. ve znění 431/2016 Sb.) is a separate renderer over [InsuredClaimsAssembly].
 *
 * WHAT IT DECIDES
 *   - A depositor's eligible balance is principal plus accrued interest over every account they
 *     hold, with a joint account split by declared shares, or equally when none are declared.
 *   - Foreign-currency balances convert to CZK at the ČNB rate of the relevant day. A missing
 *     rate FAILS the assembly: skipping the account would understate a claim silently.
 *   - Compensation is capped at the CZK equivalent of EUR 100 000 on that day.
 *   - An excluded depositor (§ 41b — the reason is recorded, never just a boolean) and every
 *     ADR-0252 synthetic depositor or account is dropped and COUNTED, so the exclusion is
 *     evidenced in the delivery rather than assumed. A synthetic customer reaching this list
 *     would be a fictitious person entitled to compensation.
 *
 * TEMPORARILY HIGH BALANCES
 *   The increased compensation of § 41e odst. 3 / § 41ea is claim-based and computed separately
 *   ([TemporarilyHighBalances]); it is evidenced on the assembly and never folded into
 *   [InsuredClaim.compensationCzk].
 */

/** Statutory cover per depositor, in EUR. */
val COVER_LIMIT_EUR: BigDecimal = BigDecimal("100000")

private const val CZK = "CZK"
private const val EUR = "EUR"
private const val MONEY_SCALE = 2
private const val SHARE_SCALE = 10

/** Why a depositor is outside the scheme. Categories of § 41b; the decision is party-service's. */
enum class ExclusionReason {
    CREDIT_INSTITUTION,
    FINANCIAL_INSTITUTION,
    INSURANCE_UNDERTAKING,
    INVESTMENT_OR_PENSION_FUND,
    PUBLIC_AUTHORITY,
    OTHER_STATUTORY,
}

/** [naturalPerson] gates the increased cover of § 41e odst. 3; unknown is treated as false. */
data class Depositor(
    val partyId: UUID,
    val exclusion: ExclusionReason?,
    val synthetic: Boolean,
    val naturalPerson: Boolean = false,
)

/** One holder's share of an account. [share] is a fraction in (0, 1]; null means "split equally". */
data class Holding(val partyId: UUID, val share: BigDecimal?)

data class DepositAccount(
    val accountId: UUID,
    val iban: String,
    val currency: String,
    val principal: BigDecimal,
    val accruedInterest: BigDecimal,
    val holders: List<Holding>,
    val synthetic: Boolean,
    /**
     * An account in the special regime (účet ve speciálním režimu, § 41f): the owner holds it for
     * beneficiaries (podílníci) listed in DII. Decree annex 1.2.2, DIC.PAYMENT: the cover limit
     * does not apply to the owner of such an account.
     */
    val specialRegime: Boolean = false,
) {
    init {
        requireValid(holders.isNotEmpty()) { "account $accountId has no holder" }
        requireValid(
            holders.map {
                it.partyId
            }.toSet().size == holders.size,
        ) { "account $accountId lists a holder twice" }
        val declared = holders.mapNotNull { it.share }
        requireValid(declared.isEmpty() || declared.size == holders.size) {
            "account $accountId declares shares for some holders but not all"
        }
        requireValid(declared.all { it > BigDecimal.ZERO && it <= BigDecimal.ONE }) {
            "account $accountId has a share outside (0, 1]"
        }
        requireValid(
            declared.isEmpty() || declared.fold(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal.ONE) == 0,
        ) {
            "account $accountId shares do not sum to 1"
        }
    }

    fun shareOf(partyId: UUID): BigDecimal {
        val holding = holders.first { it.partyId == partyId }
        return holding.share ?: BigDecimal.ONE.divide(BigDecimal(holders.size), SHARE_SCALE, RoundingMode.HALF_EVEN)
    }

    /** principal + accrued interest, rounded to hundredths. */
    fun total(): BigDecimal = principal.add(accruedInterest).setScale(MONEY_SCALE, RoundingMode.HALF_UP)

    /** The account total in CZK, rounded to haléře BEFORE any split. */
    fun totalCzk(rates: FxRates): BigDecimal =
        rates.toCzk(principal.add(accruedInterest), currency).setScale(MONEY_SCALE, RoundingMode.HALF_UP)

    /**
     * Splits [amount] (already at hundredths) between the holders in minor units with the
     * largest-remainder method, so the parts sum EXACTLY to [amount]. Ties go to holder order.
     */
    fun split(amount: BigDecimal): Map<UUID, BigDecimal> {
        val minor = amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY).unscaledValue()
        val equal = holders.first().share == null
        val weights = holders.map { if (equal) BigDecimal.ONE else it.share!! }
        val weightSum = weights.fold(BigDecimal.ZERO, BigDecimal::add)
        val exact = weights.map { BigDecimal(minor).multiply(it) }
        val floors = exact.map { it.divideToIntegralValue(weightSum).toBigIntegerExact() }
        val remainders = exact.mapIndexed { i, e -> e.subtract(BigDecimal(floors[i]).multiply(weightSum)) }
        var leftover = minor.subtract(floors.fold(BigInteger.ZERO, BigInteger::add)).toInt()
        val parts = floors.toMutableList()
        val sign = if (leftover < 0) BigInteger.ONE.negate() else BigInteger.ONE
        val order = holders.indices.sortedWith(compareByDescending<Int> { remainders[it].abs() }.thenBy { it })
        for (i in order) {
            if (leftover == 0) break
            parts[i] = parts[i].add(sign)
            leftover -= sign.toInt()
        }
        return holders.indices.associate { holders[it].partyId to BigDecimal(parts[it], MONEY_SCALE) }
    }
}

/** CZK per one unit of currency, ČNB fixing of the relevant day. */
data class FxRates(val day: LocalDate, val czkPerUnit: Map<String, BigDecimal>) {
    fun toCzk(amount: BigDecimal, currency: String): BigDecimal {
        if (currency == CZK) return amount
        val rate = czkPerUnit[currency]
        requireValid(rate != null && rate > BigDecimal.ZERO) { "no ČNB rate for $currency on $day" }
        return amount.multiply(rate)
    }
}

data class InsuredClaim(
    val partyId: UUID,
    val eligibleBalanceCzk: BigDecimal,
    val compensationCzk: BigDecimal,
    val accountIds: List<UUID>,
    /** Increased compensation for temporarily high balances (§ 41e odst. 3); not rendered. */
    val increasedCompensationCzk: BigDecimal = BigDecimal.ZERO,
)

data class InsuredClaimsAssembly(
    val relevantDay: LocalDate,
    val coverLimitCzk: BigDecimal,
    val claims: List<InsuredClaim>,
    val droppedSyntheticDepositors: Int,
    val droppedSyntheticAccounts: Int,
    val droppedOverdrawnAccounts: Int,
    val excludedDepositors: Map<ExclusionReason, Int>,
    /** Claims whose eligible balance exceeds the cover limit and are paid at the limit. */
    val cappedClaims: Int = 0,
    /** Claims carrying increased compensation for a temporarily high balance (§ 41e odst. 3). */
    val temporarilyHighClaims: Int = 0,
    /** Sum of that increased compensation, CZK; claim-based, not in the delivered files. */
    val temporarilyHighCompensationCzk: BigDecimal = BigDecimal.ZERO,
    /** Qualifying credits refused (no application, not a natural person, outside the window, ...). */
    val refusedQualifyingCredits: Int = 0,
)

object InsuredClaims {

    fun assemble(
        relevantDay: LocalDate,
        depositors: Collection<Depositor>,
        accounts: Collection<DepositAccount>,
        rates: FxRates,
        qualifyingCredits: Collection<QualifyingCredit> = emptyList(),
    ): InsuredClaimsAssembly {
        requireValid(rates.day == relevantDay) { "FX rates are for ${rates.day}, the relevant day is $relevantDay" }
        val byId = depositors.associateBy { it.partyId }
        requireValid(byId.size == depositors.size) { "a depositor is listed twice" }
        requireValid(accounts.map { it.accountId }.toSet().size == accounts.size) { "an account is listed twice" }
        accounts.flatMap { a -> a.holders.map { it.partyId } }.forEach { holder ->
            requireValid(holder in byId) { "account holder $holder is not among the depositors" }
        }

        val coverLimitCzk = rates.toCzk(COVER_LIMIT_EUR, EUR).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        // An overdrawn account holds no deposit: it is not reported (the format carries no sign)
        // and does not net against the depositor's other balances.
        val overdrawn = accounts.filter { !it.synthetic && it.principal.add(it.accruedInterest).signum() < 0 }
        val liveAccounts = accounts.filterNot { it.synthetic || it in overdrawn }
        val eligible = depositors.filter { !it.synthetic && it.exclusion == null }

        var refused = 0
        val claims = eligible.mapNotNull { depositor ->
            val held = liveAccounts.filter { a -> a.holders.any { it.partyId == depositor.partyId } }
            if (held.isEmpty()) return@mapNotNull null
            // Each account converts to CZK and rounds FIRST, then splits in haléře, so the shares
            // reported in DIL sum exactly to the DIA total and DIC reconciles with both.
            fun shareSum(of: List<DepositAccount>) = of.fold(BigDecimal.ZERO.setScale(MONEY_SCALE)) { sum, a ->
                sum.add(a.split(a.totalCzk(rates)).getValue(depositor.partyId))
            }
            val (special, ordinary) = held.partition { it.specialRegime }
            val balance = shareSum(held)
            // Annex 1.2.2 DIC.PAYMENT: the limit does not apply to the owner of a special-regime
            // account, so those balances are added uncapped on top of the capped ordinary claim.
            val compensation = shareSum(ordinary).max(BigDecimal.ZERO).min(coverLimitCzk).add(shareSum(special))
            InsuredClaim(depositor.partyId, balance, compensation, held.map { it.accountId }.sorted())
        }.sortedBy { it.partyId }.let { base ->
            if (qualifyingCredits.isEmpty()) return@let base
            val extra = TemporarilyHighBalances.compute(
                relevantDay,
                qualifyingCredits,
                base,
                byId,
                accounts.associateBy { it.accountId },
                rates,
                coverLimitCzk,
            )
            refused = extra.refusedCredits
            base.map { c -> extra.byParty[c.partyId]?.let { c.copy(increasedCompensationCzk = it) } ?: c }
        }

        return InsuredClaimsAssembly(
            relevantDay = relevantDay,
            coverLimitCzk = coverLimitCzk,
            claims = claims,
            cappedClaims = claims.count { it.compensationCzk < it.eligibleBalanceCzk },
            temporarilyHighClaims = claims.count { it.increasedCompensationCzk.signum() > 0 },
            temporarilyHighCompensationCzk = claims.fold(BigDecimal.ZERO) { s, c -> s.add(c.increasedCompensationCzk) },
            refusedQualifyingCredits = refused,
            droppedSyntheticDepositors = depositors.count { it.synthetic },
            droppedSyntheticAccounts = accounts.count { it.synthetic },
            droppedOverdrawnAccounts = overdrawn.size,
            excludedDepositors = depositors.filter { !it.synthetic }.mapNotNull { it.exclusion }
                .groupingBy { it }.eachCount(),
        )
    }
}
