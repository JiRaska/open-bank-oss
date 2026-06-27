// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The retained period-close record (ADR-0035 §F.1). This is the ONLY thing persisted per statement
 * period — small metadata plus the legal/electronic sequence and balance anchors. **No camt/MT/PDF
 * bytes are stored**: renders are produced on demand from this record + the booked entries replayed
 * from transaction-service, and discarded. Retention (10y, ČNB) is on this reproducible record, not
 * on any rendered artefact.
 */
data class StatementPeriod(
    val id: UUID,
    val accountId: UUID,
    val pocketCurrency: String,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val legalSequenceNumber: Long,
    val electronicSequenceNumber: Long,
    val openingBalance: BigDecimal,
    val closingBalance: BigDecimal,
    val entryCount: Int,
    val closedAt: Instant,
    val status: PeriodCloseStatus = PeriodCloseStatus.CLOSED,
    val supersedesSequence: Long? = null,
)
