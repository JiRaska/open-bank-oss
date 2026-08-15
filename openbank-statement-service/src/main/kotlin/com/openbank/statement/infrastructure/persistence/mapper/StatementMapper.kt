// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.persistence.mapper

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.statement.domain.model.StatementPeriod
import com.openbank.statement.domain.model.StatementSnapshot
import com.openbank.statement.infrastructure.persistence.entity.StatementPeriodEntity
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class StatementMapper {

    /**
     * Private, explicitly-configured mapper for the `model_snapshot` column (#3986) — NOT the
     * CDI `ObjectMapper`. The snapshot is a stored legal record read back years later, so its
     * encoding must not move when someone tunes the application-wide REST mapper. Dates are ISO
     * strings (never epoch numbers) and `BigDecimal` keeps its scale, so `100.00` round-trips as
     * `100.00` and not `100.0` — a re-render prints the amount the statement was issued with.
     */
    private val json: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

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
        modelSnapshot = period.snapshot?.let { json.writeValueAsString(it) }
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
        snapshot = e.modelSnapshot?.let { json.readValue<StatementSnapshot>(it) },
    )
}
