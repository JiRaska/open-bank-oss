// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.domain.model

import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.NetworkTokenStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The bank's record that a network token exists — a MIRROR, never the source of truth.
 *
 * ## What this row is and is not
 *
 * The token vault belongs to the network (VTS, MDES). This platform stores no token credential and
 * could not honour one; what it keeps is the fact that a token was provisioned for a card, to which
 * requestor, and what the network last said about its state. That is what an operator answering
 * "why did my watch stop paying?" needs, and what an audit trail of wallet provisioning has to be
 * able to show years later — neither of which a call to a network API at read time can provide once
 * the token is deleted and the network stops returning it.
 *
 * ## Why the mirror is never read as if it were live
 *
 * A mirror row and a live network answer are different claims, and code that cannot tell them apart
 * will present a stale ACTIVE for a token the network suspended an hour ago. Every read therefore
 * carries [TokenReadSource]; nothing in this service returns a token list without saying where it
 * came from. Same rule as [PostingOutcome][com.openbank.cardprocessing.application.port.out.PostingOutcome]:
 * a degraded answer must not share a signal with a good one.
 *
 * [last4] is the network's own display value for the token, not the card. No PAN, no token
 * credential and no cryptogram is stored here.
 */
data class CardTokenRegistration(
    val id: UUID,
    val cardId: UUID,
    val tokenReference: String,
    val requestorId: String,
    val requestorLabel: String,
    val last4: String,
    val status: NetworkTokenStatus,
    val scheme: CardScheme,
    val expiry: LocalDate?,
    val provisionedAt: Instant,
    val updatedAt: Instant,
) {
    /**
     * `DELETED` is terminal in every scheme's token lifecycle.
     *
     * Expressed on the aggregate rather than trusted to the adapter: the simulator enforces it, and
     * a future VTS or MDES adapter would too, but a rule that lives only in an adapter is a rule
     * this service cannot state about itself — and the refusal a caller sees would depend on which
     * binding happened to be wired.
     */
    val terminal: Boolean get() = status == NetworkTokenStatus.DELETED
}

/**
 * Where a token list came from.
 *
 * `NETWORK` means the scheme answered on this request. `LOCAL_MIRROR` means it did not and these
 * are the rows this service last recorded — possibly stale, never presented as current.
 */
enum class TokenReadSource { NETWORK, LOCAL_MIRROR }

/** A token list plus the provenance of the answer, and the failure detail when it is a fallback. */
data class TokenRegistrations(
    val tokens: List<CardTokenRegistration>,
    val source: TokenReadSource,
    /** Why the network could not answer, when [source] is `LOCAL_MIRROR`. Null on a live read. */
    val degradedReason: String? = null,
)

/** Why a token operation was refused. Values, not exceptions — the caller renders each differently. */
enum class TokenRefusal {
    CARD_NOT_FOUND,
    TOKEN_NOT_FOUND,
    TOKEN_TERMINAL,
    SCHEME_UNAVAILABLE,
    SCHEME_REFUSED,
}

sealed interface TokenOutcome {
    data class Provisioned(val registration: CardTokenRegistration) : TokenOutcome

    data class Changed(val registration: CardTokenRegistration) : TokenOutcome

    data class Refused(val reason: TokenRefusal, val detail: String?) : TokenOutcome
}
