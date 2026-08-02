// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.event

import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.libs.domain.event.DomainEvent
import java.time.Instant
import java.util.UUID

data class AccountCreatedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val accountNumber: String,
    val accountType: AccountType,
    val partyId: UUID,
    val productId: UUID,
    val currency: String,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Account"
    override val eventType = "AccountCreated"
}

data class AccountStatusChangedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val previousStatus: AccountStatus,
    val newStatus: AccountStatus,
    val reason: String?,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Account"
    override val eventType = "AccountStatusChanged"
}

data class AccountClosedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val reason: String?,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Account"
    override val eventType = "AccountClosed"
}

/**
 * The executable half of the propose-only flow (ADR-0232 D8 / AC8): the owner
 * approved a delegate's withdrawal proposal with their own SCA. The payments path
 * consumes this as the instruction to actually move the money — the approval and
 * the instruction share one outbox transaction with the proposal's status flip.
 */
data class SavingsWithdrawalApproved(
    override val aggregateId: UUID,
    val accountId: UUID,
    val delegatePartyId: UUID,
    val amountMinor: Long,
    val currency: String,
    val approvalId: String,
    val scaSessionId: UUID,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Account"
    override val eventType = "SavingsWithdrawalApproved"
    override val version = 1L
}
