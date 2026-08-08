// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneId

/** Why a reserve was refused. The three ceilings are ADR-0249 D3's `PER_TX | DAILY | MONTHLY`. */
enum class SpendRefusalReason {
    PER_TX,
    DAILY,
    MONTHLY,

    /** The grant is not ACTIVE, or `now` is outside its validity window. */
    GRANT_NOT_ACTIVE,

    /** The grant carries no money-moving capability, so there is nothing to reserve against. */
    NO_SPEND_CAPABILITY,

    /**
     * The amount is denominated in a currency the ceiling is not. A DENIAL rather than an error,
     * for the same reason [DelegationGrant.withinLimits] answers `false` instead of throwing: an
     * authorisation question answered with a crash is indistinguishable from an outage, and a
     * caller retrying gets the same crash.
     */
    CURRENCY_MISMATCH,
}

/**
 * Spend already counted against each window at the moment of the decision — the sum of every
 * RESERVED and CONFIRMED reservation on the grant whose `createdAt` falls inside the window and
 * whose currency matches. Read under the same lock as the write it informs; see
 * `SpendReservationRepository.reserve`.
 */
data class CountedSpend(val withinDay: Money, val withinMonth: Money)

sealed interface SpendDecision {
    data object Allowed : SpendDecision

    /**
     * [remaining] is what the delegate could still spend under the breached ceiling right now,
     * clamped at zero. It is the number the customer's client can honestly show; a negative
     * "remaining" (possible when a grantor lowers a ceiling below what is already reserved) would
     * render as a debt the delegate does not owe.
     */
    data class Refused(
        val reason: SpendRefusalReason,
        val ceiling: Money? = null,
        val alreadyCounted: Money? = null,
        val remaining: Money? = null,
    ) : SpendDecision
}

/**
 * The two cumulative windows, resolved to instants once per decision so that the daily and the
 * monthly answer are computed from the same clock reading.
 */
data class SpendWindow(val dayStart: OffsetDateTime, val monthStart: OffsetDateTime)

object SpendWindows {
    /**
     * TIMEZONE DECISION (ADR-0249 D3). Both windows are calendar windows in **Europe/Prague**, the
     * bank's home jurisdiction, and NOT in UTC.
     *
     * A customer who caps a delegate at "5 000 Kč per day" means their own calendar day. In UTC the
     * counter would roll over at 01:00 or 02:00 local, so a delegate could spend a full day's
     * ceiling at midnight and another one an hour later — twice the cap, inside what the customer
     * would call one day. The monthly window uses the same zone for the same reason and so that a
     * day can never straddle two months differently from how the month sees it.
     *
     * It is deliberately a fixed zone rather than the caller's or the server's: the ceiling belongs
     * to the grant, and a grant whose window moved with whoever happened to call would be
     * unauditable. If this service is ever operated for another jurisdiction, this constant — and
     * a migration of the counters — is the one place that changes.
     */
    val ZONE: ZoneId = ZoneId.of("Europe/Prague")

    fun windowAt(now: OffsetDateTime): SpendWindow {
        val local = now.atZoneSameInstant(ZONE)
        val dayStart = local.toLocalDate().atStartOfDay(ZONE)
        val monthStart = local.toLocalDate().withDayOfMonth(1).atStartOfDay(ZONE)
        return SpendWindow(dayStart = dayStart.toOffsetDateTime(), monthStart = monthStart.toOffsetDateTime())
    }
}

/**
 * ADR-0249 D3 — the arithmetic that makes `dailyLimit` and `monthlyLimit` mean something.
 *
 * Pure and framework-free on purpose: this is the only place the three ceilings are compared, and
 * every caller (the reserve path, its tests) exercises the same function. The per-transaction check
 * is delegated to [DelegationGrant.withinLimits] rather than re-implemented, so the two can never
 * disagree.
 */
object SpendCeilings {

    fun evaluate(grant: DelegationGrant, amount: Money, counted: CountedSpend, now: OffsetDateTime): SpendDecision {
        if (!grant.isActiveOn(now)) {
            return SpendDecision.Refused(SpendRefusalReason.GRANT_NOT_ACTIVE)
        }
        if (grant.capabilities.none { it in DelegationGrant.EXECUTION_CAPABILITIES }) {
            return SpendDecision.Refused(SpendRefusalReason.NO_SPEND_CAPABILITY)
        }
        return perTransaction(grant, amount)
            ?: cumulative(SpendRefusalReason.DAILY, grant.dailyLimit, counted.withinDay, amount)
            ?: cumulative(SpendRefusalReason.MONTHLY, grant.monthlyLimit, counted.withinMonth, amount)
            ?: SpendDecision.Allowed
    }

    private fun perTransaction(grant: DelegationGrant, amount: Money): SpendDecision.Refused? {
        val ceiling = grant.perTransactionLimit ?: return null
        if (ceiling.currency != amount.currency) {
            return SpendDecision.Refused(SpendRefusalReason.CURRENCY_MISMATCH, ceiling = ceiling)
        }
        if (grant.withinLimits(amount)) return null
        return SpendDecision.Refused(
            reason = SpendRefusalReason.PER_TX,
            ceiling = ceiling,
            alreadyCounted = null,
            remaining = ceiling,
        )
    }

    /**
     * A ceiling is breached when what is already counted PLUS this amount exceeds it. Equality
     * passes: spending exactly the cap is spending within the cap.
     */
    private fun cumulative(
        reason: SpendRefusalReason,
        ceiling: Money?,
        counted: Money,
        amount: Money,
    ): SpendDecision.Refused? {
        if (ceiling == null) return null
        if (ceiling.currency != amount.currency) {
            return SpendDecision.Refused(SpendRefusalReason.CURRENCY_MISMATCH, ceiling = ceiling)
        }
        val projected = counted.amount.add(amount.amount)
        if (projected.compareTo(ceiling.amount) <= 0) return null
        return SpendDecision.Refused(
            reason = reason,
            ceiling = ceiling,
            alreadyCounted = counted,
            remaining = headroom(ceiling, counted),
        )
    }

    /**
     * Clamped at zero, and clamped to a zero that is still scaled to the currency's minor unit —
     * a bare [BigDecimal.ZERO] would hand the client "0 CZK" where every other amount on the same
     * response reads "0.00 CZK".
     */
    private fun headroom(ceiling: Money, counted: Money): Money {
        val left = ceiling.amount.subtract(counted.amount)
        val clamped = if (left.signum() < 0) BigDecimal.ZERO else left
        return Money(clamped.setScale(ceiling.currency.defaultFractionDigits), ceiling.currency)
    }
}
