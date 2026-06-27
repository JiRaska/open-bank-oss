// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.persistence.mapper

import com.openbank.statement.domain.model.StatementPeriod
import com.openbank.statement.infrastructure.persistence.entity.StatementPeriodEntity
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class StatementMapper {

    fun toEntity(period: StatementPeriod): StatementPeriodEntity = StatementPeriodEntity().apply {
        id = period.id
        accountId = period.accountId
        pocketCurrency = period.pocketCurrency
        periodFrom = period.periodFrom
        periodTo = period.periodTo
        legalSequenceNumber = period.legalSequenceNumber
        electronicSequenceNumber = period.electronicSequenceNumber
        openingBalance = period.openingBalance
        closingBalance = period.closingBalance
        entryCount = period.entryCount
        status = period.status
        supersedesSequence = period.supersedesSequence
        closedAt = period.closedAt
    }

    fun toDomain(e: StatementPeriodEntity): StatementPeriod = StatementPeriod(
        id = e.id,
        accountId = e.accountId,
        pocketCurrency = e.pocketCurrency,
        periodFrom = e.periodFrom,
        periodTo = e.periodTo,
        legalSequenceNumber = e.legalSequenceNumber,
        electronicSequenceNumber = e.electronicSequenceNumber,
        openingBalance = e.openingBalance,
        closingBalance = e.closingBalance,
        entryCount = e.entryCount,
        closedAt = e.closedAt,
        status = e.status,
        supersedesSequence = e.supersedesSequence,
    )
}
