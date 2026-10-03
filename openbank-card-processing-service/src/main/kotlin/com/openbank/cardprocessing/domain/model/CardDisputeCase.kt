// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.domain.model

import com.openbank.libs.domain.cards.scheme.CardScheme
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A chargeback case, as this bank tracks it against a cleared card authorisation.
 *
 * ## Two vocabularies, deliberately kept apart
 *
 * [status] is the BANK's lifecycle and [schemeStatus] is the network's own string, carried verbatim
 * and never translated. The scheme's status vocabulary differs per network and changes with their
 * release cycles; folding it into ours would make the two disagree exactly where a chargeback
 * deadline is computed, which is the one place a disagreement costs money. The same rule is stated
 * on [SchemeDispute][com.openbank.libs.domain.cards.scheme.SchemeDispute] for [reasonCode].
 *
 * ## Why there is no local case without a network case
 *
 * [networkCaseId] is non-null and assigned by the network. A case recorded here that the network
 * never opened would carry a [respondByDate] nobody is counting down, and would read as an active
 * dispute on every screen while the representment window silently expired. So opening fails closed:
 * if the scheme cannot be reached, no row is written and the caller is told why.
 */
data class CardDisputeCase(
    val id: UUID,
    val authorizationId: UUID,
    val cardId: UUID,
    val networkCaseId: String,
    val reasonCode: String,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val status: DisputeStatus,
    val scheme: CardScheme,
    /** The network's own status string, verbatim. Never parsed, never mapped to [status]. */
    val schemeStatus: String,
    val respondByDate: LocalDate?,
    val evidenceReference: String?,
    val openedAt: Instant,
    val updatedAt: Instant,
) {
    /** A case in a terminal state accepts no evidence and no further transition. */
    val terminal: Boolean get() = status in TERMINAL_STATUSES

    private companion object {
        val TERMINAL_STATUSES = setOf(DisputeStatus.WON, DisputeStatus.LOST, DisputeStatus.WITHDRAWN)
    }
}

/**
 * The bank-side lifecycle.
 *
 * `EVIDENCE_SUBMITTED` is a state and not a flag on `OPEN`: the representment deadline stops
 * mattering once evidence is in, and an operator queue that cannot separate the two is a queue that
 * keeps showing work already done.
 */
enum class DisputeStatus { OPEN, EVIDENCE_SUBMITTED, WON, LOST, WITHDRAWN }

/** Why a dispute operation was refused. Each renders differently to an operator; none is an error. */
enum class DisputeRefusal {
    AUTHORIZATION_NOT_FOUND,

    /**
     * The authorisation carries no acquirer reference, so the network cannot be told WHICH
     * transaction is disputed. Its own value because the operator's next step differs: this one is
     * a data problem to chase with the acquirer, not a scheme outage to retry.
     */
    NO_NETWORK_REFERENCE,
    NOTHING_CLEARED,
    AMOUNT_EXCEEDS_CLEARED,
    ALREADY_DISPUTED,
    CASE_NOT_FOUND,
    CASE_TERMINAL,
    SCHEME_UNAVAILABLE,
    SCHEME_REFUSED,
}

sealed interface DisputeOutcome {
    data class Accepted(val case: CardDisputeCase) : DisputeOutcome

    data class Refused(val reason: DisputeRefusal, val detail: String?) : DisputeOutcome
}
