// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
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
import java.time.Instant

/**
 * RestClient binding to `openbank-fx-service` for reading the ČNB central-bank fixing
 * (`GET /api/v1/fx/rates/{base}/CZK?source=CNB`). Carries the service OIDC token via the reactive
 * client filter, like the other inter-service clients.
 */
@RegisterRestClient(configKey = "fx-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/fx")
@Produces(MediaType.APPLICATION_JSON)
interface FxServiceClient {

    /**
     * [asOf] pins the business day the fixing must have been in effect on (ISO `yyyy-MM-dd`).
     * fx-service accepts it only alongside `source=CNB` and answers 404 — not the newest fixing —
     * for a day none covers (#3921).
     *
     * This is an outbound rest-client INTERFACE, so the non-nullable parameters are supplied by the
     * caller and checked at compile time; the `check-nonnull-jaxrs-params.py` rule about nullable
     * `@QueryParam` is about inbound resources, and does not apply here.
     */
    @GET
    @Path("/rates/{base}/{quote}")
    fun getRate(
        @PathParam("base") base: String,
        @PathParam("quote") quote: String,
        @QueryParam("source") source: String,
        @QueryParam("asOf") asOf: String,
    ): Uni<FxRateResponse>
}

/**
 * Subset of the fx-service FxRate payload we need; a CNB fixing has bid == ask == mid.
 *
 * [validFrom] is read because the revaluation has to know **how old the fixing it is about to mark
 * positions at actually is** (#3921). fx-service has always served it — it is a column on
 * `fx_rates` and a field of that service's own `FxRateResponse` — and this DTO simply did not
 * declare it, so `@JsonIgnoreProperties(ignoreUnknown = true)` dropped it on the floor and the
 * ledger side had no time value to reason about at all. Nullable so a payload without it degrades
 * to "age unknown" rather than to a fabricated "just now".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FxRateResponse(
    val baseCurrency: String? = null,
    val quoteCurrency: String? = null,
    val bidRate: BigDecimal? = null,
    val askRate: BigDecimal? = null,
    val validFrom: Instant? = null,
)
