// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.event

import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import com.openbank.libs.domain.event.DomainEvent
import java.util.UUID

data class AccountCreatedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val accountNumber: String,
    val accountType: AccountType,
    val partyId: UUID,
    val productId: UUID,
    val currency: String,
) : DomainEvent() {
    override val aggregateType = "Account"
    override val eventType = "AccountCreated"
}

data class AccountStatusChangedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val previousStatus: AccountStatus,
    val newStatus: AccountStatus,
    val reason: String?,
) : DomainEvent() {
    override val aggregateType = "Account"
    override val eventType = "AccountStatusChanged"
}

data class AccountClosedEvent(override val aggregateId: UUID, override val version: Long, val reason: String?) :
    DomainEvent() {
    override val aggregateType = "Account"
    override val eventType = "AccountClosed"
}
