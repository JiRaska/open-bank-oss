// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.read

import com.fasterxml.jackson.databind.JsonNode
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

// Downstream banking-service reads (ADR-0195 step 2), mirroring agent-service's ServiceClients
// pattern exactly: MP Rest Client interfaces returning raw JsonNode (the tools pass it straight
// through), M2M-authenticated via OidcClientRequestReactiveFilter (client-credentials, same
// `openbank-services`-shaped principal the downstream @Authorize checks already grant reads to).
// Each downstream endpoint + its @Authorize action is cited in the KDoc it backs (RealAccountReadPort).

@RegisterRestClient(configKey = "account-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface AccountServiceClient {
    /** `GET /api/v1/accounts/iban/{iban}` — `@Authorize(account.read, resource=#iban)`. */
    @GET
    @Path("/iban/{iban}")
    fun getAccountByIban(@PathParam("iban") iban: String): JsonNode
}

@RegisterRestClient(configKey = "balance-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/balances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface BalanceServiceClient {
    /** `GET /api/v1/balances/{accountId}`. */
    @GET
    @Path("/{accountId}")
    fun getBalances(@PathParam("accountId") accountId: String): JsonNode
}

@RegisterRestClient(configKey = "transaction-service")
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface TransactionServiceClient {
    /** `GET /api/v1/transactions?accountId=&limit=&cursor=`. */
    @GET
    fun listTransactions(
        @QueryParam("accountId") accountId: String,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): JsonNode
}
