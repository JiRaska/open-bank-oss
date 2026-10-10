// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.exit.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.exit.ClaimantDesignation
import com.openbank.pension.application.exit.ClaimantKyc
import com.openbank.pension.application.exit.DeathClaimService
import com.openbank.pension.application.exit.NotifyDeathCommand
import com.openbank.pension.application.exit.requireKey
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.domain.exit.DeathClaimStatus
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
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
 * Operator-side death claims (ADR-0334 S5). Staff only — the customer edge has no route here, and
 * OPA grants these actions to human operators, never to a `service-account-`. Approval is
 * four-eyes, enforced in the aggregate (the registering operator cannot approve).
 */
@Tag(name = "Pension death claims", description = "Operator-driven settlement on the participant's death")
@Path("/api/v1/pension/death-claims")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
class PensionDeathClaimResource {

    @Inject
    lateinit var claims: DeathClaimService

    @Inject
    lateinit var identity: SecurityIdentity

    @Inject
    lateinit var access: ContractAccessGuard

    /** Staff only, through the same guard as every contract route: no party header, staff role required. */
    private fun operator(): String {
        check(access.readerFor(null) == Caller.STAFF) { "death claims are staff work" }
        return identity.principal.name
    }

    @POST
    @Operation(summary = "Register a death with evidence; freezes the contract")
    @Authorize(action = "pension.death.notify")
    suspend fun notifyDeath(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: NotifyDeathRequest?,
    ): Response {
        val key = requireKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        val claim = claims.notify(
            NotifyDeathCommand(
                operator(),
                requireNotNull(body.contractId) { "contractId is required" },
                requireNotNull(body.dateOfDeath) { "dateOfDeath is required" },
                requireNotNull(body.evidenceRef) { "evidenceRef is required" },
                key,
            ),
        )
        return Response.status(Response.Status.CREATED).entity(DeathClaimResponse.from(claim)).build()
    }

    @GET
    @Operation(summary = "Death claims, newest first, optionally by status (operator queue)")
    @Authorize(action = "pension.death.inspect")
    suspend fun list(
        @QueryParam("status") status: DeathClaimStatus?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ): List<DeathClaimResponse> {
        operator()
        return claims.list(status, limit).map(DeathClaimResponse::from)
    }

    @GET
    @Path("/{claimId}")
    @Operation(summary = "One death claim with its claimants")
    @Authorize(action = "pension.death.inspect", resource = "#claimId")
    suspend fun get(@PathParam("claimId") claimId: UUID) = DeathClaimResponse.from(claims.get(claimId))

    @PUT
    @Path("/{claimId}/claimants")
    @Operation(summary = "Replace the claimants (shares must total 100 %) while the claim is NOTIFIED")
    @Authorize(action = "pension.death.verify", resource = "#claimId")
    suspend fun replaceClaimants(@PathParam("claimId") claimId: UUID, request: ClaimantsRequest?): DeathClaimResponse {
        val list =
            requireNotNull(requireNotNull(request) { "request body is required" }.claimants) { "claimants is required" }
        val designations = list.mapIndexed { i, c ->
            val item = requireNotNull(c) { "claimants[$i] must not be null" }
            ClaimantDesignation(
                requireNotNull(item.name) { "claimants[$i].name is required" },
                item.partyId,
                requireNotNull(item.sharePercent) { "claimants[$i].sharePercent is required" },
                item.estate ?: false,
            )
        }
        return DeathClaimResponse.from(claims.replaceClaimants(claimId, designations))
    }

    @POST
    @Path("/{claimId}/claimants/{claimantId}/verification")
    @Operation(summary = "KYC-light verification of one claimant and their payout account")
    @Authorize(action = "pension.death.verify", resource = "#claimId")
    suspend fun verify(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("claimId") claimId: UUID,
        @PathParam("claimantId") claimantId: UUID,
        request: VerifyClaimantRequest?,
    ): DeathClaimResponse {
        requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        val kyc = ClaimantKyc(
            requireNotNull(body.name) { "name is required" },
            requireNotNull(body.birthDate) { "birthDate is required" },
            requireNotNull(body.identityDocumentRef) { "identityDocumentRef is required" },
            requireNotNull(body.iban) { "iban is required" },
        )
        return DeathClaimResponse.from(claims.verifyClaimant(operator(), claimId, claimantId, kyc))
    }

    @POST
    @Path("/{claimId}/approve")
    @Operation(summary = "Four-eyes approval: values the contract, fixes each share and starts the payouts")
    @Authorize(action = "pension.death.approve", resource = "#claimId")
    suspend fun approve(
        @PathParam("claimId") claimId: UUID,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): DeathClaimResponse {
        requireKey(idempotencyKey)
        return DeathClaimResponse.from(claims.approve(operator(), claimId))
    }
}
