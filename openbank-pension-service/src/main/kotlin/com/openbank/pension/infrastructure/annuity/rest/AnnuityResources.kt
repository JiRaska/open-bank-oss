// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.annuity.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.annuity.AnnuityMarketplaceService
import com.openbank.pension.application.annuity.AnnuityNotFoundException
import com.openbank.pension.domain.annuity.FourEyesViolationException
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.resteasy.reactive.server.ServerExceptionMapper
import java.util.UUID

private const val PARTY_HEADER = ContractAccessGuard.PARTY_HEADER

/**
 * Participant side of the annuity marketplace (#12383): request offers from every eligible partner,
 * compare, select one under SCA, and cancel an issued policy within the partner's cooling-off.
 * Ownership is [ContractAccessGuard] + the contract use case (another party's contract is 404).
 *
 * `@Path` directly above `class` (#3371); nullable params checked in the body (#3104).
 */
@Tag(name = "Pension annuity", description = "Annuity offers from partner insurers, selection, cancellation")
@Path("/api/v2/pension/contracts/{contractId}/exit/payouts/{payoutId}/annuity")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
class PensionAnnuityResource {

    @Inject
    lateinit var marketplace: AnnuityMarketplaceService

    @Inject
    lateinit var access: ContractAccessGuard

    @POST
    @Path("/offers")
    @Operation(summary = "Ask every eligible partner insurer for offers, in parallel; partial results are returned")
    @Authorize(action = "pension.annuity.quote", resource = "#contractId")
    suspend fun requestOffers(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("contractId") contractId: UUID,
        @PathParam("payoutId") payoutId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
        request: AnnuityOffersRequest?,
    ): AnnuityPurchaseResponse {
        requireIdempotencyKey(idempotencyKey)
        val prefs = (request ?: AnnuityOffersRequest()).toPreferences()
        return AnnuityPurchaseResponse.from(
            marketplace.requestOffers(access.actingParticipant(party), contractId, payoutId, prefs),
        )
    }

    @GET
    @Operation(summary = "The offers, the selection and the purchase progress of one annuity payout")
    @Authorize(action = "pension.annuity.inspect", resource = "#contractId")
    suspend fun get(
        @PathParam("contractId") contractId: UUID,
        @PathParam("payoutId") payoutId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
    ) = AnnuityPurchaseResponse.from(marketplace.get(access.readerFor(party), contractId, payoutId))

    @POST
    @Path("/selection")
    @Operation(summary = "Select one offer under SCA (the challenge signs partner, offer id and amounts)")
    @Authorize(action = "pension.annuity.select", resource = "#contractId")
    suspend fun select(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("contractId") contractId: UUID,
        @PathParam("payoutId") payoutId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
        request: AnnuitySelectionRequest?,
    ): AnnuityPurchaseResponse {
        requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        return AnnuityPurchaseResponse.from(
            marketplace.select(
                access.actingParticipant(party),
                contractId,
                payoutId,
                requireNotNull(body.partnerId) { "partnerId is required" },
                requireNotNull(body.offerId) { "offerId is required" },
                requireNotNull(body.scaChallengeId) { "scaChallengeId is required" },
            ),
        )
    }

    @POST
    @Path("/cancellation")
    @Operation(summary = "Cancel the issued policy within the partner's cooling-off period (SCA)")
    @Authorize(action = "pension.annuity.cancel", resource = "#contractId")
    suspend fun cancel(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("contractId") contractId: UUID,
        @PathParam("payoutId") payoutId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
        request: AnnuityCancellationRequest?,
    ): AnnuityPurchaseResponse {
        requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        return AnnuityPurchaseResponse.from(
            marketplace.cancel(
                access.actingParticipant(party),
                contractId,
                payoutId,
                requireNotNull(body.scaChallengeId) { "scaChallengeId is required" },
            ),
        )
    }
}

/** Only the exceptions this slice owns; `IllegalArgument/State` are mapped by libs-runtime and S1. */
class AnnuityExceptionMappers {
    @ServerExceptionMapper
    fun notFound(e: AnnuityNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()

    @ServerExceptionMapper
    fun fourEyes(e: FourEyesViolationException): Response =
        Response.status(Response.Status.FORBIDDEN).entity(mapOf("error" to e.message)).build()
}
