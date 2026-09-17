// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.ScaChallengeClient
import com.openbank.delegation.application.port.out.StatutoryDecisionConflict
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.domain.model.StatutoryDecisionVerdict
import com.openbank.delegation.domain.model.StatutoryDelegationDecision
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.util.UUID

/** Each company representative signs only their own acceptance ballot; no activation happens here. */
@ApplicationScoped
class StatutoryDelegationAcceptanceDecisionService(
    private val proposals: StatutoryDelegationAcceptanceProposalService,
    private val repository: StatutoryDelegationOperationRepository,
    sca: ScaChallengeClient,
    private val clock: Clock,
) {
    private val ceremony = StatutoryDecisionCeremony(sca)

    @Inject
    constructor(
        proposals: StatutoryDelegationAcceptanceProposalService,
        repository: StatutoryDelegationOperationRepository,
        sca: ScaChallengeClient,
    ) : this(proposals, repository, sca, Clock.systemUTC())

    suspend fun approvalIntent(id: UUID, principal: UUID, actor: UUID): String =
        ceremony.approvalHash(proposals.pending(id, principal, principal, actor), actor)

    suspend fun decide(
        id: UUID,
        principal: UUID,
        actor: UUID,
        verdict: StatutoryDecisionVerdict,
        scaSessionId: UUID?,
    ): StatutoryDelegationDecision {
        val operation = proposals.pending(id, principal, principal, actor)
        require((verdict == StatutoryDecisionVerdict.APPROVE) == (scaSessionId != null)) {
            "APPROVE requires an SCA session; REJECT must not supply one"
        }
        repository.findDecision(id, actor)?.let { existing ->
            if (existing.verdict != verdict || existing.scaSessionId != scaSessionId) throw StatutoryDecisionConflict()
            return existing
        }
        if (verdict == StatutoryDecisionVerdict.APPROVE) {
            ceremony.verifyAndConsume(requireNotNull(scaSessionId), actor, operation)
        }
        return repository.recordDecision(
            StatutoryDelegationDecision(id, actor, verdict, scaSessionId, clock.instant()),
        )
    }
}
