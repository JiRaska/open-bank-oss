// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.usecase.ProposeWithdrawalCommand
import com.openbank.account.application.usecase.SavingsProposalService
import com.openbank.account.domain.model.WithdrawalProposal
import com.openbank.account.domain.model.WithdrawalProposalStatus
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.time.OffsetDateTime
import java.util.UUID

data class ProposeWithdrawalRequest(
    val delegatePartyId: UUID,
    val amountMinor: Long,
    val currency: String,
    val note: String? = null,
)

data class DecideProposalRequest(val approve: Boolean, val scaSessionId: UUID)

data class ProposalResponse(
    val id: UUID,
    val accountId: UUID,
    val delegatePartyId: UUID,
    val amountMinor: Long,
    val currency: String,
    val note: String?,
    val status: WithdrawalProposalStatus,
    val approvalId: String?,
    val createdAt: OffsetDateTime,
) {
    companion object {
        fun from(p: WithdrawalProposal): ProposalResponse = ProposalResponse(
            id = p.id,
            accountId = p.accountId,
            delegatePartyId = p.delegatePartyId,
            amountMinor = p.amountMinor,
            currency = p.currency,
            note = p.note,
            status = p.status,
            approvalId = p.approvalId,
            createdAt = p.createdAt,
        )
    }
}

/**
 * Propose-only withdrawal flow (ADR-0232 D8 / AC8): the delegate proposes, the owner
 * decides with their own SCA, the approval emits the executable event. The delegate
 * can never decide (store-enforced) and never executes.
 */
@Path("/api/v1/accounts/{accountId}/savings-goal/delegation/proposals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class SavingsProposalResource(private val proposalService: SavingsProposalService) {

    @POST
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#accountId")
    @Operation(summary = "Propose a savings-goal withdrawal (delegate maker; 202 + approvalId)")
    suspend fun propose(@PathParam("accountId") accountId: UUID, request: ProposeWithdrawalRequest?): Response {
        requireNotNull(request) { "request body is required" }
        val created = proposalService.propose(
            ProposeWithdrawalCommand(
                accountId = accountId,
                delegatePartyId = request.delegatePartyId,
                amountMinor = request.amountMinor,
                currency = request.currency,
                note = request.note,
            ),
        )
        return Response.status(202)
            .entity(mapOf("proposal" to ProposalResponse.from(created.proposal), "approvalId" to created.approvalId))
            .build()
    }

    @GET
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#accountId")
    @Operation(summary = "List withdrawal proposals for the account (the owner's inbox)")
    suspend fun list(
        @PathParam("accountId") accountId: UUID,
        @QueryParam("status") status: WithdrawalProposalStatus?,
    ): List<ProposalResponse> = proposalService.listForAccount(accountId, status).map { ProposalResponse.from(it) }

    @POST
    @Path("/{proposalId}/decide")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.authorize", resource = "#accountId")
    @Operation(summary = "Owner decides a proposal — SCA-bound; approval emits SavingsWithdrawalApproved")
    suspend fun decide(
        @PathParam("accountId") accountId: UUID,
        @PathParam("proposalId") proposalId: UUID,
        @QueryParam("decidedByPartyId") decidedByPartyId: UUID,
        request: DecideProposalRequest?,
    ): ProposalResponse {
        requireNotNull(request) { "request body is required" }
        return ProposalResponse.from(
            proposalService.decide(accountId, proposalId, decidedByPartyId, request.approve, request.scaSessionId),
        )
    }

    @DELETE
    @Path("/{proposalId}")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#accountId")
    @Operation(summary = "Delegate cancels their own PENDING proposal")
    suspend fun cancel(
        @PathParam("accountId") accountId: UUID,
        @PathParam("proposalId") proposalId: UUID,
        @QueryParam("delegatePartyId") delegatePartyId: UUID,
    ): ProposalResponse = ProposalResponse.from(proposalService.cancel(accountId, proposalId, delegatePartyId))
}
