// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.treasury.application.port.`in`.NostroBreakResolutionUseCase
import com.openbank.treasury.domain.model.Actor
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Instant
import java.util.UUID

/**
 * Four-eyes manual resolution of a nostro break that will never match (ADR-0315 D7, ADR-0369):
 * an approver proposes, a different senior approver approves or rejects. Nothing is posted.
 *
 * NOTE the annotation order: `@Path` sits immediately above `class` (#3371).
 */
@Tag(name = "Nostro reconciliation", description = "camt.053 statements vs the ledger nostro GL (ADR-0315)")
@Path("/api/v1/treasury/nostro/{account}/breaks/{breakId}/resolution")
@Produces(MediaType.APPLICATION_JSON)
class NostroBreakResolutionResource {

    @Inject
    lateinit var resolutions: NostroBreakResolutionUseCase

    @Inject
    lateinit var identity: SecurityIdentity

    /**
     * Four-eyes manual resolution (ADR-0315 D7, #14770), step 1: an approver who has investigated an
     * OPEN break proposes closing it with a reason (10..500 characters). The break stays OPEN — it
     * keeps ageing and alerting — until a DIFFERENT person approves. Nothing is ever posted.
     * 404 unknown break or one of another account; 409 resolved or already proposed.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(APPROVER)
    @Operation(summary = "Propose closing an open nostro break with a reason (four-eyes step 1, audited)")
    @Authorize(action = "treasury.nostro.break.propose-resolution", resource = "#breakId")
    suspend fun proposeResolution(
        @PathParam("account") account: String,
        @PathParam("breakId") breakId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ReasonRequest?,
    ): BreakResponse {
        val idempotencyKey = idempotencyKey(key)
        val reason = requireNotNull(request?.reason) { "reason is required" }
        return BreakResponse.from(resolutions.proposeResolution(account, breakId, reason, actor(), idempotencyKey))
    }

    /**
     * Step 2: names the proposal it approves by its `proposedAt` (a proposal rejected and replaced
     * since it was read is a 409). A senior approver, never the proposer (422 FOUR_EYES_VIOLATION,
     * also for a user who holds both roles), closes the break as MANUAL. 409 when there is no pending proposal or the
     * break is already resolved. Closing a break silences the control that surfaces it, so the
     * deciding step takes the senior role.
     */
    @POST
    @Path("/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(SENIOR_APPROVER)
    @Operation(summary = "Approve a proposed nostro break resolution (four-eyes step 2; a different person)")
    @Authorize(action = "treasury.nostro.break.approve-resolution", resource = "#breakId")
    suspend fun approveResolution(
        @PathParam("account") account: String,
        @PathParam("breakId") breakId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ApproveResolutionRequest?,
    ): BreakResponse {
        val idempotencyKey = idempotencyKey(key)
        val proposedAt = requireNotNull(request?.proposedAt) { "proposedAt of the proposal being approved is required" }
        return BreakResponse.from(resolutions.approveResolution(account, breakId, proposedAt, actor(), idempotencyKey))
    }

    /** Step 2, refused: a senior approver rejects the proposal with a reason; the break stays open. */
    @POST
    @Path("/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(SENIOR_APPROVER)
    @Operation(summary = "Reject a proposed nostro break resolution with a reason")
    @Authorize(action = "treasury.nostro.break.reject-resolution", resource = "#breakId")
    suspend fun rejectResolution(
        @PathParam("account") account: String,
        @PathParam("breakId") breakId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ReasonRequest?,
    ): BreakResponse {
        val idempotencyKey = idempotencyKey(key)
        val reason = requireNotNull(request?.reason) { "reason is required" }
        return BreakResponse.from(resolutions.rejectResolution(account, breakId, reason, actor(), idempotencyKey))
    }

    private fun idempotencyKey(key: String?): String {
        val k = requireNotNull(key?.takeIf { it.isNotBlank() }) { "header '$IDEMPOTENCY_KEY' is required" }
        require(k.length <= MAX_KEY_LENGTH) { "header '$IDEMPOTENCY_KEY' is longer than $MAX_KEY_LENGTH" }
        return k
    }

    private fun actor(): Actor = Actor.fromPrincipalName(identity.principal.name)

    private companion object {
        const val MAX_KEY_LENGTH = 128
    }
}

/** Body of `POST .../resolution/approve`: the `proposedAt` of the proposal the approver read. */
data class ApproveResolutionRequest(val proposedAt: Instant? = null)
