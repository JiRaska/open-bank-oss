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
 * Contract ownership for the S3 funding routes — the SAME rule S1's `PensionContractResource`
 * applies (`caller()` + `Caller.requireParticipant`), so the two route families cannot disagree:
 *
 * - `X-Customer-Party-Id` present (stamped by the customer edge from the token it validated): the
 *   caller is confined to that party's contracts, whatever roles it holds. A foreign or unknown
 *   contract is a 404 — never a 403, so ids cannot be probed.
 * - Header absent: only staff (`ROLE_OPERATOR` / `ROLE_ADMIN` / `ROLE_COMPLIANCE`) may proceed, and
 *   only to READ; any other caller is a 400 for the missing header.
 * - A write ([forWrite]) always acts for a participant: staff never mutate a contract's funding.
 *
 * TEMPORARY: S1 is extracting one shared guard into `infrastructure/authz`; when it lands this
 * class is deleted and every funding route goes through the shared one.
 */
@ApplicationScoped
class ContractAccessGuard(private val directory: ContractFundingDirectory) {

    suspend fun forRead(identity: SecurityIdentity, partyHeader: String?, contractId: UUID): ContractFundingView =
        authorize(identity, partyHeader, contractId, write = false)

    suspend fun forWrite(identity: SecurityIdentity, partyHeader: String?, contractId: UUID): ContractFundingView =
        authorize(identity, partyHeader, contractId, write = true)

    @Suppress("ThrowsCount")
    private suspend fun authorize(
        identity: SecurityIdentity,
        partyHeader: String?,
        contractId: UUID,
        write: Boolean,
    ): ContractFundingView {
        val party = partyHeader?.let {
            runCatching {
                UUID.fromString(it)
            }.getOrElse { throw IllegalArgumentException("header '$PARTY_HEADER' must be a UUID") }
        }
        if (party == null) {
            require(STAFF_ROLES.any(identity::hasRole)) { "header '$PARTY_HEADER' is required" }
            require(!write) { "a contract change must be made for the participant" }
        }
        val contract = directory.find(contractId) ?: throw ContractNotFoundException(contractId)
        if (party != null && party != contract.participantPartyId) throw ContractNotFoundException(contractId)
        return contract
    }

    companion object {
        const val PARTY_HEADER = "X-Customer-Party-Id"
        val STAFF_ROLES = listOf(Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE)
    }
}
