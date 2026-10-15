// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.out

import com.openbank.pension.domain.incentive.TaxYearSummary
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The participant's annual statement for one closed year (ADR-0334, #12379). */
data class AnnualStatement(
    val contractId: UUID,
    val year: Int,
    val documentId: String,
    val sha256: String,
    val issuedAt: Instant,
)

/** Inputs of the statement: the year's movements plus the contract value at the date of issue. */
data class AnnualStatementContent(
    val summary: TaxYearSummary,
    val participantPartyId: UUID,
    val contractReference: String,
    val value: BigDecimal,
    val valueAsOf: LocalDate,
)

/** document-service: renders the annual statement; returns the stored document and its hash. */
fun interface AnnualStatementDocumentPort {
    suspend fun generate(content: AnnualStatementContent): RenderedDocument
}

/** A document as document-service stored it: its id and the SHA-256 of the stored bytes. */
data class RenderedDocument(val documentId: String, val sha256: String)

interface AnnualStatementRepository {
    suspend fun find(contractId: UUID, year: Int): AnnualStatement?

    /** Insert-once per (contract, year); returns the row that is stored afterwards (the winner). */
    suspend fun saveOnce(statement: AnnualStatement): AnnualStatement
}
