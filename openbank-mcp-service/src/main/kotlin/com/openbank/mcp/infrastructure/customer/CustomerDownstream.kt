// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.libs.web.SyntheticTaintClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * Customer-path reads (ADR-0383 D4). Same downstream routes as the staff-path clients in
 * `DownstreamClients.kt` — `GET /api/v1/balances/{accountId}` and `GET /api/v1/transactions`, both
 * covered by the committed balance/transaction pacts — but authenticated ONLY by the customer's
 * exchanged token passed per call. There is deliberately no OIDC client filter here: these
 * interfaces cannot attach mcp-service's own credential even by mistake.
 */
@RegisterRestClient(configKey = "balance-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/balances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface CustomerBalanceClient {
    @GET
    @Path("/{accountId}")
    fun getBalances(
        @HeaderParam("Authorization") authorization: String,
        @PathParam("accountId") accountId: String,
    ): JsonNode
}

@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Path("/api/v1/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface CustomerTransactionClient {
    @GET
    fun listTransactions(
        @HeaderParam("Authorization") authorization: String,
        @QueryParam("accountId") accountId: String,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
    ): JsonNode
}

/** The customer read port: every call carries an exchanged, single-audience customer token. */
interface CustomerDownstream {
    fun balances(token: String, accountId: UUID): JsonNode
    fun transactions(token: String, accountId: UUID, limit: Int): JsonNode
}

/** A downstream refusal of the customer's token (401/403): the grant may have changed underneath. */
class DownstreamRefused(val status: Int) : RuntimeException("downstream refused the customer token ($status)")

@ApplicationScoped
class RestCustomerDownstream @Inject constructor(
    @RestClient private val balances: CustomerBalanceClient,
    @RestClient private val transactions: CustomerTransactionClient,
) : CustomerDownstream {
    override fun balances(token: String, accountId: UUID): JsonNode =
        refusals { balances.getBalances(BEARER + token, accountId.toString()) }

    override fun transactions(token: String, accountId: UUID, limit: Int): JsonNode =
        refusals { transactions.listTransactions(BEARER + token, accountId.toString(), limit) }

    private inline fun refusals(block: () -> JsonNode): JsonNode = try {
        block()
    } catch (e: WebApplicationException) {
        val status = e.response?.status ?: 0
        if (status == UNAUTHORIZED || status == FORBIDDEN) throw DownstreamRefused(status) else throw e
    }

    private companion object {
        const val BEARER = "Bearer "
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
    }
}
