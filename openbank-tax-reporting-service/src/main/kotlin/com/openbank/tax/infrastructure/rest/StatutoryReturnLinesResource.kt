// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.tax.application.usecase.StatutoryReturnService
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * The line-level artefact of a line-based return (CRS/FATCA, ADR-0340). Split from
 * [StatutoryReturnResource] so the response resource stays under the function-count budget;
 * the read gate is identical to the figure read.
 */
@Path("/api/v1/statutory-returns")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "StatutoryReturn", description = "Catalogue-defined regulatory returns (ADR-0336)")
class StatutoryReturnLinesResource(private val service: StatutoryReturnService) {

    @GET
    @Path("/{id}/lines")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "tax.statutory-return.inspect")
    @Operation(
        summary = "The line-level artefact of a line-based return (CRS/FATCA); empty for figure-only returns",
    )
    suspend fun lines(@PathParam("id") id: String): Response =
        Response.ok(mapOf("lines" to service.get(parseId(id)).lines.map { it.fields })).build()

    private fun parseId(value: String): UUID = runCatching { UUID.fromString(value) }.getOrNull()
        ?: throw WebApplicationException("id must be a UUID (got '$value')", Response.Status.BAD_REQUEST)
}
