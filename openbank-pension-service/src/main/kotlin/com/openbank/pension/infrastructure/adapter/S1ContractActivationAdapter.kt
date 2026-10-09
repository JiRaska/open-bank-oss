// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.adapter

import com.openbank.pension.application.port.out.ContractActivationPort
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.model.ContractStatus
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.LocalDate

/**
 * Default [ContractActivationPort]: S1's own `PENDING_ACTIVATION -> ACTIVE` transition, with the
 * value date of the first money as start date. When S2's onboarding activation port lands, this is
 * replaced by it so the onboarding application completes in the same step (follow-up, #12350).
 */
@ApplicationScoped
class S1ContractActivationAdapter(private val contracts: PensionContractRepository, private val clock: Clock) :
    ContractActivationPort {

    override suspend fun activateOnFirstContribution(contractId: java.util.UUID, startDate: LocalDate): Boolean {
        val contract = contracts.findById(contractId) ?: return false
        if (contract.status != ContractStatus.PENDING_ACTIVATION) return false
        contracts.save(contract.activate(startDate, clock.instant()))
        return true
    }
}
