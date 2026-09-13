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

/**
 * What balance-service actually puts on the wire for `POST /balances/{id}/{credit,debit}`, narrowed
 * to what this service reads.
 *
 * It used to bind `availableBalance` and `currentBalance` as non-nullable [BigDecimal]. Those two
 * names have never been on the wire: `BalanceResource.credit/debit` returns the domain `Balance`
 * straight to Jackson, so the payload carries that class's property names — `bookedAmount`,
 * `availableAmount`, `reservedAmount`, `pendingAmount`. A missing non-nullable Kotlin property is a
 * deserialisation failure, so every real credit and debit failed on the way back, AFTER the money
 * had moved (#8673).
 *
 * Nothing could see it. The only test coverage constructed `BalanceResponse` in Kotlin or stubbed
 * WireMock with a body copied from this class, so both sides of the assertion came from the same
 * wrong shape and agreed with each other — the consumer-pact trap in the repo guide, one layer
 * down.
 *
 * The fields are not renamed, they are REMOVED: no code path in settlement-service reads a balance
 * off this response, and a field that is never read is a way to fail on someone else's schema for
 * nothing. Jackson ignores the unknown remainder, so this binds only the two values that identify
 * the movement, and it cannot break again when balance-service adds a field.
 */
data class BalanceResponse(val accountId: UUID, val currency: String)
