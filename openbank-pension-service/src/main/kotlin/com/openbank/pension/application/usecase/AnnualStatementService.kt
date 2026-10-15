// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.pension.application.port.out.AnnualStatement
import com.openbank.pension.application.port.out.AnnualStatementContent
import com.openbank.pension.application.port.out.AnnualStatementDocumentPort
import com.openbank.pension.application.port.out.AnnualStatementRepository
import com.openbank.pension.application.port.out.ContractFundingDirectory
import com.openbank.pension.application.port.out.ContractNotFoundException
import com.openbank.pension.application.port.out.ContractReferenceRepository
import com.openbank.pension.application.port.out.FundAdministrationPort
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The participant's annual statement (ADR-0334, #12379): the closed year's movements — own,
 * employer and state contributions, transfers in — and the contract value on the day of issue,
 * rendered by document-service.
 *
 * Issued ONCE per (contract, year): a second call returns the stored statement and renders
 * nothing, so an operator retry or a re-run batch cannot put two different statements for one year
 * in front of the participant. The value is a valuation at the date of issue (forward-priced units
 * have no year-end NAV in this service) and the statement says so through `valueAsOf`.
 */
class AnnualStatementService(
    private val directory: ContractFundingDirectory,
    private val references: ContractReferenceRepository,
    private val incentives: IncentiveService,
    private val funds: FundAdministrationPort,
    private val documents: AnnualStatementDocumentPort,
    private val statements: AnnualStatementRepository,
    private val clock: Clock,
) {
    suspend fun find(contractId: UUID, year: Int): AnnualStatement? = statements.find(contractId, year)

    suspend fun issue(contractId: UUID, year: Int): AnnualStatement {
        statements.find(contractId, year)?.let { return it }
        val contract = directory.find(contractId) ?: throw ContractNotFoundException(contractId)
        require(year < LocalDate.now(clock).year) { "year $year has not closed yet" }
        val summary = incentives.taxSummary(contractId, year)
        val valuation = funds.valuation(contractId, contract.currency)
        val rendered = documents.generate(
            AnnualStatementContent(
                summary = summary,
                participantPartyId = contract.participantPartyId,
                contractReference = references.referenceFor(contractId),
                value = valuation.amount,
                valueAsOf = valuation.asOf,
            ),
        )
        return statements.saveOnce(
            AnnualStatement(contractId, year, rendered.documentId, rendered.sha256, clock.instant()),
        )
    }
}
