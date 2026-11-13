// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

// product-catalog now authenticates its callers (issue #401, #743): reads require a valid
// token (@Authenticated). This comment previously said "unauthenticated, public reference
// data" — true when this file was written, stale since #743 merged 2026-07-11. Use the account-owned
// named m2m bearer with catalog:read, or every account-open product lookup
// gets a 401 -> ProductLookupResult.Unavailable -> validation silently skipped (fail-open by
// design, so this was never a crash, just a silent bypass of ADR-0158's whole point).
@RegisterRestClient(configKey = "product-catalog-api")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
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

/**
 * product-catalog v2 (ADR-0257 / ADR-0341): the PUBLISHED revision of a v1 product, which an account
 * pins at opening. Same config key, filters and bearer as [ProductCatalogClient]; only GETs.
 */
@RegisterRestClient(configKey = "product-catalog-api")
@RegisterProvider(SyntheticTaintClientFilter::class)
@OidcClientFilter("m2m")
@RegisterProvider(ProductCatalogHostHeaderFilter::class)
@Path("/api/v2")
@Produces(MediaType.APPLICATION_JSON)
interface ProductCatalogRevisionClient {
    /** The revision effective at [effectiveAt]; 404 when the product is unknown or none is published then. */
    @GET
    @Path("/legacy-products/{productId}/published-revision")
    fun getPublishedRevisionForLegacyProduct(
        @PathParam("productId") productId: String,
        @QueryParam("effectiveAt") effectiveAt: String,
    ): Uni<CatalogRevisionResponse>
}

/** The subset of a catalog `ProductRevision` an account pins. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogRevisionResponse(
    val id: String,
    val offeringId: String,
    val number: Long,
    val state: String,
    val contentHash: String? = null,
    val irSignature: CatalogIrSignature? = null,
    val content: CatalogRevisionContent = CatalogRevisionContent(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogIrSignature(val revisionHash: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogRevisionContent(val behaviour: CatalogBehaviour? = null)

/** Only `irVersion` of the product IR document (ADR-0352 §1); the IR itself is read by consumers by hash. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogBehaviour(val irVersion: Int? = null)
