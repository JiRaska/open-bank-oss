// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.ScaChallengeClient
import com.openbank.delegation.application.port.out.ScaChallengeSnapshot
import com.openbank.delegation.application.port.out.StatutoryDecisionConflict
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.domain.model.StatutoryDecisionVerdict
import com.openbank.delegation.domain.model.StatutoryDelegationDecision
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationState
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

class StatutoryDecisionUnavailable : RuntimeException("statutory SCA decision cannot be verified now")

/** Records signer evidence only. No grant, quorum execution or event is produced here. */
@ApplicationScoped
class StatutoryDelegationDecisionService(
    private val proposals: StatutoryDelegationProposalService,
    private val repository: StatutoryDelegationOperationRepository,
    private val sca: ScaChallengeClient,
    private val clock: Clock,
) {
    @Inject
    constructor(
        proposals: StatutoryDelegationProposalService,
        repository: StatutoryDelegationOperationRepository,
        sca: ScaChallengeClient,
    ) : this(proposals, repository, sca, Clock.systemUTC())

    suspend fun approvalIntent(id: UUID, principal: UUID, actor: UUID): String {
        val operation = pending(id, principal, actor)
        return approvalHash(operation, actor)
    }

    suspend fun decide(
        id: UUID,
        principal: UUID,
        actor: UUID,
        verdict: StatutoryDecisionVerdict,
        scaSessionId: UUID?,
    ): StatutoryDelegationDecision {
        val operation = pending(id, principal, actor)
        require((verdict == StatutoryDecisionVerdict.APPROVE) == (scaSessionId != null)) {
            "APPROVE requires an SCA session; REJECT must not supply one"
        }
        repository.findDecision(id, actor)?.let { existing ->
            if (existing.verdict != verdict || existing.scaSessionId != scaSessionId) throw StatutoryDecisionConflict()
            return existing
        }
        if (verdict == StatutoryDecisionVerdict.APPROVE) {
            verifyAndConsume(requireNotNull(scaSessionId), actor, operation)
        }
        return repository.recordDecision(
            StatutoryDelegationDecision(id, actor, verdict, scaSessionId, clock.instant()),
        )
    }

    private suspend fun pending(id: UUID, principal: UUID, actor: UUID): StatutoryDelegationOperation {
        val operation = proposals.get(id, principal, principal, actor)
        if (operation.state != StatutoryOperationState.PENDING) throw StatutoryProposalStale(id)
        return operation
    }

    private suspend fun verifyAndConsume(sessionId: UUID, actor: UUID, operation: StatutoryDelegationOperation) {
        val hash = approvalHash(operation, actor)
        val before = read(sessionId)
        requireMatching(before, sessionId, actor, operation.id, hash)
        if (before.consumedAt != null) return
        val consumed = try {
            sca.consumeStatutoryApproval(sessionId, actor, operation.id, hash)
        } catch (_: Exception) {
            // A timeout may mean the remote compare-and-consume committed. Reconcile exact evidence.
            read(sessionId)
        }
        requireMatching(consumed, sessionId, actor, operation.id, hash)
        if (consumed.consumedAt == null) throw StatutoryDecisionUnavailable()
    }

    private suspend fun read(sessionId: UUID): ScaChallengeSnapshot = try {
        sca.getChallenge(sessionId)
    } catch (_: Exception) {
        throw StatutoryDecisionUnavailable()
    }

    private fun requireMatching(
        challenge: ScaChallengeSnapshot,
        sessionId: UUID,
        actor: UUID,
        operationId: UUID,
        hash: String,
    ) {
        val signerAndPurposeMatch = challenge.id == sessionId &&
            challenge.partyId == actor &&
            challenge.purpose == SCA_PURPOSE
        val bindingAndStatusMatch = challenge.status == "COMPLETED" &&
            challenge.operationId == operationId.toString() &&
            challenge.operationHash == hash
        if (!signerAndPurposeMatch || !bindingAndStatusMatch) {
            throw StatutoryDecisionConflict()
        }
    }

    private fun approvalHash(operation: StatutoryDelegationOperation, actor: UUID): String {
        val exactIntent = listOf(
            "openbank:statutory-delegation-approval:v1",
            operation.id,
            operation.principalPartyId,
            operation.requestHash,
            operation.ruleHash,
            actor,
            StatutoryDecisionVerdict.APPROVE.name,
        ).joinToString("\n")
        return MessageDigest.getInstance("SHA-256")
            .digest(exactIntent.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SCA_PURPOSE = "DELEGATION_STATUTORY_APPROVAL"
    }
}
