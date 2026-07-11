// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

// product-catalog's product-read endpoints are unauthenticated (public reference data), so no
// OidcClientRequestReactiveFilter provider here, unlike SanctionsServiceClient.
@RegisterRestClient(configKey = "product-catalog-api")
@Path("/api/v1/products")
@Produces(MediaType.APPLICATION_JSON)
interface ProductCatalogClient {
    @GET
    @Path("/{id}")
    fun getById(@PathParam("id") id: String): Uni<ProductCatalogResponse>
}

/** The subset of product-catalog's `Product` this service actually consumes (issue #668). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ProductCatalogResponse(val id: String, val code: String, val status: String)
