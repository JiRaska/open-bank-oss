// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.util.UUID

@RegisterRestClient(configKey = "balance-api")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/balances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface BalanceRestClient {
    @POST
    @Path("/{accountId}/debit")
    fun debit(@PathParam("accountId") accountId: UUID, body: MoneyMovementRequest): Uni<BalanceResponse>

    @POST
    @Path("/{accountId}/credit")
    fun credit(@PathParam("accountId") accountId: UUID, body: MoneyMovementRequest): Uni<BalanceResponse>
}

data class MoneyMovementRequest(
    val amount: BigDecimal,
    val currency: String,
    val referenceId: String,
    val description: String,
)

data class BalanceResponse(
    val accountId: UUID,
    val currency: String,
    val availableBalance: BigDecimal,
    val currentBalance: BigDecimal,
)
