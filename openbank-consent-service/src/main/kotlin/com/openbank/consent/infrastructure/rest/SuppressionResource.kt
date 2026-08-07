// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure.rest

import com.openbank.consent.application.usecase.SuppressionNotFoundException
import com.openbank.consent.application.usecase.SuppressionService
import com.openbank.consent.domain.model.Suppression
import com.openbank.consent.domain.model.SuppressionReason
import com.openbank.consent.domain.model.SuppressionScope
import com.openbank.libs.authz.Authorize
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import java.time.OffsetDateTime
import java.util.UUID

data class CreateSuppressionRequest(
    val partyId: UUID,
    val scope: SuppressionScope,
    val value: String? = null,
    val reason: SuppressionReason,
    val source: String,
)

data class SuppressionResponse(
    val id: UUID,
    val partyId: UUID,
    val scope: SuppressionScope,
    val value: String?,
    val reason: SuppressionReason,
    val source: String,
    val createdBy: String,
    val createdAt: OffsetDateTime,
) {
    companion object {
        fun from(s: Suppression) =
            SuppressionResponse(s.id, s.partyId, s.scope, s.value, s.reason, s.source, s.createdBy, s.createdAt)
    }
}

/**
 * ADR-0219 D3 suppression administration (#3656 slice 2): the granular do-not-contact that is not
 * a consent revocation. Operator-managed (admin-ui surfaces: preference centre, complaints flow,
 * RM workbench); the contact-policy gate reads the active list per party.
 */
@Path("/api/v1/suppressions")
@ApplicationScoped
class SuppressionResource(private val service: SuppressionService, private val identity: SecurityIdentity) {

    @POST
    @Authorize(action = "suppression.manage", resource = "#request.partyId")
    suspend fun create(request: CreateSuppressionRequest): Response = runCatching {
        val created = service.create(
            partyId = request.partyId,
            scope = request.scope,
            value = request.value,
            reason = request.reason,
            source = request.source,
            createdBy = identity.principal?.name ?: "unknown",
        )
        Response.status(Response.Status.CREATED).entity(SuppressionResponse.from(created)).build()
    }.getOrElse {
        Response.status(Response.Status.BAD_REQUEST).entity(mapOf("error" to it.message)).build()
    }

    @GET
    @Path("/party/{partyId}")
    @Authorize(action = "suppression.read", resource = "#partyId")
    suspend fun listActive(@PathParam("partyId") partyId: UUID): List<SuppressionResponse> =
        service.listActive(partyId).map(SuppressionResponse::from)

    @POST
    @Path("/{id}/revoke")
    @Authorize(action = "suppression.manage", resource = "#id")
    suspend fun revoke(@PathParam("id") id: UUID): Response = runCatching {
        Response.ok(SuppressionResponse.from(service.revoke(id, identity.principal?.name ?: "unknown"))).build()
    }.getOrElse {
        val status = if (it is SuppressionNotFoundException) {
            Response.Status.NOT_FOUND
        } else {
            Response.Status.CONFLICT
        }
        Response.status(status).entity(mapOf("error" to it.message)).build()
    }
}
