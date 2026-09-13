// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.read

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.libs.web.SyntheticTaintClientFilter
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
import java.util.UUID

// Downstream banking-service reads (ADR-0195 step 2), mirroring agent-service's ServiceClients
// pattern exactly: MP Rest Client interfaces returning raw JsonNode (the tools pass it straight
// through), M2M-authenticated via OidcClientRequestReactiveFilter (client-credentials, same
// `openbank-services`-shaped principal the downstream @Authorize checks already grant reads to).
// Each downstream endpoint + its @Authorize action is cited in the KDoc it backs (RealAccountReadPort).

@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface AccountServiceClient {
    /** `GET /api/v1/accounts/iban/{iban}` — `@Authorize(account.read, resource=#iban)`. */
    @GET
    @Path("/iban/{iban}")
    fun getAccountByIban(@PathParam("iban") iban: String): JsonNode

    /**
     * `GET /api/v1/accounts/{accountId}` — `@Authorize(account.read, resource=#accountId)`. Used by
     * [RealPaymentConfirmationReadPort] to recover the debtor's IBAN from a domestic payment, which
     * (unlike a SEPA payment) carries only the internal `debtorAccountId`, never an IBAN.
     */
    @GET
    @Path("/{accountId}")
    fun getAccountById(@PathParam("accountId") accountId: UUID): JsonNode
}

@RegisterRestClient(configKey = "balance-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
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
@RegisterProvider(SyntheticTaintClientFilter::class)
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

@RegisterRestClient(configKey = "statement-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/statements")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface StatementServiceClient {
    /** `GET /api/v1/statements/{accountId}` — `@Authorize(statement.list, resource=#accountId)`. */
    @GET
    @Path("/{accountId}")
    fun listStatements(@PathParam("accountId") accountId: UUID): JsonNode

    /**
     * `GET /api/v1/statements/{accountId}/{currency}/{legalSequence}/summary` —
     * `@Authorize(statement.read, resource=#accountId)`. The JSON twin of the existing
     * camt.053/MT940/PDF `render` endpoint, added by this PR (statement-service `StatementResource`)
     * because none of those three formats is something a calling model can reason over directly.
     */
    @GET
    @Path("/{accountId}/{currency}/{legalSequence}/summary")
    fun statementSummary(
        @PathParam("accountId") accountId: UUID,
        @PathParam("currency") currency: String,
        @PathParam("legalSequence") legalSequence: Long,
    ): JsonNode
}

@RegisterRestClient(configKey = "sepa-payment-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/sepa-payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface SepaPaymentServiceClient {
    /** `GET /api/v1/sepa-payments/{paymentId}` — `@Authorize(sepaPayment.read, resource=#paymentId)`. */
    @GET
    @Path("/{paymentId}")
    fun getPayment(@PathParam("paymentId") paymentId: UUID): JsonNode
}

@RegisterRestClient(configKey = "domestic-payment-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/domestic-payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface DomesticPaymentServiceClient {
    /**
     * `GET /api/v1/domestic-payments/{paymentId}` —
     * `@Authorize(domestic-payment.read, resource=#paymentId)`.
     */
    @GET
    @Path("/{paymentId}")
    fun getPayment(@PathParam("paymentId") paymentId: UUID): JsonNode
}
