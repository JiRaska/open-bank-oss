// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.party.application.port.`in`.PartyUseCase
import com.openbank.party.application.usecase.PartyNotFoundException
import com.openbank.party.domain.model.AmlStatus
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyClassification
import com.openbank.party.domain.model.PartyStatus
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import java.util.UUID

/** Purpose-limited identity check; consent and the guarantee contract are proved separately. */
@Path("/api/v1/parties/lending-guarantor-identity")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class LendingGuarantorIdentityResource(private val parties: PartyUseCase, private val identity: SecurityIdentity) {
    @POST
    @Path("/verify")
    @RolesAllowed("ROLE_LENDING_GRAPH_PROOF")
    @Authorize(action = "party.guaranteeIdentity.verify", resource = "")
    suspend fun verify(request: LendingGuarantorIdentityRequest?): LendingGuarantorIdentityResponse {
        // OPA is advisory during rollout; keep the shared backend and staff tokens out here.
        if (identity.principal?.name != LENDING_GRAPH_PRINCIPAL) throw ForbiddenException()
        val partyId = requireNotNull(request) { "request body is required" }.partyId
        val party = try {
            parties.getParty(partyId)
        } catch (_: PartyNotFoundException) {
            return LendingGuarantorIdentityResponse(false)
        }
        return LendingGuarantorIdentityResponse(party.hasVerifiedGuarantorIdentity())
    }

    private companion object {
        const val LENDING_GRAPH_PRINCIPAL = "service-account-openbank-lending-graph"
    }
}

data class LendingGuarantorIdentityRequest(val partyId: UUID)

data class LendingGuarantorIdentityResponse(val verified: Boolean)

internal fun Party.hasVerifiedGuarantorIdentity(): Boolean = classification == PartyClassification.CUSTOMER &&
    status == PartyStatus.ACTIVE &&
    kycStatus == KycStatus.APPROVED &&
    amlStatus == AmlStatus.CLEARED
