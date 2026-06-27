// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.notification.application.DispatchControlService
import com.openbank.notification.domain.ops.DispatchControlSnapshot
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * Tier A break-glass control plane for the notification dispatch loop (ADR-0047).
 *
 * Actor identity is taken from the authenticated [SecurityIdentity] (JWT subject), never from the
 * request body — so the four-eyes rule cannot be spoofed. `ROLE_SRE` is the intended operator role;
 * until it exists in the realm we gate on `ROLE_OPERATOR`/`ROLE_ADMIN`. `MakerCheckerViolation`
 * (a four-eyes breach) surfaces as HTTP 422 and `NoSuchElementException` as 404 via the shared
 * mappers in `openbank-libs`.
 */
@Path("/api/v1/ops/dispatch")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Ops — Dispatch Control")
class DispatchControlResource(private val service: DispatchControlService, private val identity: SecurityIdentity) {
    data class ReasonRequest(val reason: String? = null)

    private fun actor(): String = identity.principal?.name.orEmpty()

    @GET
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_AUDITOR")
    @Authorize(action = "dispatch.read", resource = "")
    @Operation(summary = "Current dispatch desired-state, deferred-review flag and recent history")
    suspend fun get(): Response {
        val snapshot = service.snapshot()
        return Response.ok(
            mapOf(
                "current" to snapshot.toMap(),
                "history" to service.history(20).map { it.toMap() },
            ),
        ).build()
    }

    @POST
    @Path("/halt")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "dispatch.halt", resource = "")
    @Operation(summary = "Break-glass: halt dispatch immediately (single actor, deferred review required)")
    suspend fun halt(req: ReasonRequest): Response {
        val snapshot = service.halt(actor(), req.reason.orEmpty())
        return Response.ok(snapshot.toMap()).build()
    }

    @POST
    @Path("/resume/propose")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "dispatch.propose", resource = "")
    @Operation(summary = "Propose a resume (four-eyes — a different actor must approve)")
    suspend fun proposeResume(req: ReasonRequest): Response {
        val proposal = service.proposeResume(actor(), req.reason.orEmpty())
        return Response.status(Response.Status.ACCEPTED)
            .entity(mapOf("proposalId" to proposal.id, "state" to proposal.state.name))
            .build()
    }

    @POST
    @Path("/resume/{proposalId}/approve")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "dispatch.approve", resource = "#proposalId")
    @Operation(summary = "Approve and execute a resume (approver must differ from proposer)")
    suspend fun approveResume(@PathParam("proposalId") proposalId: String, req: ReasonRequest): Response {
        val snapshot = service.approveResume(proposalId, actor(), req.reason)
        return Response.ok(snapshot.toMap()).build()
    }

    @POST
    @Path("/resume/{proposalId}/reject")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "dispatch.reject", resource = "#proposalId")
    @Operation(summary = "Reject a pending resume proposal")
    suspend fun rejectResume(@PathParam("proposalId") proposalId: String, req: ReasonRequest): Response {
        val proposal = service.rejectResume(proposalId, actor(), req.reason)
        return Response.ok(mapOf("proposalId" to proposal.id, "state" to proposal.state.name)).build()
    }

    private fun DispatchControlSnapshot.toMap() = mapOf(
        "controlKey" to controlKey,
        "state" to state.name,
        "version" to version,
        "reason" to reason,
        "actor" to actor,
        "effectiveFrom" to effectiveFrom,
        "deferredReviewRequired" to deferredReviewRequired,
    )
}
