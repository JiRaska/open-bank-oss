// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.productcatalog.application.ProductCatalogService
import com.openbank.productcatalog.application.ProductRequest
import io.quarkus.security.Authenticated
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Suppress("TooManyFunctions")
@ApplicationScoped
@Path("/api/v1/products")
@Produces(MediaType.APPLICATION_JSON)
class ProductCatalogResource(private val service: ProductCatalogService) {

    @GET
    @Authenticated
    @Authorize(action = "catalog.list")
    suspend fun list(
        @QueryParam("type") type: String?,
        @QueryParam("status") status: String?,
        @QueryParam("currency") currency: String?,
    ): Response {
        var results = service.findAll()
        if (!type.isNullOrBlank()) results = results.filter { it.type == type }
        if (!status.isNullOrBlank()) results = results.filter { it.status.name == status }
        if (!currency.isNullOrBlank()) results = results.filter { it.currency == currency }
        return Response.ok(results).build()
    }

    @GET
    @Path("/{id}")
    @Authenticated
    @Authorize(action = "catalog.read", resource = "#id")
    suspend fun getById(@PathParam("id") id: String): Response = service.findById(id)?.let(::okWithRevision)
        ?: Response.status(404).entity(mapOf("error" to "Product $id not found")).build()

    // ADR-0105: resolve a product by its semantic code (e.g. SAVINGS_STANDARD) or prod-NNN legacy alias.
    @GET
    @Path("/by-code/{code}")
    @Authenticated
    @Authorize(action = "catalog.read", resource = "#code")
    suspend fun getByCode(@PathParam("code") code: String): Response = service.findByCode(code)?.let(::okWithRevision)
        ?: Response.status(404).entity(mapOf("error" to "Product with code '$code' not found")).build()

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "catalog.create")
    suspend fun create(req: ProductRequest): Response = try {
        val product = service.create(req)
        Response.status(201).tag(EntityTag(product.revision.toString())).entity(product).build()
    } catch (e: IllegalArgumentException) {
        legacyConflict(e)
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "catalog.update", resource = "#id")
    suspend fun update(
        @PathParam("id") id: String,
        @HeaderParam("If-Match") ifMatch: String?,
        req: ProductRequest,
    ): Response {
        val expected = expectedRevision(ifMatch, req.revision)
        return try {
            okWithRevision(service.update(id, req.copy(revision = expected)))
        } catch (e: IllegalArgumentException) {
            legacyConflict(e)
        }
    }

    @POST
    @Path("/{id}/activate")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "catalog.update", resource = "#id")
    suspend fun activate(@PathParam("id") id: String, @HeaderParam("If-Match") ifMatch: String?): Response =
        okWithRevision(service.activate(id, expectedRevision(ifMatch, null)))

    @POST
    @Path("/{id}/deactivate")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "catalog.update", resource = "#id")
    suspend fun deactivate(@PathParam("id") id: String, @HeaderParam("If-Match") ifMatch: String?): Response =
        okWithRevision(service.deactivate(id, expectedRevision(ifMatch, null)))

    @GET
    @Path("/{id}/fees")
    @Authenticated
    @Authorize(action = "catalog.read", resource = "#id")
    suspend fun getFees(@PathParam("id") id: String): Response = service.findById(id)
        ?.let { Response.ok(it.fees).build() }
        ?: Response.status(404).entity(mapOf("error" to "Product $id not found")).build()

    private fun okWithRevision(product: com.openbank.productcatalog.domain.Product): Response =
        Response.ok(product).tag(EntityTag(product.revision.toString())).build()

    private fun legacyConflict(exception: IllegalArgumentException): Response =
        Response.status(Response.Status.CONFLICT)
            .entity(mapOf("error" to exception.message))
            .build()

    private fun expectedRevision(ifMatch: String?, bodyRevision: Long?): Long? {
        val headerRevision = ifMatch?.let { STRONG_NUMERIC_ETAG.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
        require(ifMatch == null || headerRevision != null) { "If-Match must contain a numeric product ETag" }
        require(headerRevision == null || bodyRevision == null || headerRevision == bodyRevision) {
            "If-Match and body revision must agree"
        }
        return headerRevision ?: bodyRevision
    }

    private companion object {
        val STRONG_NUMERIC_ETAG = Regex("\\\"([0-9]+)\\\"")
    }
}
