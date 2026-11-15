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
 * Temporarily high balances — "zvýšená náhrada" (issue #15356). Transposes Directive 2014/49/EU
 * art. 6(2) in zákon č. 21/1992 Sb. (consolidated text in force from 11 Jan 2026):
 *
 *   § 41e odst. 3   a NATURAL PERSON who documents the conditions of § 41ea and applies under
 *                   § 41eb odst. 1 receives increased compensation for the documented part of the
 *                   claim, up to the amount by which the claim exceeds the § 41e odst. 2 limit,
 *                   at most the equivalent of EUR 100 000, converted at the ČNB rate of the
 *                   relevant day.
 *   § 41ea odst. 1  the relevant day must fall within 3 MONTHS of crediting — the moment the
 *                   amount first came to the depositor's disposal — of a payment that is one of
 *                   the events a) to j) ([QualifyingEvent]), each with its prescribed evidence.
 *   § 41ea odst. 2–3 the period does not run while the depositor may not dispose of the amount
 *                   (incapacity, notarial or attorney escrow).
 *   § 41eb odst. 1  the depositor applies to GSFT, on its form, within 2 months of the relevant day;
 *                   GSFT decides within 4 months (odst. 2, 4); the bank provides cooperation on
 *                   GSFT's request (odst. 3).
 *   § 41e odst. 6   no increased compensation for § 41f deposits, except notarial, attorney or
 *                   real-estate-agent escrow.
 *
 * So the increased amount is CLAIM-BASED: the bank's 3-day delivery (§ 41d odst. 4) carries the
 * standard compensation only, and vyhláška č. 71/2011 Sb. has no field for it in DIC or DIA. This
 * file computes the figure the bank can evidence when GSFT asks; it never changes
 * [InsuredClaim.compensationCzk] nor anything rendered. Inclusion is explicit per credit:
 * [QualifyingCredit.appliedOn] must lie within the § 41eb odst. 1 deadline, and anything not provably eligible is refused
 * and counted, never paid.
 */

/** Increased-cover ceiling per natural person, in EUR (§ 41e odst. 3). */
val INCREASED_COVER_LIMIT_EUR: BigDecimal = BigDecimal("100000")

/** Statutory window between crediting and the relevant day (§ 41ea odst. 1). */
const val QUALIFYING_WINDOW_MONTHS: Long = 3

/** Deadline for the depositor's application to GSFT after the relevant day (§ 41eb odst. 1). */
const val APPLICATION_DEADLINE_MONTHS: Long = 2

private const val EUR_CODE = "EUR"
private const val SCALE = 2

/** The payment categories of § 41ea odst. 1 písm. a) to j). */
enum class QualifyingEvent {
    /** a) sale of residential real estate, evidenced by the cadastral filing made before the day. */
    RESIDENTIAL_PROPERTY_SALE,

    /** b) settlement of community property on divorce, by final judgment. */
    DIVORCE_PROPERTY_SETTLEMENT,

    /** c) insurance benefit for injury, illness, disability or death. */
    PERSONAL_INSURANCE_BENEFIT,

    /** d) inheritance settlement, by final court decision. */
    INHERITANCE,

    /** e) one-off settlement from state-supported pension insurance or supplementary pension saving. */
    PENSION_LUMP_SUM,

    /** f) severance on termination of employment or service. */
    SEVERANCE,

    /** g) insurance benefit for harm caused by a criminal offence. */
    CRIME_INSURANCE_BENEFIT,

    /** h) damages for harm caused by a criminal offence, or surrender of unjust enrichment from one. */
    CRIME_DAMAGES,

    /** i) compensation for a decision on custody, sentence or protective measure. */
    WRONGFUL_DETENTION_COMPENSATION,

    /** j) monetary assistance to a crime victim. */
    CRIME_VICTIM_ASSISTANCE,
}

/**
 * One credit the depositor claims as a temporarily high balance. [creditedOn] is the day the
 * amount FIRST came to the depositor's disposal (§ 41ea odst. 1, already shifted by odst. 2–3 when
 * disposal was suspended — the caller must establish that day). [amount] is the WHOLE credit to the
 * account, in the account currency; a joint account's credit is apportioned by holder share.
 * [appliedOn] is the day the depositor applied to GSFT (§ 41eb odst. 1); null means no application.
 */
