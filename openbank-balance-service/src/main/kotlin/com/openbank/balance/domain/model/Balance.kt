// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.domain.model

import com.openbank.libs.domain.event.EventActor
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class Balance(
    val id: UUID,
    val accountId: UUID,
    val currency: String,
    val bookedAmount: BigDecimal,
    val availableAmount: BigDecimal,
    val reservedAmount: BigDecimal,
    val pendingAmount: BigDecimal,
    val updatedAt: OffsetDateTime,
    val version: Long,
    // Arranged (povolený) overdraft limit per ČNB/AnaCredit. The balance may be drawn down to
    // -arrangedOverdraftLimit; beyond that is an unarranged (nepovolený) overdraft and is rejected.
    val arrangedOverdraftLimit: BigDecimal = BigDecimal.ZERO,
    // ADR-0178 Phase 2 (#1745). The not-yet-effective CREDIT tail: Σ of projected booked deltas for
    // this (account, currency) that are strictly positive and value-dated after the current
    // accounting day. Derived per read from the dated projection audit (`ledger_projection_event`),
    // never materialized — see [effectiveAvailable] for why that matters. ZERO whenever it has not
    // been hydrated, which keeps every existing construction site (and the point-in-time rewind in
    // BalanceService, which already excludes the tail by rewinding) behaving exactly as before.
    val notYetEffectiveCredit: BigDecimal = BigDecimal.ZERO,
) {
    init {
        require(arrangedOverdraftLimit.signum() >= 0) {
            "arrangedOverdraftLimit must be non-negative: $arrangedOverdraftLimit"
        }
        // Credits only, by construction of the query that fills it. A negative value here would mean
        // future-dated DEBITS were netted in, which would add spendable money back (see below).
        require(notYetEffectiveCredit.signum() >= 0) {
            "notYetEffectiveCredit must be non-negative: $notYetEffectiveCredit"
        }
    }

    // Lowest value bookedAmount/availableAmount may reach: the negated arranged overdraft limit.
    private fun overdraftFloor(): BigDecimal = arrangedOverdraftLimit.negate()

    fun available(): BigDecimal = availableAmount
    fun booked(): BigDecimal = bookedAmount
    fun reserved(): BigDecimal = reservedAmount

    /**
     * Booked on the ledger's value-date basis: the receipt-dated running total less the credits that
     * are posted but not yet effective. This is the figure that ties to the ledger deposit-control
     * as of today (`entry_date <= :asOf`), by construction rather than by after-the-fact
     * reconciliation (ADR-0178, #1745).
     */
    fun effectiveBooked(): BigDecimal = bookedAmount - notYetEffectiveCredit

    /**
     * **The spendable figure.** `availableAmount` less the not-yet-effective credit tail.
     *
     * ## Why only the credit tail is removed, and the debit tail deliberately is not
     *
     * A pure value-date restatement would subtract the *net* future tail, which for a future-dated
     * DEBIT (Σ delta < 0) means adding money back — handing the customer spendable funds that are
     * already committed to an outbound payment leaving on its booking date. That is the unsafe
     * direction, and it is a risk decision, not a bookkeeping one. So the tail is filtered to
     * strictly positive deltas: a not-yet-effective credit stops being spendable, while a
     * not-yet-effective debit stays deducted exactly as it is today. Both candidate product
     * semantics for #1745 — "visible but unspendable" and "hidden until value date" — agree that a
     * future-dated credit must not be spendable, so this half needs no product decision; what is
     * still open is only whether [bookedAmount] should *display* it.
     *
     * ## Why derived and not materialized
     *
     * The tail is recomputed per read from the dated projection audit, so it becomes correct the
     * moment the accounting day passes the value date with nothing having to run. The daily roll
     * (`ValueDateRollScheduler`) therefore only *announces* maturity to downstream consumers; a
     * missed roll delays a notification and can never leave a balance wrong. A materialized
     * `effective_booked` column would invert that: a missed roll would be a wrong money figure.
     */
    fun effectiveAvailable(): BigDecimal = availableAmount - notYetEffectiveCredit

    /**
     * The spendable figure, ON THE WIRE (#1745).
     *
     * A property rather than a second function, because `BalanceResource` serialises this object
     * directly and Jackson does not see `fun effectiveAvailable()` — a Kotlin function compiles to
     * `effectiveAvailable()`, not `getEffectiveAvailable()`, and a `val`'s getter does carry that
     * name. Declared without a Jackson annotation on purpose: this is the domain layer and it must
     * stay framework-free (ADR-0002) — the enforced domain-purity gate rejects the import, which
     * is how I found out. Without this the payload carried
     * `availableAmount` and a `notYetEffectiveCredit` field no consumer read, so every caller
     * outside balance-service kept spending the pre-fix number while the invariant looked fixed:
     * `openbank-account-service`'s `BalanceServiceClient.toView()` maps `available = availableAmount`
     * and drops the tail entirely.
     *
     * `availableAmount` deliberately keeps its meaning. Consumers that want "what may be spent now"
     * read this; anything reconciling against the raw projection still has the unmodified figure.
     */
    val effectiveAvailableAmount: BigDecimal
        get() = effectiveAvailable()

    /** Drawn overdraft (credit exposure for AnaCredit): how far booked is below zero, else zero. */
    fun overdraftUsed(): BigDecimal = bookedAmount.negate().max(BigDecimal.ZERO)

    fun isOverdrawn(): Boolean = bookedAmount.signum() < 0

    // Guards on effectiveAvailable(), NOT availableAmount: the cover decision is the one place the
    // not-yet-effective credit must not be spendable (#1745). With no future-dated credit the two
    // are equal and this is the previous behaviour exactly.
    fun withReservation(amount: BigDecimal): Balance {
        val spendable = effectiveAvailable()
        require(spendable - amount >= overdraftFloor()) {
            "Insufficient funds: available=$spendable (of which $notYetEffectiveCredit not yet " +
                "effective), overdraftLimit=$arrangedOverdraftLimit, requested=$amount"
        }
        return copy(
            availableAmount = availableAmount - amount,
            reservedAmount = reservedAmount + amount,
            version = version + 1,
        )
    }

    fun releaseReservation(amount: BigDecimal): Balance {
        val release = amount.min(reservedAmount)
        return copy(
            availableAmount = availableAmount + release,
            reservedAmount = reservedAmount - release,
            version = version + 1,
        )
    }

    fun applyDebit(amount: BigDecimal): Balance {
        require(bookedAmount - amount >= overdraftFloor()) {
            "Overdraft limit exceeded: booked=$bookedAmount, overdraftLimit=$arrangedOverdraftLimit, requested=$amount"
        }
        return copy(
            bookedAmount = bookedAmount - amount,
            availableAmount = availableAmount - amount,
            version = version + 1,
        )
    }

    fun applyCredit(amount: BigDecimal): Balance = copy(
        bookedAmount = bookedAmount + amount,
        availableAmount = availableAmount + amount,
        version = version + 1,
    )

    // ADR-0039 Phase D: apply a signed booked delta projected from a ledger AccountBookedChanged
    // event (+ on a credit, − on a debit). Unlike applyDebit there is NO overdraft guard: this is
    // not a new spend decision but the read-model catching up to a fact the ledger already posted —
    // the cover decision was enforced earlier by the hold, and a posted accounting movement cannot
    // be refused by its projection. Moves availableAmount in lock-step so the projected available
    // tracks booked; reservations/holds are layered on top by the saga, not here.
    fun applyBookedDelta(delta: BigDecimal): Balance = copy(
        bookedAmount = bookedAmount + delta,
        availableAmount = availableAmount + delta,
        version = version + 1,
    )
}

