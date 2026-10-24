// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import com.openbank.swift.domain.model.SwiftMessage
import java.math.BigDecimal
import java.util.UUID

/**
 * One settlement posting against a SWIFT message; production persists no such record (the
 * settlement is a transaction-service booking keyed by the message, ADR-0108), so the scenario
 * records the money-side view here — the invariant-side correspondence between a message's
 * terminal status and the journal that did or did not move its funds.
 */
data class SwiftSettlementPosting(
    val messageId: UUID,
    val journalId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val reversed: Boolean = false,
)

/**
 * Money-path SWIFT adoption: the invariant-side book for
 * [com.openbank.simulation.scenario.SwiftMessageScenario], mirroring SddCollectionBook's role.
 * Holds the REAL `SwiftMessage` aggregates the scenario drives through the real `validate()`
 * admission, plus the per-message settlement posting the money-side invariants assert over
 * (exactly one live journal per completed message, none for a rejected one, reversed for a
 * conflicted one).
 */
class SwiftMessageBook {

    val messages = mutableListOf<SwiftMessage>()
    private val postings = mutableMapOf<UUID, SwiftSettlementPosting>()

    fun recordMessage(message: SwiftMessage) {
        messages.add(message)
    }

    fun replaceMessage(updated: SwiftMessage) {
        val index = messages.indexOfFirst { it.id == updated.id }
        messages[index] = updated
    }

    fun recordPosting(posting: SwiftSettlementPosting) {
        postings[posting.messageId] = posting
    }

    fun replacePosting(updated: SwiftSettlementPosting) {
        postings[updated.messageId] = updated
    }

    fun postingOf(messageId: UUID): SwiftSettlementPosting? = postings[messageId]
}
