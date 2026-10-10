// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.annuity.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.annuity.AnnuityProviderRegistryService
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.domain.annuity.AnnuityProviderStatus
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
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

private const val PARTY_HEADER = ContractAccessGuard.PARTY_HEADER

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
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
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
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
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
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
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
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("partnerId") partnerId: String,
        @HeaderParam(PARTY_HEADER) party: String?,
    ): AnnuityProviderResponse {
        requireIdempotencyKey(idempotencyKey)
        access.staffActor(party)
        return AnnuityProviderResponse.from(registry.disable(partnerId))
    }
}
