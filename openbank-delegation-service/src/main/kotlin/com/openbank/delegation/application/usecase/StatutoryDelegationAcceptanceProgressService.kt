// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.domain.model.StatutoryOperationState
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** Display state uses the exact same office-aware quorum evaluator as issuance. */
@ApplicationScoped
class StatutoryDelegationAcceptanceProgressService(
    private val proposals: StatutoryDelegationAcceptanceProposalService,
    private val repository: StatutoryDelegationOperationRepository,
) {
    suspend fun get(id: UUID, company: UUID, actor: UUID): StatutorySigningProgress {
        val (operation, rule) = proposals.current(id, company, company, actor)
        if (operation.state != StatutoryOperationState.PENDING) throw StatutoryProposalStale(id)
        return StatutorySigningProgress.evaluate(id, rule, actor, repository.decisions(id))
    }
}
