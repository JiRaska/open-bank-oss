// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import com.openbank.libs.domain.money.Money
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class AuthorizationRole {
    FULL_ACCESS,
    PAYMENT_ONLY,
    READ_ONLY,
    CARD_HOLDER,
}

enum class AuthorizationStatus {
    ACTIVE,
    SUSPENDED,
    REVOKED,
    EXPIRED,
}

enum class SigningRule {
    SINGLE,
    JOINT_ALL,
    JOINT_ANY_TWO,
    OWNER_PLUS_ONE,
}

data class AccountAuthorization(
    val id: UUID = UUID.randomUUID(),
    val accountId: UUID,
    val partyId: UUID,
    val role: AuthorizationRole,
    val dailyLimit: Money?,
    val transactionLimit: Money?,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val status: AuthorizationStatus = AuthorizationStatus.ACTIVE,
    val grantedBy: UUID,
    val grantedAt: Instant,
    val revokedBy: UUID? = null,
    val revokedAt: Instant? = null,
    val revokedReason: String? = null,
) {
    fun isActiveOn(date: LocalDate): Boolean = status == AuthorizationStatus.ACTIVE &&
        !date.isBefore(validFrom) &&
        (validTo == null || !date.isAfter(validTo))

    fun revoke(by: UUID, reason: String, clock: Clock): AccountAuthorization = copy(
        status = AuthorizationStatus.REVOKED,
        revokedBy = by,
        revokedAt = Instant.now(clock),
        revokedReason = reason,
    )

    fun suspend(): AccountAuthorization = copy(status = AuthorizationStatus.SUSPENDED)
    fun reinstate(): AccountAuthorization = copy(status = AuthorizationStatus.ACTIVE)
}
