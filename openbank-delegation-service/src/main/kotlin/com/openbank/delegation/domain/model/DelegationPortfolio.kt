// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.domain.model

import com.openbank.libs.domain.identifiers.Ids
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A named, business-owned scope of accounts (ADR-0232 D5/D8).
 *
 * This is deliberately not an authorization grant and cannot itself release a payment. The
 * customer edge resolves an active statutory/internal mandate before forwarding the business
 * principal, and the application verifies every account against its authoritative owner before
 * persisting this aggregate. Payment co-signing is added only with an enforcing money path.
 */
data class DelegationPortfolio(
    val id: UUID = Ids.newId(),
    val ownerPartyId: UUID,
    val name: String,
    val accountIds: Set<UUID>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    init {
        require(name.isNotBlank()) { "portfolio name is required" }
        require(name.length <= NAME_MAX_LENGTH) { "portfolio name is too long" }
        require(accountIds.isNotEmpty()) { "portfolio must contain at least one account" }
        require(accountIds.size <= MAX_ACCOUNTS) { "portfolio contains too many accounts" }
    }

    companion object {
        const val NAME_MAX_LENGTH = 100
        const val MAX_ACCOUNTS = 500
    }
}
