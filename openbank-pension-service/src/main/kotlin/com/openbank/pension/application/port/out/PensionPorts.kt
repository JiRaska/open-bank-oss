// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.out

import com.openbank.pension.domain.model.PensionContract
import java.util.UUID

class ContractNotFoundException(id: UUID) : RuntimeException("pension contract $id not found")

interface PensionContractRepository {
    /** Upsert of the whole aggregate, including any strategy elections not yet stored. */
    suspend fun save(contract: PensionContract): PensionContract

    suspend fun findById(id: UUID): PensionContract?

    suspend fun findByIdempotencyKey(participantPartyId: UUID, idempotencyKey: String): PensionContract?
}
