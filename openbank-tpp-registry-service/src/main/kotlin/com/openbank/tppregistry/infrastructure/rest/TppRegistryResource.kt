// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.tppregistry.application.port.`in`.BlacklistTppCommand
import com.openbank.tppregistry.application.port.`in`.CheckTppAuthorizationQuery
import com.openbank.tppregistry.application.port.`in`.GetTppQuery
import com.openbank.tppregistry.application.port.`in`.ListTppsQuery
import com.openbank.tppregistry.application.port.`in`.RegisterTppCommand
import com.openbank.tppregistry.application.port.`in`.TppRegistryUseCase
import com.openbank.tppregistry.domain.model.TppRole
import com.openbank.tppregistry.domain.model.TppStatus
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/tpp-registry")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class TppRegistryResource(
    private val svc: TppRegistryUseCase,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {
    @GET
    @Path("/check")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun checkAuthorization(@QueryParam("tppId") tppId: String?, @QueryParam("role") role: String?): Response {
        // #3624 — both name WHAT is being checked, so neither can default: this endpoint answers
        // "is this TPP authorised for this role", and a guessed role would answer a question the
        // caller did not ask. `suspend` emits no Intrinsics.checkNotNullParameter, so the nulls
        // flowed in and `role.uppercase()` threw NPE -> 500. libs-runtime maps this to 400.
        requireNotNull(tppId) { "query parameter 'tppId' is required" }
        requireNotNull(role) { "query parameter 'role' is required" }
        val result = svc.checkAuthorization(
            CheckTppAuthorizationQuery(tppId, TppRole.valueOf(role.uppercase())),
        )
        return if (result.authorized) {
            Response.ok(result).build()
        } else {
            Response.status(403).entity(result).build()
        }
    }

    @POST
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun registerTpp(
        cmd: RegisterTppCommand,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        val cacheKey = idempotencyKey?.takeIf { it.isNotBlank() }?.let { registerKey(cmd.tppId, it) }
        cacheKey?.let { key ->
            idempotencyStore.get(key)?.let { cached ->
                return Response.status(cached.statusCode)
                    .entity(cached.responseBody)
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Idempotency-Replayed", "true")
                    .build()
            }
        }

        val entry = svc.registerTpp(cmd)
        cacheKey?.let { key -> idempotencyStore.save(key, 201, objectMapper.writeValueAsString(entry)) }
        return Response.status(201).entity(entry).build()
    }

    @GET
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun listTpps(
        @QueryParam("countryCode") countryCode: String?,
        @QueryParam("role") role: String?,
        @QueryParam("status") status: String?,
        @QueryParam("limit") limit: Int?,
        @QueryParam("afterCursor") afterCursor: String?,
    ): Response {
        val entries = svc.listTpps(
            ListTppsQuery(
                countryCode = countryCode,
                role = role?.let { TppRole.valueOf(it.uppercase()) },
                status = status?.let { TppStatus.valueOf(it.uppercase()) },
                limit = limit ?: 50,
                afterCursor = afterCursor,
            ),
        )
        return Response.ok(mapOf("tpps" to entries, "count" to entries.size)).build()
    }

    @GET
    @Path("/{tppId}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun getTpp(@PathParam("tppId") tppId: String): Response =
        Response.ok(svc.getTpp(GetTppQuery(tppId))).build()

    @POST
    @Path("/{tppId}/blacklist")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "tppRegistry.blacklist", resource = "#tppId")
    suspend fun blacklistTpp(
        @PathParam("tppId") tppId: String,
        body: Map<String, String>,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
    ): Response {
        val reason = body["reason"] ?: "No reason provided"
        val cacheKey = idempotencyKey?.takeIf { it.isNotBlank() }?.let { blacklistKey(tppId, it) }
        cacheKey?.let { key ->
            idempotencyStore.get(key)?.let { cached ->
                return Response.status(cached.statusCode)
                    .entity(cached.responseBody)
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Idempotency-Replayed", "true")
                    .build()
            }
        }

        val result = svc.blacklistTpp(BlacklistTppCommand(tppId, reason))
        cacheKey?.let { key -> idempotencyStore.save(key, 200, objectMapper.writeValueAsString(result)) }
        return Response.ok(result).build()
    }

    @POST
    @Path("/sync/eba")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun triggerEbaSync(@HeaderParam("Idempotency-Key") idempotencyKey: String?): Response {
        val cacheKey = idempotencyKey?.takeIf { it.isNotBlank() }?.let(::syncKey)
        cacheKey?.let { key ->
            idempotencyStore.get(key)?.let { cached ->
                return Response.status(cached.statusCode)
                    .entity(cached.responseBody)
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Idempotency-Replayed", "true")
                    .build()
            }
        }

        val result = svc.triggerEbaSync()
        cacheKey?.let { key -> idempotencyStore.save(key, 200, objectMapper.writeValueAsString(result), 300) }
        return Response.ok(result).build()
    }

    @GET
    @Path("/sync/state")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    suspend fun getSyncState(): Response = Response.ok(svc.getSyncState()).build()

    private fun registerKey(tppId: String, idempotencyKey: String) = "tpp:register:$tppId:$idempotencyKey"
    private fun blacklistKey(tppId: String, idempotencyKey: String) = "tpp:blacklist:$tppId:$idempotencyKey"
    private fun syncKey(idempotencyKey: String) = "tpp:sync:$idempotencyKey"
}
