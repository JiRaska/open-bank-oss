// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.sdd.application.port.`in`.ListMandatesUseCase
import com.openbank.sdd.infrastructure.rest.dto.MandateResponse
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * The backoffice queue read (ADR-0230 D1): fleet-wide mandate list for the SDD console.
 * Kept as its own resource (separate from the lifecycle-heavy [SddResource], mirroring
 * ApprovalResource's split) — lifecycle mutations stay out of this surface by construction.
 */
@Path("/api/v1/sdd")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "SDD Console")
class SddMandateQueueResource(private val list: ListMandatesUseCase) {

    @GET
    @Path("/mandates/recent")
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    @Authorize(action = "sdd.list")
    @Operation(summary = "Backoffice queue: newest mandates fleet-wide, optionally one status (ADR-0230)")
    fun listRecentMandates(
        @QueryParam("status") status: String?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ): Uni<Response> = list.listRecent(status, limit).map { ms -> Response.ok(ms.map(MandateResponse::of)).build() }
}