data class QualifyingCredit(
    val partyId: UUID,
    val accountId: UUID,
    val amount: BigDecimal,
    val creditedOn: LocalDate,
    val event: QualifyingEvent,
    val evidenceReference: String,
    val appliedOn: LocalDate?,
) {
    init {
        requireValid(amount > BigDecimal.ZERO) { "qualifying credit on $accountId must be positive" }
        requireValid(evidenceReference.isNotBlank()) { "qualifying credit on $accountId has no evidence reference" }
    }

    /** Identity of the underlying payment, independent of which holder files it. */
    internal val paymentKey: List<Any> get() = listOf(
        accountId,
        creditedOn,
        amount.stripTrailingZeros(),
        evidenceReference,
    )

    /** True when the application was made no later than 2 months after [relevantDay]. */
    fun appliedInTime(relevantDay: LocalDate): Boolean =
        appliedOn != null && !appliedOn.isAfter(relevantDay.plusMonths(APPLICATION_DEADLINE_MONTHS))

    /** True when [relevantDay] falls inside the 3-month window opened by the credit. */
    fun inWindow(relevantDay: LocalDate): Boolean =
        !relevantDay.isBefore(creditedOn) && !relevantDay.isAfter(creditedOn.plusMonths(QUALIFYING_WINDOW_MONTHS))
}

internal data class IncreasedCover(val byParty: Map<UUID, BigDecimal>, val refusedCredits: Int)

internal object TemporarilyHighBalances {

    /**
     * Per eligible party, the increased compensation of § 41e odst. 3. Each admissible credit is
     * apportioned to the filing holder by account share (the [DepositAccount.split] of the credit
     * in CZK); per (party, account) the credited part is bounded by that holder's share of the
     * account's balance on the relevant day — money credited and since spent is not attributable.
     * The party total is then bounded by (balance − standard limit) and the EUR 100 000
     * equivalent. A credit is refused (counted, never paid, never thrown) when the depositor did
     * not apply in time, is not a natural person or is outside the claims, the account is not a
     * live account of that depositor, the relevant day is outside the window, the same payment is
     * filed twice by the same holder, or nothing of it remains attributable.
     */
    fun compute(
        relevantDay: LocalDate,
        credits: Collection<QualifyingCredit>,
        claims: List<InsuredClaim>,
        depositors: Map<UUID, Depositor>,
        accounts: Map<UUID, DepositAccount>,
        rates: FxRates,
        coverLimitCzk: BigDecimal,
    ): IncreasedCover {
        val claimById = claims.associateBy { it.partyId }
        val admissible = credits.filter { c ->
            c.appliedInTime(relevantDay) &&
                depositors[c.partyId]?.naturalPerson == true &&
                claimById[c.partyId]?.accountIds?.contains(c.accountId) == true &&
                c.inWindow(relevantDay)
        }.distinctBy { it.paymentKey + it.partyId }
        val attributed = admissible.groupBy { it.partyId to it.accountId }.mapValues { (key, own) ->
            val account = accounts.getValue(key.second)
            val credited = own.fold(BigDecimal.ZERO.setScale(SCALE)) { sum, c ->
                val czk = rates.toCzk(c.amount, account.currency).setScale(SCALE, RoundingMode.HALF_UP)
                sum.add(account.split(czk).getValue(key.first))
            }
            credited.min(account.split(account.totalCzk(rates)).getValue(key.first).max(BigDecimal.ZERO))
        }
        val paid = attributed.filterValues { it.signum() > 0 }
        val ceiling = rates.toCzk(INCREASED_COVER_LIMIT_EUR, EUR_CODE).setScale(SCALE, RoundingMode.HALF_UP)
        val byParty = paid.entries.groupBy({ it.key.first }, { it.value }).mapValues { (party, parts) ->
            val excess = claimById.getValue(party).eligibleBalanceCzk.subtract(coverLimitCzk).max(BigDecimal.ZERO)
            parts.fold(BigDecimal.ZERO, BigDecimal::add).min(excess).min(ceiling)
        }.filterValues { it.signum() > 0 }
        val admitted = admissible.count { (it.partyId to it.accountId) in paid && it.partyId in byParty }
        return IncreasedCover(byParty, credits.size - admitted)
    }
}
