// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import java.util.UUID

sealed interface StatutoryOperationCreateOutcome {
    val operation: StatutoryDelegationOperation

    data class Created(override val operation: StatutoryDelegationOperation) : StatutoryOperationCreateOutcome

    data class Replayed(override val operation: StatutoryDelegationOperation) : StatutoryOperationCreateOutcome
}

interface StatutoryDelegationOperationRepository {
    /** Exact replay returns the existing operation id; changed evidence under one request key fails. */
    suspend fun create(operation: StatutoryDelegationOperation): StatutoryOperationCreateOutcome

    /** Principal scope is mandatory so a guessed operation id never reveals another company. */
    suspend fun find(id: UUID, principalPartyId: UUID): StatutoryDelegationOperation?
}
