// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.annuity.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.annuity.AnnuityMarketplaceService
import com.openbank.pension.application.exit.ExitWorkflowLauncher
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.domain.annuity.AnnuityPurchaseStatus
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

private const val PARTY_HEADER = ContractAccessGuard.PARTY_HEADER

/** Operator view and status sync of annuity purchases (#12383). */
@Tag(name = "Pension operations", description = "Annuity purchases")
@Path("/api/v2/pension/operator/annuity-purchases")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE)
class PensionAnnuityPurchaseResource {

    @Inject
    lateinit var marketplace: AnnuityMarketplaceService

    @Inject
    lateinit var access: ContractAccessGuard

    @Inject
    lateinit var launcher: ExitWorkflowLauncher

    @GET
    @Operation(summary = "Annuity purchases newest first, optionally by status")
    @Authorize(action = "pension.operator.annuity-read")
    suspend fun list(
        @QueryParam("status") status: AnnuityPurchaseStatus?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ): List<AnnuityPurchaseResponse> {
        check(access.readerFor(null) == Caller.STAFF) { "the purchase queue is staff work" }
        return marketplace.list(status, limit).map(AnnuityPurchaseResponse::from)
    }

    @POST
    @Path("/{purchaseId}/sync")
    @Operation(summary = "Ask the partner where an in-flight purchase or a cancellation refund stands, and apply it")
    @Authorize(action = "pension.operator.annuity-purchase")
    suspend fun sync(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("purchaseId") purchaseId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
    ): AnnuityPurchaseResponse {
        requireIdempotencyKey(idempotencyKey)
        access.staffActor(party)
        val synced = marketplace.sync(purchaseId)
        // The payout workflow may have exhausted its retries while the partner was deciding; a
        // re-start is idempotent (workflow id = payout id) and settles the payout from the new state.
        if (synced.status == AnnuityPurchaseStatus.ACTIVE || synced.status == AnnuityPurchaseStatus.FAILED) {
            launcher.startPayout(synced.id)
        }
        return AnnuityPurchaseResponse.from(synced)
    }
}
