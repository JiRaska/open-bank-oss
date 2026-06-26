// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.account.infrastructure.rest.dto

import com.openbank.account.domain.model.*
import com.openbank.libs.domain.money.Money
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class GrantAuthorizationRequest(
    val partyId: UUID,
    val role: AuthorizationRole,
    val dailyLimit: MoneyDto?,
    val transactionLimit: MoneyDto?,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val grantedBy: UUID,
)

data class RevokeAuthorizationRequest(val revokedBy: UUID, val reason: String)

data class MoneyDto(val amount: java.math.BigDecimal, val currency: String) {
    fun toDomain() = com.openbank.libs.domain.money.Money.of(amount, currency)
}

data class AuthorizationResponse(
    val id: UUID,
    val accountId: UUID,
    val partyId: UUID,
    val role: AuthorizationRole,
    val dailyLimit: MoneyDto?,
    val transactionLimit: MoneyDto?,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val status: AuthorizationStatus,
    val grantedBy: UUID,
    val grantedAt: Instant,
) {
    companion object {
        fun from(a: AccountAuthorization) = AuthorizationResponse(
            id = a.id,
            accountId = a.accountId,
            partyId = a.partyId,
            role = a.role,
            dailyLimit = a.dailyLimit?.let { MoneyDto(it.amount, it.currency.code) },
            transactionLimit = a.transactionLimit?.let { MoneyDto(it.amount, it.currency.code) },
            validFrom = a.validFrom,
            validTo = a.validTo,
            status = a.status,
            grantedBy = a.grantedBy,
            grantedAt = a.grantedAt,
        )
    }
}
