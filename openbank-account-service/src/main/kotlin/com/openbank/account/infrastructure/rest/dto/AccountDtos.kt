// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.account.domain.model.PocketStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class OpenAccountRequest(
    val partyId: UUID,
    val productId: UUID,
    val accountType: AccountType,
    val currencyCode: String,
    /** Legal name of the party — required for sanctions screening (ADR-0032 §C). */
    val legalName: String,
)

data class CloseAccountRequest(val reason: String?)

data class FreezeAccountRequest(val reason: String)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AccountResponse(
    val id: UUID,
    val accountNumber: String,
    val accountType: AccountType,
    val partyId: UUID,
    val productId: UUID,
    val currencyCode: String,
    val status: AccountStatus,
    val openedAt: Instant,
    val closedAt: Instant?,
)

data class AddPocketRequest(val currencyCode: String)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PocketResponse(
    val id: UUID,
    val accountId: UUID,
    val currencyCode: String,
    val isPrimary: Boolean,
    val status: PocketStatus,
    val openedAt: Instant,
    val closedAt: Instant?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PocketResolutionResponse(
    val outcome: String,
    val pocketCurrency: String? = null,
    val convertFrom: String? = null,
    val reason: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AccountBalanceResponse(
    val accountId: UUID,
    val availableBalance: BigDecimal,
    val currentBalance: BigDecimal,
    val reservedBalance: BigDecimal,
    val pendingBalance: BigDecimal,
    val currencyCode: String,
    val lastUpdatedAt: Instant,
)
