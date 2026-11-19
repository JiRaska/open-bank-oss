// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.transaction.application.port.out.BalanceCoverPort
import com.openbank.transaction.application.port.out.InsufficientPaymentCover
import io.quarkus.oidc.client.filter.OidcClientFilter
import io.smallrye.common.annotation.Blocking
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.util.UUID

/**
 * Typed client for the balance-service cover endpoints. The hold legs are money-relevant and gated by
 * `@RolesAllowed(API, OPERATOR, ADMIN)` on balance-service, so the client attaches an M2M bearer
 * via the named oidc-client `m2m` (Keycloak client `openbank-transaction`, ROLE_API only; balance's
 * OPA grants it balance.hold / balance.holdRelease by identity, #10486).
 * Without it these calls are rejected 401. Booked debit/credit is no longer driven from here — the
 * ledger projection owns the booked balance (ADR-0039 Phase D-2).
 */
@RegisterRestClient(configKey = "balance-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(InsufficientPaymentCoverMapper::class)
// #10486: this client's bearer is minted by the NAMED oidc-client `m2m` - Keycloak client
// `openbank-transaction` (ROLE_API only) - never the shared `openbank-services` default client.
@OidcClientFilter("m2m")
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
    val ttlSeconds: Long?,
)

data class HoldResponse(val id: UUID)

@Blocking
class InsufficientPaymentCoverMapper : ResponseExceptionMapper<InsufficientPaymentCover> {
    override fun handles(status: Int, headers: jakarta.ws.rs.core.MultivaluedMap<String, Any>): Boolean =
        status == UNPROCESSABLE_ENTITY

    override fun toThrowable(response: Response): InsufficientPaymentCover? {
        val code = runCatching {
            response.bufferEntity()
            JsonObject(response.readEntity(String::class.java)).getString("error")
        }.getOrNull()
        return if (code == "INSUFFICIENT_FUNDS") InsufficientPaymentCover() else null
    }

    private companion object {
        const val UNPROCESSABLE_ENTITY = 422
    }
}

@ApplicationScoped
class BalanceCoverClient(@RestClient private val client: BalanceCoverRestClient) : BalanceCoverPort {

    override suspend fun placeHold(
        accountId: UUID,
        amount: BigDecimal,
        currency: String,
        reason: String,
        referenceId: String,
        ttlSeconds: Long?,
    ): UUID = client.placeHold(accountId, PlaceHoldBody(amount, currency, reason, referenceId, ttlSeconds))
        .awaitSuspending().id

    override suspend fun releaseHold(holdId: UUID) {
        client.releaseHold(holdId).awaitSuspending()
    }
}
