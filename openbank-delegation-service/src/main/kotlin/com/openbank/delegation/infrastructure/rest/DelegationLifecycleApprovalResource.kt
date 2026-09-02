// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.port.`in`.DecideDelegationLifecycleCommand
import com.openbank.delegation.application.port.`in`.DelegationLifecycleApprovalQuery
import com.openbank.delegation.application.port.`in`.DelegationLifecycleApprovalUseCase
import com.openbank.delegation.application.port.`in`.ProposeDelegationLifecycleCommand
import com.openbank.delegation.infrastructure.rest.dto.DecideDelegationLifecycleRequest
import com.openbank.delegation.infrastructure.rest.dto.DelegationLifecycleApprovalResponse
import com.openbank.delegation.infrastructure.rest.dto.ProposeDelegationLifecycleRequest
import com.openbank.libs.authz.Authorize
import com.openbank.libs.governance.ProposalState
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * Operator read model plus a dark-launched mutation edge.
 *
 * The admin portal intentionally exposes GET only. POST handlers additionally require the
 * default-false feature gate, a human principal and OPA; without all three they cannot mutate.
 */
@Tag(name = "Delegation lifecycle approvals")
@Path("/api/v1/delegations/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
class DelegationLifecycleApprovalResource(
    private val commands: DelegationLifecycleApprovalUseCase,
    private val queries: DelegationLifecycleApprovalQuery,
) {
    @Inject
    lateinit var identity: SecurityIdentity

    @ConfigProperty(
        name = "openbank.delegation.lifecycle-approvals.mutations-enabled",
        defaultValue = "false",
    )
    var mutationsEnabled: Boolean = false

    @GET
    @Authorize(action = "delegation.approval.read")
    @Operation(summary = "List durable delegation lifecycle approvals (read-only)")
    suspend fun list(
        @QueryParam("state") state: ProposalState?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ): List<DelegationLifecycleApprovalResponse> {
        requireHumanOperator()
        return queries.list(state, limit).map(DelegationLifecycleApprovalResponse::from)
    }

    @GET
    @Path("/{approvalId}")
    @Authorize(action = "delegation.approval.read", resource = "#approvalId")
    @Operation(summary = "Get immutable lifecycle approval evidence")
    suspend fun get(@PathParam("approvalId") approvalId: UUID): DelegationLifecycleApprovalResponse {
        requireHumanOperator()
        return DelegationLifecycleApprovalResponse.from(queries.get(approvalId))
    }

    @POST
    @Authorize(action = "delegation.approval.propose", resource = "#request.delegationId")
    @Operation(summary = "Propose a bank-side lifecycle action (default-off)")
    suspend fun propose(
        request: ProposeDelegationLifecycleRequest?,
        @HeaderParam("X-Request-ID") requestId: String?,
    ): Response {
        requireMutationsEnabled()
        val actor = requireHumanOperator()
        requireNotNull(request) { "request body is required" }
        val key = requestId?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("X-Request-ID header is required")
        val approval = commands.propose(
            ProposeDelegationLifecycleCommand(
                delegationId = request.delegationId,
                operation = request.operation,
                reason = request.reason,
                proposedBy = actor,
                requestKey = key,
            ),
        )
        return Response.status(Response.Status.CREATED)
            .entity(DelegationLifecycleApprovalResponse.from(approval))
            .build()
    }

    @POST
    @Path("/{approvalId}/decision")
    @Authorize(action = "delegation.approval.decide", resource = "#approvalId")
    @Operation(summary = "Reject a lifecycle proposal; approval execution is fail-closed (default-off)")
    suspend fun decide(
        @PathParam("approvalId") approvalId: UUID,
        request: DecideDelegationLifecycleRequest?,
    ): DelegationLifecycleApprovalResponse {
        requireMutationsEnabled()
        val actor = requireHumanOperator()
        requireNotNull(request) { "request body is required" }
        return DelegationLifecycleApprovalResponse.from(
            commands.decide(
                DecideDelegationLifecycleCommand(
                    approvalId = approvalId,
                    approve = request.approve,
                    decidedBy = actor,
                    reason = request.reason,
                ),
            ),
        )
    }

    private fun requireMutationsEnabled() {
        if (!mutationsEnabled) {
            // 404 makes the dark endpoint undiscoverable and cannot be mistaken for a retryable
            // operational failure. The read side remains available independently.
            throw NotFoundException("delegation lifecycle approval mutations are not enabled")
        }
    }

    private fun requireHumanOperator(): String {
        val actor = identity.principal.name?.trim().orEmpty()
        if (actor.isBlank() || actor.startsWith("service-account-")) {
            throw ForbiddenException("a human operator session is required")
        }
        return actor
    }
}
