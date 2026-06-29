// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal

/** A product's fee definition as returned by product-catalog `GET /api/v1/products/{id}/fees`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FeeDto(
    val id: String,
    val name: String,
    val type: String,
    val amount: BigDecimal,
    val currency: String,
    val waivable: Boolean = false,
    val waiveCondition: String? = null,
)

/** The subset of account-service `GET /api/v1/accounts/{id}` the billing read path needs. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountDto(val id: String, val productId: String, val currencyCode: String, val status: String? = null)

/** The subset of balance-service `GET /api/v1/balances/{accountId}/{currency}` we need. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BalanceDto(val accountId: String, val currency: String, val currentBalance: BigDecimal)

/** Product-catalog fee definitions (public read; the catalog is the fee source-of-truth). */
@RegisterRestClient(configKey = "product-catalog")
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface ProductCatalogRestClient {
    @GET
    @Path("/products/{id}/fees")
    fun getProductFees(@PathParam("id") productId: String): Uni<List<FeeDto>>
}

/** Account read — carries account PII, so the call propagates an OIDC service token. */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface AccountRestClient {
    @GET
    @Path("/accounts/{id}")
    fun getAccount(@PathParam("id") accountId: String): Uni<AccountDto>
}

/** Balance read — balance is PII, so the call propagates an OIDC service token. */
@RegisterRestClient(configKey = "balance-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface BalanceRestClient {
    @GET
    @Path("/balances/{accountId}/{currency}")
    fun getBalance(@PathParam("accountId") accountId: String, @PathParam("currency") currency: String): Uni<BalanceDto>
}
