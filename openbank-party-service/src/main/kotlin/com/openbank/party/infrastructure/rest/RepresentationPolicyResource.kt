// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.party.application.usecase.RepresentationPolicyQuery
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import java.util.UUID

/** Internal source evidence for an operation's statutory-authority resolver, not an admission token. */
@Path("/api/v1/parties/{id}/representation-policy")
@Produces(MediaType.APPLICATION_JSON)
class RepresentationPolicyResource(private val query: RepresentationPolicyQuery) {
    @GET
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC")
    @Authorize(action = "party.mandate.read", resource = "#id")
    @Operation(summary = "Latest signed statutory-rule evidence; validate current authority before use")
    suspend fun latest(@PathParam("id") id: UUID): Response =
        query.latestEvidence(id)?.let { Response.ok(it).build() } ?: Response.status(Response.Status.NOT_FOUND).build()
}
