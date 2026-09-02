// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.util.UUID

/**
 * RestClient binding to `openbank-fraud-service` synchronous scorer (`POST /api/v1/fraud/score`,
 * ADR-0084 §1). Carries the service OIDC token via the reactive client filter, like the other
 * inter-service clients.
 */
@RegisterRestClient(configKey = "fraud-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/fraud")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface FraudScoreClient {

    @POST
    @Path("/score")
    fun score(request: FraudScoreClientRequest): Uni<FraudScoreClientResponse>
}

/** Mirror of fraud-service `ScoreFraudRequest`. */
data class FraudScoreClientRequest(
    val amount: BigDecimal,
    val currency: String,
    val rail: String,
    val accountId: UUID?,
    val counterpartyId: UUID?,
)

/** Mirror of fraud-service `ScoreFraudResponse` (tolerate unknown fields as the contract evolves). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FraudScoreClientResponse(
    val verdict: String,
    val score: Int = 0,
    val reasons: List<String> = emptyList(),
    val ruleVersion: String = "unknown",
)
