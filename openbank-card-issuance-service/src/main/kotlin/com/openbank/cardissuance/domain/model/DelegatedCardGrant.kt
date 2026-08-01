// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.domain.model

import java.time.OffsetDateTime
import java.util.UUID

/**
 * card-issuance's local projection row of a CARD-scoped delegation-service grant
 * (ADR-0232 D3). Capabilities stay STRINGS for the same reason as in account-service:
 * the delegation vocabulary grows in delegation-service and an unknown value must
 * never poison the consumer — it is stored and simply never matches a guard question.
 */
data class DelegatedCardGrant(
    val id: UUID,
    val cardId: UUID,
    val granteePartyId: UUID,
    val capabilities: Set<String>,
    val validFrom: OffsetDateTime,
    val validTo: OffsetDateTime? = null,
    val active: Boolean = true,
) {
    fun isActiveOn(now: OffsetDateTime): Boolean = active &&
        !now.isBefore(validFrom) &&
        (validTo == null || now.isBefore(validTo))

    fun satisfies(intent: CardDelegationIntent): Boolean = when (intent) {
        CardDelegationIntent.VIEW -> CAP_CARD_VIEW in capabilities
        CardDelegationIntent.MANAGE_LIMITS -> CAP_CARD_MANAGE_LIMITS in capabilities
    }

    companion object {
        const val CAP_CARD_VIEW = "CARD_VIEW"
        const val CAP_CARD_MANAGE_LIMITS = "CARD_MANAGE_LIMITS"
    }
}

enum class CardDelegationIntent {
    VIEW,
    MANAGE_LIMITS,
}
