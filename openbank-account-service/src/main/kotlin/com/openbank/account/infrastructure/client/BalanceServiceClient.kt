// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.openbank.account.application.port.out.BalanceQueryPort
import com.openbank.account.application.port.out.BalanceView
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.resteasy.reactive.client.spi.ResteasyReactiveClientRequestContext
import org.jboss.resteasy.reactive.client.spi.ResteasyReactiveClientRequestFilter
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Typed client for the balance-service REST API. The balance endpoints are
 * role-secured (@RolesAllowed SERVICE/OPERATOR/ADMIN), so the caller's bearer
 * token is propagated outbound via [BalanceAuthPropagationFilter].
 */
@RegisterRestClient(configKey = "balance-service")
@RegisterProvider(BalanceAuthPropagationFilter::class)
@Path("/api/v1/balances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface BalanceServiceRestClient {

    @GET
    @Path("/{accountId}")
    fun getBalances(@PathParam("accountId") accountId: UUID): Uni<BalancesEnvelope>

    @GET
    @Path("/{accountId}/{currency}")
    fun getBalance(@PathParam("accountId") accountId: UUID, @PathParam("currency") currency: String): Uni<BalanceDto>

    @POST
    @Path("/{accountId}/initialize")
    fun initialize(@PathParam("accountId") accountId: UUID, body: InitializeBalanceBody): Uni<BalanceDto>
}

data class BalancesEnvelope(val balances: List<BalanceDto> = emptyList())

data class BalanceDto(
    val accountId: UUID,
    val currency: String,
    val bookedAmount: BigDecimal,
    val availableAmount: BigDecimal,
    val reservedAmount: BigDecimal,
    val pendingAmount: BigDecimal,
    val arrangedOverdraftLimit: BigDecimal = BigDecimal.ZERO,
    val updatedAt: OffsetDateTime,
)

data class InitializeBalanceBody(
    val currency: String,
    val initialAmount: BigDecimal,
    val arrangedOverdraftLimit: BigDecimal,
)

@ApplicationScoped
class BalanceServiceClient(@RestClient private val client: BalanceServiceRestClient) : BalanceQueryPort {

    override suspend fun initialize(accountId: UUID, currency: String, arrangedOverdraftLimit: BigDecimal) {
        client.initialize(
            accountId,
            InitializeBalanceBody(currency, BigDecimal.ZERO, arrangedOverdraftLimit),
        ).awaitSuspending()
    }

    override suspend fun getByAccount(accountId: UUID): List<BalanceView> =
        client.getBalances(accountId).awaitSuspending().balances.map { it.toView() }

    override suspend fun getByAccountAndCurrency(accountId: UUID, currency: String): BalanceView? = try {
        client.getBalance(accountId, currency).awaitSuspending().toView()
    } catch (e: WebApplicationException) {
        if (e.response?.status == 404) null else throw e
    }

    private fun BalanceDto.toView() = BalanceView(
        accountId = accountId,
        currency = currency,
        booked = bookedAmount,
        available = availableAmount,
        reserved = reservedAmount,
        pending = pendingAmount,
        arrangedOverdraftLimit = arrangedOverdraftLimit,
        updatedAt = updatedAt.toInstant(),
    )
}

/**
 * Propagates the inbound caller's bearer token onto outbound balance-service
 * calls so its @RolesAllowed endpoints authorize the request. SecurityIdentity
 * is request-scoped and resolved per call via context propagation; outside a
 * request context (no JWT principal) no header is added and the call fails
 * closed with 401, so an unauthenticated seed attempt fails fast rather than
 * silently writing an unauthorized balance.
 */
@ApplicationScoped
class BalanceAuthPropagationFilter(private val identity: SecurityIdentity) : ResteasyReactiveClientRequestFilter {
    override fun filter(requestContext: ResteasyReactiveClientRequestContext) {
        val rawToken = (identity.principal as? JsonWebToken)?.rawToken ?: return
        requestContext.headers.putSingle(HttpHeaders.AUTHORIZATION, "Bearer $rawToken")
    }
}