data class BalanceHold(
    val id: UUID,
    val accountId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val reason: String,
    val referenceId: String,
    val expiresAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
    val releasedAt: OffsetDateTime?,
)

/**
 * The three origins this service publishes from (#3994). See [BalanceEvent.actorId] for why all
 * three are `SYSTEM` and none is a person.
 *
 * Named constants rather than string literals at five call sites: the audit trail groups on this
 * value, so a typo at one site would silently create a fourth origin that looks real.
 */
object BalanceEventActors {
    private const val SERVICE = "balance-service"

    /** An inbound command on the balance API — hold, release, credit, debit. */
    val API: String = EventActor.system(SERVICE, "balance-api")

    /** The read model catching up to a posting the ledger already made (ADR-0039 Phase D). */
    val LEDGER_PROJECTION: String = EventActor.system(SERVICE, "ledger-projection")

    /** The daily value-date roll scheduler (ADR-0178, #1745). */
    val VALUE_DATE_ROLL: String = EventActor.system(SERVICE, "value-date-roll")
}

enum class BalanceEventType {
    BALANCE_UPDATED,
    HOLD_PLACED,
    HOLD_RELEASED,
    HOLD_EXPIRED,
}

data class BalanceEvent(
    val eventId: UUID,
    val eventType: BalanceEventType,
    val accountId: UUID,
    val currency: String,
    val amount: BigDecimal?,
    val bookedAmount: BigDecimal,
    val availableAmount: BigDecimal,
    val reservedAmount: BigDecimal,
    val occurredAt: OffsetDateTime,
    /**
     * Who originated this balance movement (#3994).
     *
     * **Always a `SYSTEM` id here, and that is the finding, not a shortcut.** Every one of this
     * service's five publish sites is reached either by a machine-to-machine call from the payment
     * orchestration (`placeHold`/`releaseHold`/`credit`/`debit`) or by this service's own
     * projection and scheduler — none of them has, or could have, a human identity to record. The
     * commands (`PlaceHoldCommand`, `CreditAccountCommand`, …) carry no principal, and the REST
     * layer above them is service-to-service. So the honest answer is that no person did this, and
     * `EventActor.system` says exactly that instead of leaving a NULL that reads identically to a
     * lost human identity — 542 of the 1341 unattributed audit rows are these three event types.
     *
     * The mechanism segment is what makes the value worth storing: `balance-api` (an inbound
     * command), `ledger-projection` (catching up to a posting the ledger already made) and
     * `value-date-roll` (the daily scheduler) are three genuinely different origins that were all
     * one NULL before.
     *
     * **This is a serialised data class, not a hand-built map** — the wire keys are these Kotlin
     * property names, so the outbox payload's `ObjectMapper.writeValueAsString` (the same call the
     * pre-#8510 direct emitter made) emits `actorId` and `actorType` with no literal appearing
     * anywhere in this module. A grep for `"actorId"` over
     * balance-service therefore found nothing before this change and finds nothing after it, which
     * is the exact blind spot that hides half of this fleet's event fields.
     *
     * Nullable with a null default only so the many test constructions of this class keep
     * compiling; every production site sets it.
     */
    val actorId: String? = null,
    val actorType: String? = null,
    /**
     * Producing service, read by `AuditConsumer.resolveSourceService` as the strongest
     * (EVENT-sourced) attribution (#3994/#5256). This class already carries `eventType` via
     * [BalanceEventType], and those values (`BALANCE_UPDATED`, `HOLD_PLACED`, …) are not touched
     * here — nothing outside balance-service reads them by name (verified fleet-wide), so there
     * is no load-bearing-rename risk. `sourceService` has no such consumer today, so it is safe to
     * add net-new. Value matches the fleet's audit convention: the module directory without the
     * `openbank-` prefix, the same spelling `TopicAttribution` already maps
     * `openbank.balance.events` to.
     *
     * **This is a serialised data class, not a hand-built map** — the wire key is this Kotlin
     * property name, same as `actorId`/`actorType` above.
     */
    val sourceService: String = "balance-service",
)
