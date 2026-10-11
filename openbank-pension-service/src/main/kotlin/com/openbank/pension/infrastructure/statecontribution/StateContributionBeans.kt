// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.statecontribution

import com.openbank.pension.application.port.out.ContractFundingDirectory
import com.openbank.pension.application.port.out.ContractReferenceRepository
import com.openbank.pension.application.port.out.IncentiveClaimRepository
import com.openbank.pension.application.port.out.StateAgencyGateway
import com.openbank.pension.application.port.out.StateContributionReturnChannel
import com.openbank.pension.application.port.out.StateContributionReturnRepository
import com.openbank.pension.application.usecase.IncentiveService
import com.openbank.pension.application.usecase.StateContributionReturnService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/** CDI wiring for the CZ state-contribution returns (#12382); kept apart from `FundingBeans`. */
@ApplicationScoped
class StateContributionBeans {

    @Produces
    @ApplicationScoped
    @Suppress("LongParameterList")
    fun stateContributionReturnService(
        returns: StateContributionReturnRepository,
        claims: IncentiveClaimRepository,
        directory: ContractFundingDirectory,
        references: ContractReferenceRepository,
        incentives: IncentiveService,
        gateway: StateAgencyGateway,
        channel: StateContributionReturnChannel,
        clock: Clock,
    ): StateContributionReturnService =
        StateContributionReturnService(returns, claims, directory, references, incentives, gateway, channel, clock)
}
