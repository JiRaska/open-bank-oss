// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.application.port.out

import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.loyalty.domain.BenefitGrant
import com.openbank.loyalty.domain.LeafEarnSource
import com.openbank.loyalty.domain.LeafLedger
import com.openbank.loyalty.domain.LeafLedgerEntry
import com.openbank.loyalty.domain.Leaves
import java.time.Instant
import java.util.UUID

/**
 * The Lístek ledger's write and query surface. Every write here also writes the outbox row in the
 * SAME transaction (ADR-0050) — that atomicity is the adapter's job and is proven by
 * `LoyaltyLedgerOutboxIT` against a real database, never by a mocked repository, which cannot
 * observe a transaction at all.
 */
interface LeafLedgerRepository {
    /** Every entry for a party, any type, any state — the input to [LeafLedger]'s pure rules. */
    suspend fun entriesFor(partyId: UUID): List<LeafLedgerEntry>

    /**
     * Idempotency guard, keyed on the achievement — (party, earn source, correlation event) — so a
     * redelivered domain event cannot award twice. `openbank-engagement-service` learned the
     * narrower form of this the hard way: keying on a freshly generated id awards on every retry,
     * because each HTTP POST creates a new triggering row.
     */
    suspend fun findEarn(partyId: UUID, source: LeafEarnSource, correlationEventId: UUID): LeafLedgerEntry?

    /** Leaves this party has EARNed within the calendar year containing [at] — the cap's input. */
    suspend fun earnedInYearOf(partyId: UUID, at: Instant): Leaves

    /** Appends an EARN lot and its outbox row atomically. */
    suspend fun appendEarn(entry: LeafLedgerEntry)

    /**
     * Appends the BURN row, debits the named lots' `remaining`, and writes the grant plus the
     * outbox row — all in one transaction. Splitting these would let a party's balance fall for a
     * grant that was never recorded, or a grant exist that nothing paid for.
     */
    suspend fun appendBurnAndGrant(entry: LeafLedgerEntry, debits: List<LeafLedger.LotDebit>, grant: BenefitGrant)

    /** Appends EXPIRE rows and zeroes the named lots, atomically with their outbox rows. */
    suspend fun appendExpiries(entries: List<LeafLedgerEntry>, lotIds: List<UUID>)

    /** Parties holding at least one lot that has expired unspent as of [at] — the sweep's input. */
    suspend fun partiesWithExpirableLots(at: Instant, limit: Int): List<UUID>

    /** Outstanding obligation across every party — the daily provisioning summary's numerator. */
    suspend fun outstandingLeaves(at: Instant): Long
}

/** Grants are read back by idempotency key so a retry resolves to the grant it already made. */
interface BenefitGrantRepository {
    suspend fun findByIdempotencyKey(partyId: UUID, key: String): BenefitGrant?
}

/** Marker interface so the dispatcher binds this service's outbox, not another's. */
interface LoyaltyOutboxRepository : OutboxRepository

/**
 * Counters the loyalty programme is judged by. [earnCapped] exists as its own counter for the
 * same reason `EarnOutcome.Capped` is its own type: "the programme is refusing to award" is a
 * state you must be able to alert on, and it is invisible if it shares a metric with success.
 */
interface LoyaltyMetricsPort {
    fun earnAwarded(sourceId: String, leaves: Int)
    fun earnCapped(sourceId: String, requested: Int)
    fun earnReplayed(sourceId: String)
    fun benefitGranted(benefitId: String, price: Int)
    fun benefitRefused(benefitId: String, reason: String)
    fun leavesExpired(count: Int)
    fun outstandingObligation(leaves: Long)
}
