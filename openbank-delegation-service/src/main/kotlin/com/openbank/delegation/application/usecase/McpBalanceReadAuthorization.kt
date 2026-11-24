// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.ResolveMcpClientGrantUseCase
import com.openbank.delegation.application.port.out.McpIssuerProofReplayStore
import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.libs.security.ReplayClaim
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime
import java.util.UUID

/** The issuer calls this after authenticating the workload and independently verifying DPoP. */
@ApplicationScoped
class McpBalanceReadAuthorization(
    private val grants: ResolveMcpClientGrantUseCase,
    private val ownership: ResourceOwnershipClient,
    private val replay: McpIssuerProofReplayStore,
) {
    data class VerifiedRequest(
        val partyId: UUID,
        val clientId: String,
        val keyThumbprint: String,
        val accountId: UUID,
        val proofKey: String,
        val connectionId: UUID? = null,
    )

    data class Permit(
        val grantId: UUID,
        val accountId: UUID,
        val validUntil: OffsetDateTime,
        val connectionId: UUID? = null,
    )

    suspend fun authorize(request: VerifiedRequest): Permit? {
        val candidates = request.connectionId?.let {
            grants.resolveForConnection(request.partyId, request.clientId, request.keyThumbprint, it)
        } ?: grants.resolve(request.partyId, request.clientId, request.keyThumbprint)
        val grant = candidates.singleOrNull {
            it.resourceType == DelegationResourceType.ACCOUNT &&
                it.mcpConnectionId == request.connectionId &&
                it.resourceId == request.accountId &&
                DelegationCapability.ACCOUNT_READ_BALANCES in it.capabilities
        } ?: return null
        val validUntil = grant.validTo ?: return null
        val verdict = ownership.verifyOwnership(request.partyId, grant.resourceType, request.accountId)
        if (verdict != OwnershipVerdict.OWNED) return null
        if (replay.claim(request.proofKey) != ReplayClaim.FIRST_USE) return null
        return Permit(grant.id, request.accountId, validUntil, request.connectionId)
    }
}
