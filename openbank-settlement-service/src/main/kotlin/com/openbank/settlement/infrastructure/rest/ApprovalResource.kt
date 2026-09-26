// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.settlement.infrastructure.rest

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.settlement.application.port.`in`.SettlementUseCase
import com.openbank.settlement.infrastructure.approval.PostgresApprovalStore
import com.openbank.settlement.infrastructure.approval.SettlementApprovalHistory
import com.openbank.settlement.infrastructure.approval.SettlementProposalStore
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI

/** A distinct checker decides; the maker retries the bound operation with X-Approval-Id. */
@Path("/api/v1/settlements/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ApprovalResource(
    private val approvals: ApprovalStore,
    private val durableApprovals: PostgresApprovalStore,
    private val identity: SecurityIdentity,
    private val settlements: SettlementUseCase,
    private val history: SettlementApprovalHistory,
    private val proposals: SettlementProposalStore,
) {
    /** Proposal submission cannot call financial execution, regardless of the four-eyes flag. */
    @POST
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.proposal.create", resource = "#request.approvalFingerprint")
    suspend fun propose(request: CreateSettlementRequest?): Response {
        requireNotNull(request) { "a request body is required" }
        val maker = identity.principal.name
        val proposal = proposals.capture(request, maker)
        val approval = approvals.create("settlement.create", request.approvalFingerprint, maker)
        return Response.accepted(
            approval.toResponse().copy(proposalId = proposal.id.toString(), instruction = proposal.instruction()),
        )
            .location(URI.create("/api/v1/settlements/approvals/${approval.id}"))
            .header("Cache-Control", "no-store")
            .build()
    }

    @GET
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.approval.read", resource = "")
    suspend fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): List<ApprovalResponse> =
        approvals.findPending(limit.coerceIn(1, MAX_PENDING_LIMIT)).map { it.toResponse() }

    @GET
    @Path("/{id}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.approval.read", resource = "#id")
    suspend fun get(@PathParam("id") id: String): Response {
        val record = history.readRecord(id) ?: throw NotFoundException("Approval not found")
        val instruction = record.proposal?.instruction()
        val command = instruction?.toCommand()
        val settlement = command?.let { settlements.findById(it.settlementId) }
        val correlation = when {
            command == null -> null
            settlement == null -> SettlementCorrelation.NOT_OBSERVED
            command.matches(settlement) -> SettlementCorrelation.MATCHED
            else -> SettlementCorrelation.CONFLICT
        }
        val detail = record.approval.toResponse().copy(
            proposalId = record.proposal?.id?.toString(),
            instruction = instruction,
            expiresAt = record.expiresAt.toString(),
            expired = record.expired,
            decidedAt = record.approval.decidedAt?.toString(),
            claimedAt = record.claimedAt?.toString(),
            settlementId = settlement?.id?.toString().takeIf { correlation == SettlementCorrelation.MATCHED },
            settlementCorrelation = correlation,
        )
        return Response.ok(detail).header("Cache-Control", "no-store").build()
    }

    @PATCH
    @Path("/{id}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.approval.decide", resource = "#id")
    suspend fun decide(@PathParam("id") id: String, request: DecideApprovalRequest?): ApprovalResponse {
        requireNotNull(request) { "request body is required" }
        val approve = requireNotNull(request.approve) { "approve is required" }
        if (approve) {
            val instruction = requireNotNull(request.instruction) { "the reviewed settlement instruction is required" }
            val approval = approvals.find(id) ?: throw NotFoundException("no pending approval with id=$id")
            val stored = requireNotNull(durableApprovals.proposalForApproval(id)) {
                "Legacy approval has no reviewable instruction; reject it and resubmit"
            }
            require(approval.action == "settlement.create" && approval.resourceId == instruction.approvalFingerprint) {
                "the reviewed instruction does not match the pending settlement"
            }
            require(stored.instruction().approvalFingerprint == instruction.approvalFingerprint) {
                "the reviewed instruction does not match the stored proposal"
            }
        }
        // Match AuthorizeInterceptor's maker identity, including preferred_username semantics.
        val decided = approvals.decide(id, identity.principal.name, approve)
            ?: throw NotFoundException("no pending approval with id=$id")
        return decided.toResponse()
    }

    private companion object {
        const val MAX_PENDING_LIMIT = 200
    }
}

data class DecideApprovalRequest(val approve: Boolean?, val instruction: CreateSettlementRequest? = null)

data class ApprovalResponse(
    val id: String,
    val action: String,
    val resourceId: String?,
    val status: String,
    val makerId: String,
    val createdAt: String,
    val decidedBy: String?,
    val proposalId: String? = null,
    val instruction: CreateSettlementRequest? = null,
    val expiresAt: String? = null,
    val expired: Boolean? = null,
    val decidedAt: String? = null,
    val claimedAt: String? = null,
    val settlementId: String? = null,
    val settlementCorrelation: SettlementCorrelation? = null,
)

/** Correlation is not financial completion, nor proof that an unobserved request never ran. */
enum class SettlementCorrelation { NOT_OBSERVED, MATCHED, CONFLICT }

private fun PendingApproval.toResponse() = ApprovalResponse(
    id = id,
    action = action,
    resourceId = resourceId,
    status = status.name,
    makerId = makerId,
    createdAt = createdAt.toString(),
    decidedBy = decidedBy,
)
