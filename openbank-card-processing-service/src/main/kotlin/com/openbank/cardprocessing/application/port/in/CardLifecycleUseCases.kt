// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.cardprocessing.application.port.`in`

import com.openbank.cardprocessing.domain.model.CardDisputeCase
import com.openbank.cardprocessing.domain.model.DisputeOutcome
import com.openbank.cardprocessing.domain.model.TokenOutcome
import com.openbank.cardprocessing.domain.model.TokenRegistrations
import com.openbank.libs.domain.cards.scheme.NetworkTokenStatus
import java.util.UUID

data class ProvisionTokenCommand(
    val cardId: UUID,
    /** The wallet or merchant asking, in the network's own requestor vocabulary. */
    val requestorId: String,
    val requestorLabel: String,
    val idempotencyKey: String,
)

data class ChangeTokenStatusCommand(
    val tokenReference: String,
    val status: NetworkTokenStatus,
)

/**
 * The caller for [TokenisationPort][com.openbank.libs.domain.cards.scheme.TokenisationPort].
 *
 * ADR-0283 phase 2 wired the port and its bindings; without this interface nothing invoked them,
 * which is the exact shape of the defect the whole ADR was written about — card-issuance's
 * authorisation decision had been complete and uncalled since ADR-0194.
 */
interface CardTokenUseCase {
    suspend fun provision(command: ProvisionTokenCommand): TokenOutcome

    suspend fun changeStatus(command: ChangeTokenStatusCommand): TokenOutcome

    /**
     * Tokens for a card, read from the network where it answers and from the mirror where it does
     * not. The answer always says which — see
     * [TokenReadSource][com.openbank.cardprocessing.domain.model.TokenReadSource].
     */
    suspend fun listForCard(cardId: UUID): TokenRegistrations
}

data class OpenDisputeCommand(
    val authorizationId: UUID,
    /** The scheme's own reason code, passed through untranslated. */
    val reasonCode: String,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val idempotencyKey: String,
)

data class SubmitEvidenceCommand(
    val disputeId: UUID,
    val documentReference: String,
    val note: String?,
)

interface CardDisputeUseCase {
    suspend fun open(command: OpenDisputeCommand): DisputeOutcome

    suspend fun submitEvidence(command: SubmitEvidenceCommand): DisputeOutcome

    /** Re-reads the network's status for a case and records any move. */
    suspend fun refreshStatus(disputeId: UUID): DisputeOutcome

    suspend fun findById(id: UUID): CardDisputeCase?

    suspend fun findByCard(cardId: UUID, limit: Int): List<CardDisputeCase>
}
