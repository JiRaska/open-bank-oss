// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.treasury.application.port.`in`.CashFlowUseCase
import com.openbank.treasury.domain.cashflow.OverrideStatus
import com.openbank.treasury.domain.model.Actor
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
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
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * The stored cash-flow ledger and its four-eyes overrides (ADR-0344 D4). Back office proposes, the
 * approver decides; the domain refuses the proposer as decider and any non-human decider.
 */
@Tag(name = "Treasury", description = "Treasury cash-flow ledger (ADR-0344 D4)")
@Path("/api/v1/treasury/deals/{id}/cash-flows")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, DEALER, APPROVER, SENIOR_APPROVER, BACK_OFFICE, REFERENCE_EDITOR)
class CashFlowResource {

    @Inject
    lateinit var cashFlows: CashFlowUseCase

    @Inject
    lateinit var identity: SecurityIdentity

    private fun actor(): Actor = Actor.fromPrincipalName(identity.principal.name)

    private fun requireKey(key: String?): String = requireNotNull(key) { "header '$IDEMPOTENCY_KEY' is required" }

    @GET
    @Operation(summary = "The deal's stored cash-flow ledger (latest schedule version unless `version`)")
    @Authorize(action = "treasury.cashflow.read", resource = "#id")
    suspend fun ledger(@PathParam("id") id: UUID, @QueryParam("version") version: Int?): CashFlowLedgerDto =
        CashFlowLedgerDto.from(cashFlows.ledger(id, version))

    @GET
    @Path("/conflicts")
    @Operation(summary = "Approved overrides a regeneration could not carry over")
    @Authorize(action = "treasury.cashflow.read", resource = "#id")
    suspend fun conflicts(@PathParam("id") id: UUID): List<CashFlowConflictDto> =
        cashFlows.conflicts(id).map(CashFlowConflictDto::from)

    @GET
    @Path("/overrides")
    @Operation(summary = "The deal's overrides, optionally by status (PENDING = awaiting a second person)")
    @Authorize(action = "treasury.cashflow.read", resource = "#id")
    suspend fun overrides(@PathParam("id") id: UUID, @QueryParam("status") status: String?): List<CashFlowOverrideDto> =
        cashFlows.overrides(id, status?.let { OverrideStatus.valueOf(it) }).map(CashFlowOverrideDto::from)

    @POST
    @Path("/{flowId}/overrides")
    @RolesAllowed(BACK_OFFICE)
    @Operation(
        summary = "Propose an override of one flow's date and/or amount (PENDING until a different human approves)",
    )
    @Authorize(action = "treasury.cashflow.override.propose", resource = "#id")
    suspend fun propose(
        @PathParam("id") id: UUID,
        @PathParam("flowId") flowId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: CashFlowOverrideRequest,
    ): Response {
        val o = cashFlows.proposeOverride(
            id,
            flowId,
            request.paymentDate,
            request.amount,
            requireNotNull(request.reason) { "reason is required" },
            actor(),
            requireKey(key),
        )
        return Response.status(Response.Status.CREATED).entity(CashFlowOverrideDto.from(o)).build()
    }

    @POST
    @Path("/{flowId}/overrides/{overrideId}/approve")
    @RolesAllowed(APPROVER)
    @Operation(summary = "Approve a pending override (four-eyes; never an AI agent)")
    @Authorize(action = "treasury.cashflow.override.approve", resource = "#id")
    suspend fun approve(
        @PathParam("id") id: UUID,
        @PathParam("flowId") flowId: UUID,
        @PathParam("overrideId") overrideId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
    ): CashFlowOverrideDto =
        CashFlowOverrideDto.from(cashFlows.approveOverride(id, flowId, overrideId, actor(), requireKey(key)))

    @POST
    @Path("/{flowId}/overrides/{overrideId}/reject")
    @RolesAllowed(APPROVER)
    @Operation(summary = "Reject a pending override with a reason")
    @Authorize(action = "treasury.cashflow.override.reject", resource = "#id")
    suspend fun reject(
        @PathParam("id") id: UUID,
        @PathParam("flowId") flowId: UUID,
        @PathParam("overrideId") overrideId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ReasonRequest,
    ): CashFlowOverrideDto = CashFlowOverrideDto.from(
        cashFlows.rejectOverride(
            id,
            flowId,
            overrideId,
            requireNotNull(request.reason) {
                "reason is required"
            },
            actor(),
            requireKey(key),
        ),
    )
}
