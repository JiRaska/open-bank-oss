// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

/** Company drafts: the edge derives both company and human; the body cannot select either. */
@Path("/customer/v1/business/payment-batches")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
@Blocking
class BusinessPaymentBatchResource(
    private val upstream: UpstreamClient,
    private val actingFor: ActingForResolver,
    private val merged: PartyMergeResolver,
    @ConfigProperty(name = "openbank.edge.business-payment-batches.enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    @Inject
    lateinit var jwt: JsonWebToken

    @ConfigProperty(name = "openbank.edge.domestic-payment-service-url")
    lateinit var domesticUrl: String

    @POST
    fun create(
        @HeaderParam("X-Acting-For") header: String?,
        @HeaderParam("Idempotency-Key") key: String?,
        body: String?,
    ): Response {
        requireEnabled()
        val (human, entity) = parties(header)
        if (body.isNullOrBlank() || key.isNullOrBlank() || key.length > MAX_KEY_CHARS) {
            return Response.status(Response.Status.BAD_REQUEST).build()
        }
        return upstream.post(url(), entity.toString(), body, key, mapOf("X-Actor-Party-Id" to human.toString()))
    }

    @GET
    fun list(
        @HeaderParam("X-Acting-For") header: String?,
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
    ): Response {
        requireEnabled()
        val (_, entity) = parties(header)
        val p = page ?: 0
        val s = size ?: PAGE_SIZE
        if (p !in 0..MAX_LIST_PAGE || s !in 1..PAGE_SIZE) return Response.status(Response.Status.BAD_REQUEST).build()
        return upstream.get("${url()}?page=$p&size=$s", entity.toString())
    }

    @GET
    @Path("/{id}")
    fun detail(
        @HeaderParam("X-Acting-For") header: String?,
        @PathParam("id") id: UUID,
        @QueryParam("page") page: Int?,
    ): Response {
        requireEnabled()
        val (_, entity) = parties(header)
        val p = page ?: 0
        if (p !in 0..MAX_ITEM_PAGE) return Response.status(Response.Status.BAD_REQUEST).build()
        return upstream.get("${url()}/$id?page=$p", entity.toString())
    }

    @PUT
    @Path("/{id}/items")
    fun replace(
        @HeaderParam("X-Acting-For") header: String?,
        @PathParam("id") id: UUID,
        @HeaderParam("If-Match") revision: String?,
        body: String?,
    ): Response {
        requireEnabled()
        val (human, entity) = parties(header)
        if (revision?.trim('"')?.toLongOrNull() == null) return Response.status(HTTP_PRECONDITION_REQUIRED).build()
        if (body.isNullOrBlank()) return Response.status(Response.Status.BAD_REQUEST).build()
        return upstream.put(
            "${url()}/$id/items",
            entity.toString(),
            body,
            null,
            mapOf("If-Match" to revision, "X-Actor-Party-Id" to human.toString()),
        )
    }

    private fun url() = "$domesticUrl/api/v1/business-payment-batches"

    private fun requireEnabled() {
        if (!enabled) throw NotFoundException()
    }

    // Every failure is explicit so an absent or revoked company mandate cannot fall back to the human party.
    @Suppress("ThrowsCount")
    private fun parties(header: String?): Pair<UUID, UUID> {
        val raw = CustomerEdgeResource.resolvePartyIdClaim(jwt.getClaim<String>("party_id"), jwt.subject)
            ?: throw ForbiddenException("human party is required")
        val human = merged.resolve(
            runCatching { UUID.fromString(raw) }.getOrNull()
                ?: throw ForbiddenException("human party is invalid"),
        )
        if (header.isNullOrBlank()) throw ForbiddenException("X-Acting-For is required")
        val entity = actingFor.resolve(human, header)
        if (entity == human) throw ForbiddenException("company mandate is required")
        val company = actingFor.profilesOf(human).any {
            it["partyId"] == entity && it["partyType"] == "COMPANY"
        }
        if (!company) throw ForbiddenException("active company mandate is required")
        return human to entity
    }

    private companion object {
        const val MAX_KEY_CHARS = 128
        const val PAGE_SIZE = 20
        const val MAX_LIST_PAGE = 1000
        const val MAX_ITEM_PAGE = 4
        const val HTTP_PRECONDITION_REQUIRED = 428
    }
}
