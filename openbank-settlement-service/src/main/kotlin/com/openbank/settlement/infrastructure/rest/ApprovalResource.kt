// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.rest

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.web.ApprovalEndpointSupport
import com.openbank.libs.approval.web.DecideApprovalRequest
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.settlement.infrastructure.approval.SettlementApprovalRecord
import com.openbank.settlement.infrastructure.approval.SettlementApprovalRecords
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * Checker-facing endpoint for the four-eyes gate on settlement origination (ADR-0155, #10041
 * slice 10). `settlement.create` is in rules.yaml `four_eyes.actions`; where
 * `authz.four-eyes.enforce=true`, [com.openbank.libs.authz.AuthorizeInterceptor] parks the maker's
 * `POST /api/v1/settlements` with HTTP 202 and an approval bound to the exact instruction
 * (endpoint + arguments, #11675). A DIFFERENT operator decides it here; the maker then retries the
 * identical request with `X-Approval-Id`, which may execute once.
 *
 * Reading and deciding are human-operator only: `settlement_rest_ext.rego` vetoes every
 * `service-account-*` principal on `settlement.approval.*` (the realm M2M clients carry
 * ROLE_OPERATOR and are classified HUMAN). The checker id comes from [SecurityIdentity] and
 * self-approval is refused by [ApprovalStore.decide], both in [ApprovalEndpointSupport].
 */
@Path("/api/v1/settlements/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Settlement Approvals", description = "Four-eyes decisions for settlement origination")
class ApprovalResource(approvalStore: ApprovalStore, private val records: SettlementApprovalRecords) {

    private val support = ApprovalEndpointSupport(approvalStore)

    @Inject
    lateinit var identity: SecurityIdentity

    @GET
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.approval.read", resource = "")
    @Operation(summary = "List pending four-eyes approvals, oldest first (ADR-0227 D2)")
    suspend fun listPending(@QueryParam("limit") @DefaultValue("50") limit: Int): Response = support.listPending(limit)

    /**
     * One approval in any status, expired included, with the bound instruction summary — what the
     * checker reviews before deciding, and the evidence a maker or reconciler reads afterwards.
     * Never authorizes anything: the interceptor consumes approvals through [ApprovalStore] only.
     */
    @GET
    @Path("/{id}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.approval.read", resource = "#id")
    @Operation(summary = "Read one four-eyes approval with its bound instruction, in any status")
    suspend fun get(@PathParam("id") id: String): Response {
        val record = records.findRecord(id) ?: throw NotFoundException("no approval with id=$id")
        return Response.ok(record.toDetail()).header("Cache-Control", "no-store").build()
    }

    @PATCH
    @Path("/{id}")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "settlement.approval.decide", resource = "#id")
    @Operation(summary = "Approve or reject a pending four-eyes approval")
    suspend fun decide(@PathParam("id") id: String, request: DecideApprovalRequest?): Response =
        support.decide(id, request) { identity }
}

/** Evidence view of one approval; `summary` is the redacted, bounded rendering of the bound request. */
data class SettlementApprovalDetail(
    val id: String,
    val action: String,
    val resourceId: String?,
    val status: String,
    val makerId: String,
    val makerActorKind: String,
    val createdAt: String,
    val decidedBy: String?,
    val decidedAt: String?,
    val claimedAt: String?,
    val expiresAt: String,
    val expired: Boolean,
    val summary: String?,
)

private fun SettlementApprovalRecord.toDetail() = SettlementApprovalDetail(
    id = approval.id,
    action = approval.action,
    resourceId = approval.resourceId,
    status = approval.status.name,
    makerId = approval.makerId,
    makerActorKind = approval.makerActorKind.name,
    createdAt = approval.createdAt.toString(),
    decidedBy = approval.decidedBy,
    decidedAt = approval.decidedAt?.toString(),
    claimedAt = claimedAt?.toString(),
    expiresAt = expiresAt.toString(),
    expired = expired,
    summary = approval.summary,
)
