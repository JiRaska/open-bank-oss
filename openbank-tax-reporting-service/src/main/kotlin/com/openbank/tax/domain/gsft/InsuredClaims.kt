// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.gsft

import com.openbank.tax.domain.model.requireValid
import java.math.BigDecimal
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
 * WHAT IT DOES NOT DECIDE (yet)
 *   Temporarily high balances (a separate, higher-cover category) need the triggering events and
 *   are not modelled here; such balances are covered only up to the standard limit until they are.
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

data class Depositor(val partyId: UUID, val exclusion: ExclusionReason?, val synthetic: Boolean)

/** One holder's share of an account. [share] is a fraction in (0, 1]; null means "split equally". */
data class Holding(val partyId: UUID, val share: BigDecimal?)

data class DepositAccount(
    val accountId: UUID,
    val currency: String,
    val principal: BigDecimal,
    val accruedInterest: BigDecimal,
    val holders: List<Holding>,
    val synthetic: Boolean,
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
)

data class InsuredClaimsAssembly(
    val relevantDay: LocalDate,
    val coverLimitCzk: BigDecimal,
    val claims: List<InsuredClaim>,
    val droppedSyntheticDepositors: Int,
    val droppedSyntheticAccounts: Int,
    val excludedDepositors: Map<ExclusionReason, Int>,
)

object InsuredClaims {

    fun assemble(
        relevantDay: LocalDate,
        depositors: Collection<Depositor>,
        accounts: Collection<DepositAccount>,
        rates: FxRates,
    ): InsuredClaimsAssembly {
        requireValid(rates.day == relevantDay) { "FX rates are for ${rates.day}, the relevant day is $relevantDay" }
        val byId = depositors.associateBy { it.partyId }
        requireValid(byId.size == depositors.size) { "a depositor is listed twice" }
        accounts.flatMap { a -> a.holders.map { it.partyId } }.forEach { holder ->
            requireValid(holder in byId) { "account holder $holder is not among the depositors" }
        }

        val coverLimitCzk = rates.toCzk(COVER_LIMIT_EUR, EUR).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        val liveAccounts = accounts.filterNot { it.synthetic }
        val eligible = depositors.filter { !it.synthetic && it.exclusion == null }

        val claims = eligible.mapNotNull { depositor ->
            val held = liveAccounts.filter { a -> a.holders.any { it.partyId == depositor.partyId } }
            if (held.isEmpty()) return@mapNotNull null
            val balance = held.fold(BigDecimal.ZERO) { sum, a ->
                val total = a.principal.add(a.accruedInterest)
                sum.add(rates.toCzk(total, a.currency).multiply(a.shareOf(depositor.partyId)))
            }.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
            val compensation = balance.max(BigDecimal.ZERO).min(coverLimitCzk)
            InsuredClaim(depositor.partyId, balance, compensation, held.map { it.accountId }.sorted())
        }.sortedBy { it.partyId }

        return InsuredClaimsAssembly(
            relevantDay = relevantDay,
            coverLimitCzk = coverLimitCzk,
            claims = claims,
            droppedSyntheticDepositors = depositors.count { it.synthetic },
            droppedSyntheticAccounts = accounts.count { it.synthetic },
            excludedDepositors = depositors.filter { !it.synthetic }.mapNotNull { it.exclusion }
                .groupingBy { it }.eachCount(),
        )
    }
}
