// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.usecase.BusinessSigningService
import com.openbank.delegation.application.usecase.SigningPayloadCodec
import com.openbank.delegation.infrastructure.rest.dto.PendingForHumanResponse
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/** Everything waiting for one human's signature across all entities they may sign for now. */
@Tag(name = "Business signing")
@Path("/api/v1/parties/{humanId}/approval-requests")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class PendingApprovalsResource(private val service: BusinessSigningService, private val codec: SigningPayloadCodec) {
    @GET
    @Path("/pending")
    @Authorize(action = "delegation.signing.pending.read", resource = "#humanId")
    @Operation(summary = "Approval requests waiting for this human's signature, grouped by entity")
    suspend fun pending(@PathParam("humanId") humanId: UUID): PendingForHumanResponse =
        PendingForHumanResponse.from(humanId, service.pendingFor(humanId), codec)
}
