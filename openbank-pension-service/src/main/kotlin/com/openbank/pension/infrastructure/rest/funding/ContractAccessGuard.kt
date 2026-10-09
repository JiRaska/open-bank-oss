// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.rest.funding

import com.openbank.libs.security.Roles
import com.openbank.pension.application.port.out.ContractFundingDirectory
import com.openbank.pension.application.port.out.ContractFundingView
import com.openbank.pension.application.port.out.ContractNotFoundException
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Contract ownership for the S3 funding routes (ADR-0334 S3) — the object-level check OPA cannot
 * make, because OPA sees the action and not whose contract it is.
 *
 * - A `ROLE_API` caller is the customer edge acting for a participant. It MUST name that participant
 *   in `X-Customer-Party-Id`, and the contract MUST belong to them. The role is checked first and
 *   decides alone: a machine token that ALSO holds `ROLE_OPERATOR` (a shared service account, #3765)
 *   is still held to the participant check, never promoted to staff.
 * - Staff (`ROLE_OPERATOR` / `ROLE_ADMIN` / `ROLE_COMPLIANCE`, no `ROLE_API`) may read any contract;
 *   if they send the header anyway it must still match.
 *
 * Every denial is a 404, never a 403: a participant probing ids must not learn which exist.
 * When S1's ownership helper lands on `feat/pension-service-bootstrap`, this should delegate to it.
 */
@ApplicationScoped
class ContractAccessGuard(private val directory: ContractFundingDirectory) {

    suspend fun authorize(identity: SecurityIdentity, partyHeader: String?, contractId: UUID): ContractFundingView {
        val contract = directory.find(contractId) ?: throw ContractNotFoundException(contractId)
        val party = partyHeader?.let {
            runCatching { UUID.fromString(it) }.getOrElse { throw IllegalArgumentException("header '$PARTY_HEADER' must be a UUID") }
        }
        val isEdge = identity.hasRole(Roles.API)
        val isStaff = !isEdge && STAFF.any(identity::hasRole)
        val allowed = when {
            isEdge -> party != null && party == contract.participantPartyId
            isStaff -> party == null || party == contract.participantPartyId
            else -> false
        }
        if (!allowed) throw ContractNotFoundException(contractId)
        return contract
    }

    companion object {
        const val PARTY_HEADER = "X-Customer-Party-Id"
        private val STAFF = listOf(Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE)
    }
}
