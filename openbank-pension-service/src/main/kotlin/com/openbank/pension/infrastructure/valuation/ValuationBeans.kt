// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.valuation

import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.application.usecase.ContractValuationService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/** CDI wiring for the participant valuation view; the use case itself stays framework-free. */
@ApplicationScoped
class ValuationBeans {

    @Produces
    @ApplicationScoped
    fun contractValuationService(contracts: PensionContractRepository, fund: FundAdministrationPort) =
        ContractValuationService(contracts, fund)
}
