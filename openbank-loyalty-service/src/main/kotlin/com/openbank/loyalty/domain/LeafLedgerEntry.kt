// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.domain

import java.time.Instant
import java.util.UUID

/**
 * What a ledger row records. Four kinds, and none of them is money: an [EARN] creates obligation,
 * a [BURN] discharges it against a benefit, an [EXPIRE] releases it unspent, and a [REVERSE]
 * unwinds an earn that should not have happened.
 */
enum class LeafEntryType { EARN, BURN, EXPIRE, REVERSE }

/**
 * One append-only row of the Lístek ledger (ADR-0282 D2). Rows are never updated in place except
 * for [remaining], which is the FIFO consumption pointer on an EARN lot — see [LeafLedger].
 *
 * @param remaining for an EARN row, how much of this lot is still unspent and unexpired; zero for
 *   every other type. This is the one mutable quantity in the ledger and it exists so expiry can
 *   be first-in-first-out without rewriting history: a burn reduces the oldest lots' [remaining]
 *   and writes its own BURN row, so the audit trail keeps both the original amount and what became
 *   of it.
 * @param ruleVersion [EarnCatalog.RULE_VERSION] as it stood when this entry was decided.
 * @param correlationEventId the id of the durable domain event that triggered this entry — never a
 *   freshly minted id at write time. A correlation id that points at nothing is not a correlation,
 *   it is decoration, and this repository has already paid for that class of defect once.
 */
data class LeafLedgerEntry(
    val id: UUID,
    val partyId: UUID,
    val type: LeafEntryType,
    val leaves: Leaves,
    val remaining: Leaves,
    val earnSource: LeafEarnSource? = null,
    val benefitId: String? = null,
    val ruleVersion: String,
    val correlationEventId: UUID,
    val occurredAt: Instant,
    val expiresAt: Instant? = null,
) {
    init {
        require(!leaves.isZero()) { "a ledger entry of zero leaves records nothing" }
        when (type) {
            LeafEntryType.EARN -> {
                requireNotNull(earnSource) { "an EARN entry must name its earn source" }
                requireNotNull(expiresAt) { "an EARN lot must carry its expiry (ADR-0282 D5)" }
                require(remaining <= leaves) { "an EARN lot cannot have more remaining than it was awarded" }
            }
            LeafEntryType.BURN -> {
                requireNotNull(benefitId) { "a BURN entry must name the benefit it paid for" }
                require(remaining.isZero()) { "a BURN entry holds no remaining balance" }
            }
            LeafEntryType.EXPIRE, LeafEntryType.REVERSE ->
                require(remaining.isZero()) { "a $type entry holds no remaining balance" }
        }
    }

    fun isExpiredAt(at: Instant): Boolean = expiresAt != null && !at.isBefore(expiresAt)
}
