// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.usecase.ProposalForbiddenException
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
import jakarta.ws.rs.HeaderParam
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
 *
 * ## Who the caller is comes from the edge, never from the request
 *
 * Every party id these endpoints act on is read from [AccountResource.CUSTOMER_PARTY_HEADER],
 * which customer-edge stamps with the caller's validated party. It used to arrive as a query
 * parameter (`decidedByPartyId`) and a body field (`delegatePartyId`), which made the service's
 * own guard — `account.partyId != decidedByPartyId`, "only the owner may decide" — a comparison
 * between database state and a value the caller chose. That is defect C3 of the #3164 P0 chain,
 * and `AccountResource` two files away already reads the caller this way.
 *
 * The SCA check (`verifyOwnerSca`) did stop the obvious abuse, because it demands a COMPLETED
 * challenge belonging to the owner. But that made SCA the *authentication*, not the second
 * factor, and it was the only thing left standing.
 *
 * The header is REQUIRED here, unlike on the read paths where its absence means an operator
 * call: proposing and deciding are inherently customer actions, and an unattributable one must
 * not resolve to "whoever the body says".
 */
@Path("/api/v1/accounts/{accountId}/savings-goal/delegation/proposals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class SavingsProposalResource(private val proposalService: SavingsProposalService) {

    @POST
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "account.read", resource = "#accountId")
    @Operation(summary = "Propose a savings-goal withdrawal (delegate maker; 202 + approvalId)")
    suspend fun propose(
        @PathParam("accountId") accountId: UUID,
        @HeaderParam(AccountResource.CUSTOMER_PARTY_HEADER) callerPartyId: UUID?,
        request: ProposeWithdrawalRequest?,
    ): Response {
        requireNotNull(request) { "request body is required" }
        val created = proposalService.propose(
            ProposeWithdrawalCommand(
                accountId = accountId,
                delegatePartyId = caller(callerPartyId),
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
        @HeaderParam(AccountResource.CUSTOMER_PARTY_HEADER) callerPartyId: UUID?,
        request: DecideProposalRequest?,
    ): ProposalResponse {
        requireNotNull(request) { "request body is required" }
        return ProposalResponse.from(
            proposalService.decide(
                accountId,
                proposalId,
                caller(callerPartyId),
                request.approve,
                request.scaSessionId,
            ),
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
        @HeaderParam(AccountResource.CUSTOMER_PARTY_HEADER) callerPartyId: UUID?,
    ): ProposalResponse =
        ProposalResponse.from(proposalService.cancel(accountId, proposalId, caller(callerPartyId)))

    /** Fail closed: a call with no attributable caller is refused, never defaulted. */
    private fun caller(callerPartyId: UUID?): UUID = callerPartyId
        ?: throw ProposalForbiddenException(
            "${AccountResource.CUSTOMER_PARTY_HEADER} is required — the caller's party cannot come from the request",
        )
}
