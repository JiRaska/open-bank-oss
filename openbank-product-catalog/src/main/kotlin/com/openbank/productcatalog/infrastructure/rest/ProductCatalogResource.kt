// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.productcatalog.application.ProductCatalogService
import com.openbank.productcatalog.application.ProductRequest
import com.openbank.productcatalog.infrastructure.security.CatalogRoles
import io.quarkus.security.identity.SecurityIdentity
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
import org.eclipse.microprofile.config.inject.ConfigProperty

@Suppress("TooManyFunctions")
@ApplicationScoped
@Path("/api/v1/products")
@Produces(MediaType.APPLICATION_JSON)
class ProductCatalogResource(
    private val service: ProductCatalogService,
    private val identity: SecurityIdentity,
    @ConfigProperty(name = "openbank.catalog.bank-v1-compatibility-enabled", defaultValue = "true")
    private val bankCompatibilityEnabled: Boolean,
) {

    @GET
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.list")
    suspend fun list(
        @QueryParam("type") type: String?,
        @QueryParam("status") status: String?,
        @QueryParam("currency") currency: String?,
    ): Response {
        requireBankCompatibility()
        var results = service.findAll()
        if (!type.isNullOrBlank()) results = results.filter { it.type == type }
        if (!status.isNullOrBlank()) results = results.filter { it.status.name == status }
        if (!currency.isNullOrBlank()) results = results.filter { it.currency == currency }
        return Response.ok(results).build()
    }

    @GET
    @Path("/{id}")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read", resource = "#id")
    suspend fun getById(@PathParam("id") id: String): Response {
        requireBankCompatibility()
        return service.findById(id)?.let(::okWithRevision)
            ?: Response.status(404).entity(mapOf("error" to "Product $id not found")).build()
    }

    // ADR-0105: resolve a product by its semantic code (e.g. SAVINGS_STANDARD) or prod-NNN legacy alias.
    @GET
    @Path("/by-code/{code}")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read", resource = "#code")
    suspend fun getByCode(@PathParam("code") code: String): Response {
        requireBankCompatibility()
        return service.findByCode(code)?.let(::okWithRevision)
            ?: Response.status(404).entity(mapOf("error" to "Product with code '$code' not found")).build()
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.AUTHOR)
    @Authorize(action = "catalog.create")
    suspend fun create(req: ProductRequest): Response = try {
        requireBankCompatibility()
        val product = service.create(req, actor())
        Response.status(201).tag(EntityTag(product.revision.toString())).entity(product).build()
    } catch (e: IllegalArgumentException) {
        legacyConflict(e)
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.AUTHOR)
    @Authorize(action = "catalog.update", resource = "#id")
    suspend fun update(
        @PathParam("id") id: String,
        @HeaderParam("If-Match") ifMatch: String?,
        req: ProductRequest,
    ): Response {
        requireBankCompatibility()
        val expected = expectedRevision(ifMatch, req.revision)
        return try {
            okWithRevision(service.update(id, req.copy(revision = expected), actor()))
        } catch (e: IllegalArgumentException) {
            legacyConflict(e)
        }
    }

    @POST
    @Path("/{id}/activate")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.AUTHOR)
    @Authorize(action = "catalog.update", resource = "#id")
    suspend fun activate(@PathParam("id") id: String, @HeaderParam("If-Match") ifMatch: String?): Response {
        requireBankCompatibility()
        return okWithRevision(service.activate(id, expectedRevision(ifMatch, null), actor()))
    }

    @POST
    @Path("/{id}/deactivate")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN, CatalogRoles.AUTHOR)
    @Authorize(action = "catalog.update", resource = "#id")
    suspend fun deactivate(@PathParam("id") id: String, @HeaderParam("If-Match") ifMatch: String?): Response {
        requireBankCompatibility()
        return okWithRevision(service.deactivate(id, expectedRevision(ifMatch, null), actor()))
    }

    @GET
    @Path("/{id}/fees")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.read", resource = "#id")
    suspend fun getFees(@PathParam("id") id: String): Response {
        requireBankCompatibility()
        return service.findById(id)
            ?.let { Response.ok(it.fees).build() }
            ?: Response.status(404).entity(mapOf("error" to "Product $id not found")).build()
    }

    private fun okWithRevision(product: com.openbank.productcatalog.domain.Product): Response =
        Response.ok(product).tag(EntityTag(product.revision.toString())).build()

    private fun legacyConflict(exception: IllegalArgumentException): Response =
        Response.status(Response.Status.CONFLICT)
            .entity(mapOf("error" to exception.message))
            .build()

    private fun actor(): String = identity.principal.name

    private fun requireBankCompatibility() {
        if (!bankCompatibilityEnabled) {
            throw jakarta.ws.rs.NotFoundException("the banking compatibility API is disabled")
        }
    }

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
