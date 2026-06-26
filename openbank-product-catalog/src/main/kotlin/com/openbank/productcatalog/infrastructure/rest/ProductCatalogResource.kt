// SPDX-License-Identifier: MPL-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.\n// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.\n
package com.openbank.productcatalog.infrastructure.rest

import com.openbank.productcatalog.application.ProductCatalogService
import com.openbank.productcatalog.application.ProductRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@ApplicationScoped
@Path("/api/v1/products")
@Produces(MediaType.APPLICATION_JSON)
class ProductCatalogResource(private val service: ProductCatalogService) {

    @GET
    fun list(
        @QueryParam("type") type: String?,
        @QueryParam("status") status: String?,
        @QueryParam("currency") currency: String?
    ): Response {
        var results = service.findAll()
        if (!type.isNullOrBlank()) results = results.filter { it.type == type }
        if (!status.isNullOrBlank()) results = results.filter { it.status.name == status }
        if (!currency.isNullOrBlank()) results = results.filter { it.currency == currency }
        return Response.ok(results).build()
    }

    @GET
    @Path("/{id}")
    fun getById(@PathParam("id") id: String): Response =
        service.findById(id)?.let { Response.ok(it).build() }
            ?: Response.status(404).entity(mapOf("error" to "Product $id not found")).build()

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    fun create(req: ProductRequest): Response {
        return try {
            val product = service.create(req)
            Response.status(201).entity(product).build()
        } catch (e: IllegalArgumentException) {
            Response.status(409).entity(mapOf("error" to e.message)).build()
        }
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    fun update(@PathParam("id") id: String, req: ProductRequest): Response {
        return try {
            Response.ok(service.update(id, req)).build()
        } catch (e: NoSuchElementException) {
            Response.status(404).entity(mapOf("error" to e.message)).build()
        }
    }

    @POST
    @Path("/{id}/activate")
    fun activate(@PathParam("id") id: String): Response {
        return try {
            Response.ok(service.activate(id)).build()
        } catch (e: NoSuchElementException) {
            Response.status(404).entity(mapOf("error" to e.message)).build()
        }
    }

    @POST
    @Path("/{id}/deactivate")
    fun deactivate(@PathParam("id") id: String): Response {
        return try {
            Response.ok(service.deactivate(id)).build()
        } catch (e: NoSuchElementException) {
            Response.status(404).entity(mapOf("error" to e.message)).build()
        }
    }

    @GET
    @Path("/{id}/fees")
    fun getFees(@PathParam("id") id: String): Response =
        service.findById(id)?.let { Response.ok(it.fees).build() }
            ?: Response.status(404).entity(mapOf("error" to "Product $id not found")).build()
}