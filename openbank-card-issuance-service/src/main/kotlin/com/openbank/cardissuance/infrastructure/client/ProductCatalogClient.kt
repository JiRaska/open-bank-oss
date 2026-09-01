// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.client

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

/**
 * RestClient binding to `openbank-product-catalog` — narrow, mirroring account-service's and
 * document-service's own `ProductCatalogClient`. Card issuance resolves by **code** (the issue
 * command carries `productCode`, not the catalog UUID), so it binds `/by-code/{code}` rather than
 * `/{id}`.
 *
 * product-catalog authenticates its readers (#743), hence the `openbank-services` M2M bearer from
 * [OidcClientRequestReactiveFilter]; without it every lookup 401s and silently degrades to the
 * permissive fallback.
 */
@RegisterRestClient(configKey = "product-catalog-api")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterProvider(ProductCatalogHostHeaderFilter::class)
@Produces(MediaType.APPLICATION_JSON)
interface ProductCatalogClient {
    @GET
    @Path("/api/v1/products/by-code/{code}")
    fun getByCode(@PathParam("code") code: String): Uni<ProductCardConfigResponse>
}

/** The subset of product-catalog's `Product` this service consumes: its `cardConfig`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ProductCardConfigResponse(val id: String, val code: String, val cardConfig: CardConfigResponse? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CardConfigResponse(
    val enabled: Boolean = false,
    val maxCards: Int = 1,
    val networks: List<String> = emptyList(),
    val tiers: List<String> = emptyList(),
    val virtualCardAllowed: Boolean = true,
    val contactlessEnabled: Boolean = true,
    val monthlyFeePerCard: Double = 0.0,
    val cardCurrency: String? = null,
)
