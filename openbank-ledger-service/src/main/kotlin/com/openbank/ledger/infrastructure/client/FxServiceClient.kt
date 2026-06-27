// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal

/**
 * RestClient binding to `openbank-fx-service` for reading the ČNB central-bank fixing
 * (`GET /api/v1/fx/rates/{base}/CZK?source=CNB`). Carries the service OIDC token via the reactive
 * client filter, like the other inter-service clients.
 */
@RegisterRestClient(configKey = "fx-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/fx")
@Produces(MediaType.APPLICATION_JSON)
interface FxServiceClient {

    @GET
    @Path("/rates/{base}/{quote}")
    fun getRate(
        @PathParam("base") base: String,
        @PathParam("quote") quote: String,
        @QueryParam("source") source: String,
    ): Uni<FxRateResponse>
}

/** Subset of the fx-service FxRate payload we need; a CNB fixing has bid == ask == mid. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FxRateResponse(
    val baseCurrency: String? = null,
    val quoteCurrency: String? = null,
    val bidRate: BigDecimal? = null,
    val askRate: BigDecimal? = null,
)
