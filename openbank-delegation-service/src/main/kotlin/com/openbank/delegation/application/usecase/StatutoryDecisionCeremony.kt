// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.ScaChallengeClient
import com.openbank.delegation.application.port.out.ScaChallengeSnapshot
import com.openbank.delegation.application.port.out.StatutoryDecisionConflict
import com.openbank.delegation.domain.model.StatutoryDecisionVerdict
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryOperationKind
import java.security.MessageDigest
import java.util.UUID

/** Purpose- and domain-separated device proof shared by ISSUE and ACCEPT ballot services. */
internal class StatutoryDecisionCeremony(private val sca: ScaChallengeClient) {
    fun approvalHash(operation: StatutoryDelegationOperation, actor: UUID): String {
        val domain = when (operation.operationKind) {
            StatutoryOperationKind.ISSUE -> "openbank:statutory-delegation-approval:v1"
            StatutoryOperationKind.ACCEPT -> "openbank:statutory-delegation-acceptance:v1"
        }
        val exactIntent = listOf(
            domain,
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

    suspend fun verifyAndConsume(sessionId: UUID, actor: UUID, operation: StatutoryDelegationOperation) {
        val hash = approvalHash(operation, actor)
        val before = read(sessionId)
        requireMatching(before, sessionId, actor, operation, hash)
        if (before.consumedAt != null) return
        val consumed = try {
            sca.consumeStatutoryApproval(sessionId, actor, operation.id, hash)
        } catch (_: Exception) {
            // A timeout may mean the remote compare-and-consume committed. Reconcile exact evidence.
            read(sessionId)
        }
        requireMatching(consumed, sessionId, actor, operation, hash)
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
        operation: StatutoryDelegationOperation,
        hash: String,
    ) {
        val expectedPurpose = when (operation.operationKind) {
            StatutoryOperationKind.ISSUE -> "DELEGATION_STATUTORY_APPROVAL"
            StatutoryOperationKind.ACCEPT -> "DELEGATION_STATUTORY_ACCEPTANCE"
        }
        val signerAndPurposeMatch = challenge.id == sessionId &&
            challenge.partyId == actor &&
            challenge.purpose == expectedPurpose
        val bindingAndStatusMatch = challenge.status == "COMPLETED" &&
            challenge.operationId == operation.id.toString() &&
            challenge.operationHash == hash
        if (!signerAndPurposeMatch || !bindingAndStatusMatch) {
            throw StatutoryDecisionConflict()
        }
    }
}
