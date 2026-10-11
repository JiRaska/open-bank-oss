// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.`in`

import com.openbank.pension.application.port.out.ContractNotFoundException
import com.openbank.pension.domain.model.PensionContract

/**
 * The ONE ownership rule for pension contracts. A customer caller sees only contracts where it is
 * the participant; anything else is reported as NOT FOUND — the same answer as an unknown id — so
 * ids cannot be enumerated. A staff caller sees every contract (and is limited to reads elsewhere).
 */
object ContractVisibility {
    fun requireVisible(caller: Caller, contract: PensionContract): PensionContract {
        if (caller.customerPartyId != null && contract.participantPartyId != caller.customerPartyId) {
            throw ContractNotFoundException(contract.id)
        }
        return contract
    }
}
