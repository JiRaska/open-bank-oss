// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

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

data class FraudScoreClientRequest(
    val amount: BigDecimal,
    val currency: String,
    val rail: String,
    val accountId: UUID?,
    val counterpartyId: UUID?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FraudScoreClientResponse(
    val verdict: String,
    val score: Int = 0,
    val reasons: List<String> = emptyList(),
    val ruleVersion: String = "unknown",
)
