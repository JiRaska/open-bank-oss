// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.notification.domain.model.DeviceRegistration
import com.openbank.notification.domain.model.PushPlatform
import com.openbank.notification.infrastructure.persistence.entity.DeviceTokenEntity
import com.openbank.notification.infrastructure.persistence.repository.DeviceTokenRepository
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * Push device-token registry (PUSH fan-out target). Customer app → cluster traffic reaches
 * this through openbank-customer-edge, which injects the authoritative partyId from the
 * customer JWT (the body's partyId is never trusted on its own — IDOR prevention at the edge).
 *
 * The provider-issued token is PII-adjacent: it is accepted on write but never returned in a
 * response. Listings expose only non-sensitive metadata (platform, status, app instance, dates).
 */
@Path("/api/v1/devices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Devices")
class DeviceResource {

    @Inject
    lateinit var repo: DeviceTokenRepository

    data class RegisterDeviceRequest(
        val partyId: UUID? = null,
        val platform: String = "",
        val token: String = "",
        val appInstance: String = "",
        val appVersion: String? = null,
        val osVersion: String? = null,
    )

    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_API", "ROLE_ADMIN")
    @Authorize(action = "device.create", resource = "")
    @Operation(summary = "Register (upsert) a push device token for a party")
    suspend fun register(req: RegisterDeviceRequest): Response {
        val partyId = req.partyId
            ?: return badRequest("partyId is required")
        if (req.token.isBlank()) return badRequest("token is required")
        if (req.appInstance.isBlank()) return badRequest("appInstance is required")
        val platform = runCatching { PushPlatform.valueOf(req.platform.uppercase()) }.getOrNull()
            ?: return badRequest("platform must be one of ${PushPlatform.entries.joinToString { it.name }}")

        val saved = repo.register(
            DeviceRegistration(
                partyId = partyId,
                appInstance = req.appInstance,
                platform = platform,
                token = req.token,
                appVersion = req.appVersion,
                osVersion = req.osVersion,
            ),
        )
        return Response.status(Response.Status.CREATED).entity(view(saved)).build()
    }

    @GET
    @RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "device.list", resource = "")
    @Operation(summary = "List a party's registered devices (no tokens returned)")
    suspend fun list(@QueryParam("partyId") partyId: UUID?): Response {
        partyId ?: return badRequest("partyId query parameter is required")
        val items = repo.listByParty(partyId)
        return Response.ok(mapOf("items" to items.map { view(it) }, "total" to items.size)).build()
    }

    @DELETE
    @Path("/{deviceId}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_API", "ROLE_ADMIN")
    @Authorize(action = "device.delete", resource = "")
    @Operation(summary = "Deactivate a push device token (logout / uninstall)")
    suspend fun deactivate(@PathParam("deviceId") deviceId: UUID, @QueryParam("partyId") partyId: UUID?): Response {
        // customer-edge translates the mobile app JWT to ROLE_API and injects the
        // authoritative partyId — no direct ROLE_CUSTOMER access (prevents IDOR at the edge).
        // When partyId is supplied the UPDATE is scoped to that party's tokens only.
        val deactivated = repo.deactivate(deviceId, partyId)
        return if (deactivated) {
            Response.noContent().build()
        } else {
            Response.status(Response.Status.NOT_FOUND).entity(
                mapOf(
                    "code" to "NOT_FOUND",
                    "message" to "Device not found or already inactive",
                ),
            ).build()
        }
    }

    // Deliberately omits `token` — the provider token never leaves the registry.
    private fun view(e: DeviceTokenEntity): Map<String, Any?> = mapOf(
        "id" to e.deviceId,
        "partyId" to e.partyId,
        "platform" to e.platform,
        "appInstance" to e.appInstance,
        "appVersion" to e.appVersion,
        "osVersion" to e.osVersion,
        "status" to e.status,
        "registeredAt" to e.registeredAt,
        "refreshedAt" to e.refreshedAt,
        "lastUsedAt" to e.lastUsedAt,
        "createdAt" to e.createdAt,
        "updatedAt" to e.updatedAt,
    )

    private fun badRequest(message: String): Response = Response.status(
        Response.Status.BAD_REQUEST,
    ).entity(mapOf("code" to "BAD_REQUEST", "message" to message)).build()
}
