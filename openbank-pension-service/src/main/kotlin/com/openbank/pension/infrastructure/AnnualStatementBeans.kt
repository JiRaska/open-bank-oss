// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure

import com.openbank.pension.application.port.out.AnnualStatementDocumentPort
import com.openbank.pension.application.port.out.AnnualStatementRepository
import com.openbank.pension.application.port.out.ContractFundingDirectory
import com.openbank.pension.application.port.out.ContractReferenceRepository
import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.application.usecase.AnnualStatementService
import com.openbank.pension.application.usecase.IncentiveService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/** CDI wiring for the annual statement (#12379), kept apart from the shared bean files. */
@ApplicationScoped
class AnnualStatementBeans {
    @Produces
    @ApplicationScoped
    @Suppress("LongParameterList")
    fun annualStatementService(
        directory: ContractFundingDirectory,
        references: ContractReferenceRepository,
        incentives: IncentiveService,
        funds: FundAdministrationPort,
        documents: AnnualStatementDocumentPort,
        statements: AnnualStatementRepository,
        clock: Clock,
    ) = AnnualStatementService(directory, references, incentives, funds, documents, statements, clock)
}
