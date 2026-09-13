// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.usecase.DelegationRolePresetService
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationRolePreset
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.OffsetDateTime
import java.util.UUID

data class DelegationRolePresetRequest(
    val name: String,
    val description: String = "",
    val resourceType: DelegationResourceType,
    val capabilities: Set<DelegationCapability>,
)

data class DelegationRolePresetResponse(
    val id: UUID,
    val name: String,
    val description: String,
    val resourceType: DelegationResourceType,
    val capabilities: Set<DelegationCapability>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(value: DelegationRolePreset) = DelegationRolePresetResponse(
            value.id,
            value.name,
            value.description,
            value.resourceType,
            value.capabilities,
            value.createdAt,
            value.updatedAt,
        )
    }
}

@Path("/api/v1/delegation-role-presets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
class DelegationRolePresetResource(private val service: DelegationRolePresetService) {
    @GET
    suspend fun list() = service.list().map(DelegationRolePresetResponse::from)

    @POST
    @RolesAllowed("ROLE_ADMIN")
    suspend fun create(request: DelegationRolePresetRequest?): Response {
        requireNotNull(request) { "request body is required" }
        val created = service.create(request.name, request.description, request.resourceType, request.capabilities)
        return Response.status(Response.Status.CREATED).entity(DelegationRolePresetResponse.from(created)).build()
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("ROLE_ADMIN")
    suspend fun update(@PathParam("id") id: UUID, request: DelegationRolePresetRequest?): DelegationRolePresetResponse {
        requireNotNull(request) { "request body is required" }
        return DelegationRolePresetResponse.from(
            service.update(id, request.name, request.description, request.resourceType, request.capabilities),
        )
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("ROLE_ADMIN")
    suspend fun delete(@PathParam("id") id: UUID): Response {
        service.delete(id)
        return Response.noContent().build()
    }
}
