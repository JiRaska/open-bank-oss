// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.usecase.CompliancePackActivationService
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
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
import java.util.UUID

/**
 * Admin surface for compliance pack activation (ADR-0212 D4): the maker proposes a
 * pack, a DIFFERENT compliance principal decides it. Activated packs take effect
 * in-memory immediately and persist for boot rehydration — no service release.
 */
@Path("/api/v1/lending/compliance-packs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Compliance Packs", description = "Four-eyes activation of jurisdictional credit compliance packs")
@RolesAllowed("ROLE_COMPLIANCE", "ROLE_ADMIN")
class CompliancePackResource(
    private val activations: CompliancePackActivationService,
    private val identity: SecurityIdentity,
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper,
) {
    private fun actor(): String = identity.principal?.name.orEmpty()

    @POST
    @Path("/proposals")
    @Authorize(action = "lending.compliance.propose", resource = "")
    @Operation(summary = "Propose a compliance pack for four-eyes activation (maker)")
    fun propose(request: Map<String, Any?>): Uni<Response> =
        activations.propose(mapper.writeValueAsString(request), actor())
            .map { Response.status(HTTP_CREATED).entity(it).build() }
            .onFailure(IllegalArgumentException::class.java)
            .recoverWithItem { e -> Response.status(HTTP_BAD_REQUEST).entity(mapOf("error" to e.message)).build() }

    @POST
    @Path("/proposals/{id}/decide")
    @Authorize(action = "lending.compliance.decide", resource = "#id")
    @Operation(summary = "Approve or reject a pack activation (checker, must differ from maker)")
    fun decide(@PathParam("id") id: UUID, request: DecidePackRequest): Uni<Response> =
        activations.decide(id, request.approve, actor(), request.reason)
            .map { Response.ok(it).build() }
            .onFailure(IllegalArgumentException::class.java)
            .recoverWithItem { e -> Response.status(HTTP_BAD_REQUEST).entity(mapOf("error" to e.message)).build() }
            .onFailure(com.openbank.libs.governance.MakerCheckerViolation::class.java)
            .recoverWithItem { e -> Response.status(HTTP_UNPROCESSABLE).entity(mapOf("error" to e.message)).build() }

    @GET
    @Path("/proposals/pending")
    @Authorize(action = "lending.compliance.read", resource = "")
    @Operation(summary = "List pack proposals awaiting a checker")
    fun listPending(): Uni<Response> = activations.listPending().map { Response.ok(it).build() }

    @GET
    @Path("/active")
    @RolesAllowed("ROLE_COMPLIANCE", "ROLE_CREDIT_RISK", "ROLE_LENDING_OFFICER", "ROLE_ADMIN")
    @Authorize(action = "lending.compliance.read", resource = "")
    @Operation(summary = "List packs currently active in the origination guard")
    fun listActive(): Response = Response.ok(activations.listActive()).build()
}

data class DecidePackRequest(val approve: Boolean, val reason: String? = null)

private const val HTTP_CREATED = 201
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNPROCESSABLE = 422
