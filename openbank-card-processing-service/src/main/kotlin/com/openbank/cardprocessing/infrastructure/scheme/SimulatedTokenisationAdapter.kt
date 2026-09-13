// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.scheme

import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.NetworkToken
import com.openbank.libs.domain.cards.scheme.NetworkTokenStatus
import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.domain.cards.scheme.SchemeResult
import com.openbank.libs.domain.cards.scheme.TokenRequestor
import com.openbank.libs.domain.cards.scheme.TokenisationPort
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * The in-repo binding of [TokenisationPort] (ADR-0283 phase 2, #8810).
 *
 * ## Stateful, and only in memory — which is the honest shape
 *
 * A tokenisation simulator that forgot every token would be useless: the port's contract is that
 * provisioning is followed by listing and by status changes, and a stateless stub cannot exercise
 * that sequence at all. So it keeps a map.
 *
 * The map is per-process and does not survive a restart, and that is **correct rather than a
 * limitation**: in a real deployment the network owns the token vault and this platform stores no
 * token either. A simulator that persisted would teach its callers a durability the real binding
 * does not offer, which is worse than forgetting.
 *
 * ## It never mints anything PAN-shaped
 *
 * [NetworkToken.tokenReference] is a UUID and `last4` is a fixed simulated value. Nothing here
 * derives from, resembles, or could be mistaken for a card number — the ports were designed so no
 * signature accepts a PAN, and the simulator must not smuggle one in through the back door.
 */
@ApplicationScoped
class SimulatedTokenisationAdapter(private val clock: Clock) : TokenisationPort {

    private val tokens = ConcurrentHashMap<String, MutableList<NetworkToken>>()

    override suspend fun provision(cardReference: String, requestor: TokenRequestor): SchemeResult<NetworkToken> {
        if (cardReference.isBlank()) {
            return SchemeResult.Unanswered(SchemeFailure.MALFORMED, CardScheme.SIMULATOR, "cardReference is blank")
        }
        val token = NetworkToken(
            // Ids.randomId(), not a bare UUID.randomUUID(): ADR-0106 wants the INTENT visible at
            // the call site, and this is the random half — an opaque reference nothing indexes or
            // sorts by, standing in for a handle the network would mint. `Ids.newId()` (UUIDv7) is
            // for durable, indexed keys, which a simulated token reference is not.
            tokenReference = "sim-tok-${Ids.randomId()}",
            last4 = SIMULATED_LAST4,
            status = NetworkTokenStatus.ACTIVE,
            expiry = LocalDate.now(clock).plusYears(TOKEN_VALIDITY_YEARS),
            requestorId = requestor.requestorId,
        )
        tokens.computeIfAbsent(cardReference) { mutableListOf() }.add(token)
        return SchemeResult.Answered(token, CardScheme.SIMULATOR)
    }

    /**
     * A card with no tokens answers with an EMPTY LIST, not NOT_FOUND.
     *
     * "This card has no tokens" is an answer; "we could not ask" is not. Returning a failure for
     * the empty case would make a caller treat a perfectly normal state as an error, and the branch
     * that renders "no wallets yet" would never be reached.
     */
    override suspend fun listTokens(cardReference: String): SchemeResult<List<NetworkToken>> =
        SchemeResult.Answered(tokens[cardReference].orEmpty().toList(), CardScheme.SIMULATOR)

    override suspend fun changeStatus(tokenReference: String, status: NetworkTokenStatus): SchemeResult<NetworkToken> {
        val entry = tokens.entries.firstOrNull { (_, list) -> list.any { it.tokenReference == tokenReference } }
            ?: return SchemeResult.Unanswered(
                SchemeFailure.NOT_FOUND,
                CardScheme.SIMULATOR,
                "no simulated token $tokenReference",
            )
        val list = entry.value
        val index = list.indexOfFirst { it.tokenReference == tokenReference }
        val current = list[index]
        if (current.status == NetworkTokenStatus.DELETED) {
            // DELETED is terminal in every scheme's token lifecycle: a deleted token cannot be
            // resumed, and pretending otherwise would let a caller believe it had restored a
            // wallet credential the network has already destroyed.
            return SchemeResult.Unanswered(
                SchemeFailure.MALFORMED,
                CardScheme.SIMULATOR,
                "token $tokenReference is DELETED, which is terminal",
            )
        }
        val updated = current.copy(status = status)
        list[index] = updated
        return SchemeResult.Answered(updated, CardScheme.SIMULATOR)
    }

    private companion object {
        /** Fixed and obviously simulated. Nothing here is derived from a card number. */
        const val SIMULATED_LAST4 = "0000"
        const val TOKEN_VALIDITY_YEARS = 3L
    }
}
