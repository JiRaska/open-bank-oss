// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.transaction.application.port.out.BalanceCoverPort
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.util.UUID

/**
 * Typed client for the balance-service cover endpoints. The hold legs are money-relevant and gated by
 * `@RolesAllowed(SERVICE, OPERATOR, ADMIN)` on balance-service, so the client attaches an M2M bearer
 * via [OidcClientRequestReactiveFilter] (oidc-client `openbank-services`, which carries ROLE_OPERATOR).
 * Without it these calls are rejected 401. Booked debit/credit is no longer driven from here — the
 * ledger projection owns the booked balance (ADR-0039 Phase D-2).
 */
@RegisterRestClient(configKey = "balance-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/balances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface BalanceCoverRestClient {

    @POST
    @Path("/{accountId}/holds")
    fun placeHold(@PathParam("accountId") accountId: UUID, body: PlaceHoldBody): Uni<HoldResponse>

    @DELETE
    @Path("/holds/{holdId}")
    fun releaseHold(@PathParam("holdId") holdId: UUID): Uni<HoldResponse>
}

data class PlaceHoldBody(
    val amount: BigDecimal,
    val currency: String,
    val reason: String,
    val referenceId: String,
    val ttlSeconds: Long,
)

data class HoldResponse(val id: UUID)

@ApplicationScoped
class BalanceCoverClient(@RestClient private val client: BalanceCoverRestClient) : BalanceCoverPort {

    override suspend fun placeHold(
        accountId: UUID,
        amount: BigDecimal,
        currency: String,
        reason: String,
        referenceId: String,
        ttlSeconds: Long,
    ): UUID = client.placeHold(accountId, PlaceHoldBody(amount, currency, reason, referenceId, ttlSeconds))
        .awaitSuspending().id

    override suspend fun releaseHold(holdId: UUID) {
        client.releaseHold(holdId).awaitSuspending()
    }
}
