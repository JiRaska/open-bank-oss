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
import java.time.LocalDate
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
    /** Optional savings goal (ADR-0153) — all three null when no goal is set. */
    val goalName: String? = null,
    val goalTargetMinorUnits: Long? = null,
    val goalTargetDate: LocalDate? = null,
    /** Customer-chosen display label. Null means "use the account-type default name". */
    val nickname: String? = null,
)

data class AddPocketRequest(val currencyCode: String)

/** PUT /{accountId}/goal body (ADR-0153). */
data class SavingsGoalRequest(val name: String, val targetMinorUnits: Long, val targetDate: LocalDate? = null)

/** PATCH /{accountId}/nickname body. Null/blank clears it. */
data class RenameAccountRequest(val nickname: String?)

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
