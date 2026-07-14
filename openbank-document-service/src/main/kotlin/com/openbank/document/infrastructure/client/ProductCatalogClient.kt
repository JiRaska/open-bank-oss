// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
 * RestClient binding to `openbank-product-catalog`'s product read endpoint — narrow, mirroring
 * `openbank-account-service`'s own `ProductCatalogClient`. The only field this service actually
 * needs is `termsAndConditions[].documentTemplateCode` (ADR-0162 D1), the product's onboarding
 * document reference.
 */
@RegisterRestClient(configKey = "product-catalog-api")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Produces(MediaType.APPLICATION_JSON)
interface ProductCatalogClient {
    @GET
    @Path("/api/v1/products/{id}")
    fun getById(@PathParam("id") id: String): Uni<ProductClientResponse>
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProductClientResponse(
    val id: String,
    val code: String,
    val termsAndConditions: List<TermsAndConditionsClientResponse> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TermsAndConditionsClientResponse(val documentTemplateCode: String? = null)
