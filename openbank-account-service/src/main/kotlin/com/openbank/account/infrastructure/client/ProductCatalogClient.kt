// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

// product-catalog now authenticates its callers (issue #401, #743): reads require a valid
// token (@Authenticated). This comment previously said "unauthenticated, public reference
// data" — true when this file was written, stale since #743 merged 2026-07-11. Propagate an
// openbank-services bearer like SanctionsServiceClient, or every account-open product lookup
// gets a 401 -> ProductLookupResult.Unavailable -> validation silently skipped (fail-open by
// design, so this was never a crash, just a silent bypass of ADR-0158's whole point).
@RegisterRestClient(configKey = "product-catalog-api")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterProvider(ProductCatalogHostHeaderFilter::class)
@Path("/api/v1/products")
@Produces(MediaType.APPLICATION_JSON)
interface ProductCatalogClient {
    @GET
    @Path("/{id}")
    fun getById(@PathParam("id") id: String): Uni<ProductCatalogResponse>
}

/** The subset of product-catalog's `Product` this service actually consumes (issue #668). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ProductCatalogResponse(val id: String, val code: String, val status: String, val currency: String)
