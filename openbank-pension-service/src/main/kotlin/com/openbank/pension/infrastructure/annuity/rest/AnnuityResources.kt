// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.annuity.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.annuity.AnnuityMarketplaceService
import com.openbank.pension.application.annuity.AnnuityNotFoundException
import com.openbank.pension.application.annuity.AnnuityProviderRegistryService
import com.openbank.pension.application.exit.ExitWorkflowLauncher
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.domain.annuity.AnnuityProviderStatus
import com.openbank.pension.domain.annuity.AnnuityPurchaseStatus
import com.openbank.pension.domain.annuity.FourEyesViolationException
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
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
import org.jboss.resteasy.reactive.server.ServerExceptionMapper
import java.util.UUID

private const val PARTY_HEADER = ContractAccessGuard.PARTY_HEADER
private const val IDEMPOTENCY_HEADER = "Idempotency-Key"

/**
 * Participant side of the annuity marketplace (#12383): request offers from every eligible partner,
 * compare, select one under SCA, and cancel an issued policy within the partner's cooling-off.
 * Ownership is [ContractAccessGuard] + the contract use case (another party's contract is 404).
 *
 * `@Path` directly above `class` (#3371); nullable params checked in the body (#3104).
 */
@Tag(name = "Pension annuity", description = "Annuity offers from partner insurers, selection, cancellation")
@Path("/api/v1/pension/contracts/{contractId}/exit/payouts/{payoutId}/annuity")
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
        @HeaderParam(IDEMPOTENCY_HEADER) idempotencyKey: String?,
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
    @Authorize(action = "pension.annuity.read", resource = "#contractId")
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
        @HeaderParam(IDEMPOTENCY_HEADER) idempotencyKey: String?,
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
        @HeaderParam(IDEMPOTENCY_HEADER) idempotencyKey: String?,
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

/**
 * Operator registry of partner insurers (#12383): CRUD under FOUR-EYES activation — the approver
 * must differ from whoever edited the terms or requested activation (enforced in the aggregate).
 * Real human staff only (OPA: `operator-pension-annuity-*`; [ContractAccessGuard.staffActor]).
 */
@Tag(name = "Pension operations", description = "Annuity partner registry")
@Path("/api/v1/pension/operator/annuity-providers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE)
class PensionAnnuityProviderResource {

    @Inject
    lateinit var registry: AnnuityProviderRegistryService

    @Inject
    lateinit var access: ContractAccessGuard

    @GET
    @Operation(summary = "Partner insurers, optionally by status")
    @Authorize(action = "pension.operator.annuity-read")
    suspend fun list(@QueryParam("status") status: AnnuityProviderStatus?): List<AnnuityProviderResponse> {
        check(access.readerFor(null) == Caller.STAFF) { "the partner registry is staff work" }
        return registry.list(status).map(AnnuityProviderResponse::from)
    }

    @GET
    @Path("/{partnerId}")
    @Operation(summary = "One partner insurer")
    @Authorize(action = "pension.operator.annuity-read")
    suspend fun get(@PathParam("partnerId") partnerId: String): AnnuityProviderResponse {
        check(access.readerFor(null) == Caller.STAFF) { "the partner registry is staff work" }
        return AnnuityProviderResponse.from(registry.get(partnerId))
    }

    @POST
    @Operation(summary = "Register a partner insurer as DRAFT (maker)")
    @Authorize(action = "pension.operator.annuity-write")
    suspend fun create(
        @HeaderParam(IDEMPOTENCY_HEADER) idempotencyKey: String?,
        @HeaderParam(PARTY_HEADER) party: String?,
        request: CreateAnnuityProviderRequest?,
    ): Response {
        requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        val created = registry.create(
            requireNotNull(body.partnerId) { "partnerId is required" },
            requireNotNull(body.terms) { "terms is required" }.toTerms(),
            access.staffActor(party),
        )
        return Response.status(Response.Status.CREATED).entity(AnnuityProviderResponse.from(created)).build()
    }

    @PUT
    @Path("/{partnerId}")
    @Operation(summary = "Propose new terms; the approved version stays live until a different person approves")
    @Authorize(action = "pension.operator.annuity-write")
    suspend fun amend(
        @PathParam("partnerId") partnerId: String,
        @HeaderParam(PARTY_HEADER) party: String?,
        request: AnnuityProviderTermsRequest?,
    ) = AnnuityProviderResponse.from(
        registry.amend(
            partnerId,
            requireNotNull(request) {
                "request body is required"
            }.toTerms(),
            access.staffActor(party),
        ),
    )

    @POST
    @Path("/{partnerId}/activation-request")
    @Operation(summary = "Request activation (maker)")
    @Authorize(action = "pension.operator.annuity-write")
    suspend fun requestActivation(
        @HeaderParam(IDEMPOTENCY_HEADER) idempotencyKey: String?,
        @PathParam("partnerId") partnerId: String,
        @HeaderParam(PARTY_HEADER) party: String?,
    ): AnnuityProviderResponse {
        requireIdempotencyKey(idempotencyKey)
        return AnnuityProviderResponse.from(registry.requestActivation(partnerId, access.staffActor(party)))
    }

    @POST
    @Path("/{partnerId}/activation-approval")
    @Operation(summary = "Approve activation (checker: never the editor or the requester)")
    @Authorize(action = "pension.operator.annuity-approve")
    suspend fun approveActivation(
        @HeaderParam(IDEMPOTENCY_HEADER) idempotencyKey: String?,
        @PathParam("partnerId") partnerId: String,
        @HeaderParam(PARTY_HEADER) party: String?,
    ): AnnuityProviderResponse {
        requireIdempotencyKey(idempotencyKey)
        return AnnuityProviderResponse.from(registry.approveActivation(partnerId, access.staffActor(party)))
    }

    @POST
    @Path("/{partnerId}/disable")
    @Operation(summary = "Stop asking this partner for quotes (purchases already in flight continue)")
    @Authorize(action = "pension.operator.annuity-write")
    suspend fun disable(
        @HeaderParam(IDEMPOTENCY_HEADER) idempotencyKey: String?,
        @PathParam("partnerId") partnerId: String,
        @HeaderParam(PARTY_HEADER) party: String?,
    ): AnnuityProviderResponse {
        requireIdempotencyKey(idempotencyKey)
        access.staffActor(party)
        return AnnuityProviderResponse.from(registry.disable(partnerId))
    }
}

/** Operator view and status sync of annuity purchases (#12383). */
@Tag(name = "Pension operations", description = "Annuity purchases")
@Path("/api/v1/pension/operator/annuity-purchases")
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
        @HeaderParam(IDEMPOTENCY_HEADER) idempotencyKey: String?,
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

/** Only the exceptions this slice owns; `IllegalArgument/State` are mapped by libs-runtime and S1. */
class AnnuityExceptionMappers {
    @ServerExceptionMapper
    fun notFound(e: AnnuityNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()

    @ServerExceptionMapper
    fun fourEyes(e: FourEyesViolationException): Response =
        Response.status(Response.Status.FORBIDDEN).entity(mapOf("error" to e.message)).build()
}
