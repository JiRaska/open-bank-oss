// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.transaction.application.port.out.FxRatePort
import com.openbank.transaction.application.port.out.FxRateView
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal

/** Typed client for the fx-service rate API; the rate endpoints require an authenticated role. */
@RegisterRestClient(configKey = "fx-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/fx")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface FxServiceRestClient {

    @GET
    @Path("/rates/{base}/{quote}")
    fun getRate(@PathParam("base") base: String, @PathParam("quote") quote: String): Uni<FxRateResponse>
}

data class FxRateResponse(
    val baseCurrency: String,
    val quoteCurrency: String,
    val bidRate: BigDecimal,
    val askRate: BigDecimal,
)

@ApplicationScoped
class FxRateClient(@RestClient private val client: FxServiceRestClient) : FxRatePort {

    override suspend fun getRate(baseCurrency: String, quoteCurrency: String): FxRateView? = try {
        client.getRate(baseCurrency, quoteCurrency).awaitSuspending().let {
            FxRateView(it.baseCurrency, it.quoteCurrency, it.bidRate, it.askRate)
        }
    } catch (e: WebApplicationException) {
        if (e.response?.status == 404) null else throw e
    }
}
