// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationGranteeType
import com.openbank.delegation.domain.model.DelegationResourceType
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class McpBalanceReadGrantCheck(private val repository: DelegationRepository) {
    data class Binding(
        val grantId: UUID,
        val partyId: UUID,
        val clientId: String,
        val accountId: UUID,
        val connectionId: UUID? = null,
    )

    suspend fun isActive(binding: Binding): Boolean {
        val grant = repository.findById(binding.grantId) ?: return false
        return grant.id == binding.grantId &&
            grant.mcpConnectionId == binding.connectionId &&
            grant.granteeType == DelegationGranteeType.MCP_CLIENT &&
            grant.grantorPartyId == binding.partyId &&
            grant.granteeClientId == binding.clientId &&
            grant.granteePartyId == DelegationGrant.mcpClientGranteeId(binding.clientId) &&
            grant.resourceType == DelegationResourceType.ACCOUNT &&
            grant.resourceId == binding.accountId &&
            grant.exposure == null &&
            grant.validTo != null &&
            DelegationCapability.ACCOUNT_READ_BALANCES in grant.capabilities &&
            grant.isActiveOn(OffsetDateTime.now())
    }
}
