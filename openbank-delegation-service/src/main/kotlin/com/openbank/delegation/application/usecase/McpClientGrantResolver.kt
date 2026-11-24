// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.ResolveMcpClientGrantUseCase
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationGranteeType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ADR-0383 D3 — the customer MCP channel's grant lookup, keyed by the triple
 * `(grantor party, client id, DPoP key thumbprint)` and by nothing the client supplies.
 *
 * Returns the ACTIVE `MCP_CLIENT` grants that match all three, one per resource. An empty answer is
 * the refusal: there is no "no grant" error the caller could mistake for a transient failure.
 *
 * Why not `delegation.check`: that question is keyed by grantee + resource. Every customer of one
 * MCP client shares the same derived grantee id, so a grantee-keyed check answers "did ANY customer
 * grant this client that account" — the shared-grantee defect ADR-0383 Context item 1 names. The
 * grantor party is part of THIS key, so a grant made by another party never matches.
 */
@ApplicationScoped
class McpClientGrantResolver(private val delegationRepository: DelegationRepository, private val clock: Clock) :
    ResolveMcpClientGrantUseCase {
    @Inject
    constructor(delegationRepository: DelegationRepository) : this(delegationRepository, Clock.systemUTC())

    override suspend fun resolve(grantorPartyId: UUID, clientId: String, keyThumbprint: String): List<DelegationGrant> =
        resolveBound(grantorPartyId, clientId, keyThumbprint, null)

    override suspend fun resolveForConnection(
        grantorPartyId: UUID,
        clientId: String,
        keyThumbprint: String,
        connectionId: UUID,
    ): List<DelegationGrant> = resolveBound(grantorPartyId, clientId, keyThumbprint, connectionId)

    private suspend fun resolveBound(
        grantorPartyId: UUID,
        clientId: String,
        keyThumbprint: String,
        connectionId: UUID?,
    ): List<DelegationGrant> {
        val now = OffsetDateTime.now(clock)
        val granteeId = DelegationGrant.mcpClientGranteeId(clientId)
        // The query already filters on all three keys and ACTIVE; the checks below repeat it as
        // defence in depth, so a query regression cannot hand back another party's grant.
        return delegationRepository.findActiveMcpClientGrants(grantorPartyId, clientId, keyThumbprint).filter { grant ->
            grant.granteePartyId == granteeId &&
                grant.granteeType == DelegationGranteeType.MCP_CLIENT &&
                grant.mcpConnectionId == connectionId &&
                grant.grantorPartyId == grantorPartyId &&
                grant.granteeClientId == clientId &&
                sameThumbprint(grant.granteeKeyThumbprint, keyThumbprint) &&
                grant.exposure == null &&
                grant.isActiveOn(now)
        }
    }

    private fun sameThumbprint(recorded: String?, presented: String): Boolean = recorded != null &&
        MessageDigest.isEqual(
            recorded.toByteArray(StandardCharsets.US_ASCII),
            presented.toByteArray(StandardCharsets.US_ASCII),
        )
}
