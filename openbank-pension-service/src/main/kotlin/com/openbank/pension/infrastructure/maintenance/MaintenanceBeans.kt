// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.maintenance

import com.openbank.pension.application.exit.DeathClaimRepository
import com.openbank.pension.application.exit.ScaVerificationPort
import com.openbank.pension.application.maintenance.ContractChangeStore
import com.openbank.pension.application.maintenance.ContractMaintenanceService
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * CDI wiring of the framework-free change service (#12376). SCA reuses the exit slice's
 * [ScaVerificationPort] bean, whose stub refuses every challenge outside dev/test — so a deployment
 * without sca-service fails closed here too.
 */
@ApplicationScoped
class MaintenanceBeans {

    @Produces
    @ApplicationScoped
    @Suppress("LongParameterList")
    fun contractMaintenanceService(
        contracts: PensionContractUseCase,
        store: ContractChangeStore,
        deathClaims: DeathClaimRepository,
        sca: ScaVerificationPort,
        packs: JurisdictionPackRegistry,
        clock: Clock,
    ) = ContractMaintenanceService(contracts, store, deathClaims, sca, packs, clock)
}
