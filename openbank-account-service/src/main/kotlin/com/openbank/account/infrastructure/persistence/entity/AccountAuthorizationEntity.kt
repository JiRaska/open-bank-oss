// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.persistence.entity

import com.openbank.account.domain.model.*
import com.openbank.libs.domain.money.Money
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "account_authorizations")
class AccountAuthorizationEntity : PanacheEntityBase {

    @Id
    lateinit var id: UUID

    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var role: AuthorizationRole

    @Column(name = "daily_limit_amount", precision = 20, scale = 6)
    var dailyLimitAmount: BigDecimal? = null

    @Column(name = "daily_limit_currency", length = 3)
    var dailyLimitCurrency: String? = null

    @Column(name = "transaction_limit_amount", precision = 20, scale = 6)
    var transactionLimitAmount: BigDecimal? = null

    @Column(name = "transaction_limit_currency", length = 3)
    var transactionLimitCurrency: String? = null

    @Column(name = "valid_from", nullable = false)
    lateinit var validFrom: LocalDate

    @Column(name = "valid_to")
    var validTo: LocalDate? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var status: AuthorizationStatus

    @Column(name = "granted_by", nullable = false)
    lateinit var grantedBy: UUID

    @Column(name = "granted_at", nullable = false)
    lateinit var grantedAt: Instant

    @Column(name = "revoked_by")
    var revokedBy: UUID? = null

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    @Column(name = "revoked_reason")
    var revokedReason: String? = null

    fun toDomain(): AccountAuthorization = AccountAuthorization(
        id = id,
        accountId = accountId,
        partyId = partyId,
        role = role,
        dailyLimit = if (dailyLimitAmount != null && dailyLimitCurrency != null) {
            Money.of(dailyLimitAmount!!, dailyLimitCurrency!!)
        } else {
            null
        },
        transactionLimit = if (transactionLimitAmount != null && transactionLimitCurrency != null) {
            Money.of(transactionLimitAmount!!, transactionLimitCurrency!!)
        } else {
            null
        },
        validFrom = validFrom,
        validTo = validTo,
        status = status,
        grantedBy = grantedBy,
        grantedAt = grantedAt,
        revokedBy = revokedBy,
        revokedAt = revokedAt,
        revokedReason = revokedReason,
    )

    companion object {
        fun fromDomain(a: AccountAuthorization): AccountAuthorizationEntity = AccountAuthorizationEntity().apply {
            id = a.id
            accountId = a.accountId
            partyId = a.partyId
            role = a.role
            dailyLimitAmount = a.dailyLimit?.amount
            dailyLimitCurrency = a.dailyLimit?.currency?.code
            transactionLimitAmount = a.transactionLimit?.amount
            transactionLimitCurrency = a.transactionLimit?.currency?.code
            validFrom = a.validFrom
            validTo = a.validTo
            status = a.status
            grantedBy = a.grantedBy
            grantedAt = a.grantedAt
            revokedBy = a.revokedBy
            revokedAt = a.revokedAt
            revokedReason = a.revokedReason
        }
    }
}
