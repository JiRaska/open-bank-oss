// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.jboss.resteasy.reactive.client.spi.ResteasyReactiveClientRequestContext
import org.jboss.resteasy.reactive.client.spi.ResteasyReactiveClientRequestFilter
import java.math.BigDecimal
import java.util.UUID

/**
 * Typed client for the **customer edge** (ADR-0065) — the customer-facing BFF, NOT the internal
 * domain services. The edge is `@RolesAllowed("ROLE_CUSTOMER")` and enforces ownership (scopes every
 * upstream read by the caller's partyId), so the assistant acting AS the customer (propagated bearer,
 * ADR-0089 D5) can only ever reach that customer's own data. Calling balance-/transaction-service
 * directly would 403 — those expect SERVICE/OPERATOR roles a customer token does not carry.
 */
@RegisterRestClient(configKey = "customer-edge")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(CopilotAuthPropagationFilter::class)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface CustomerEdgeRestClient {

    @GET
    @Path("/customer/v1/accounts")
    fun listAccounts(): Uni<AccountsPage>

    @GET
    @Path("/customer/v1/balances/{accountId}")
    fun getBalances(@PathParam("accountId") accountId: UUID): Uni<List<BalanceDto>>

    @GET
    @Path("/customer/v1/transactions")
    fun listTransactions(
        @QueryParam("accountId") accountId: UUID,
        @QueryParam("limit") @DefaultValue("10") limit: Int,
    ): Uni<TransactionPage>

    @GET
    @Path("/customer/v1/fx/rates")
    fun getFxRates(): Uni<List<FxRateDto>>

    @GET
    @Path("/customer/v1/cards")
    fun listCards(): Uni<List<CardDto>>

    @GET
    @Path("/customer/v1/statements/{accountId}")
    fun listStatements(@PathParam("accountId") accountId: UUID): Uni<List<StatementDto>>

    /**
     * ADR-0269 rule 4: the customer's indicative price for an amount and term. The edge and
     * lending-service own every input that is not amount/term, so this cannot be used to price a
     * loan on the model's terms.
     */
    @POST
    @Path("/customer/v1/credit/quotes")
    fun quoteCredit(request: CreditQuoteRequestDto): Uni<CreditQuoteDto>

    /** ADR-0269 rule 3: the caller's own credit applications as customer-readable journeys. */
    @GET
    @Path("/customer/v1/credit-applications")
    fun listCreditApplications(): Uni<List<CreditJourneyDto>>

    @GET
    @Path("/customer/v1/standing-orders")
    fun listStandingOrders(): Uni<List<StandingOrderDto>>
}

data class AccountsPage(val data: List<AccountSummary> = emptyList())

data class AccountSummary(
    val id: UUID? = null,
    val accountNumber: String = "",
    val accountType: String = "",
    val currencyCode: String = "",
    val status: String = "",
)

/** Amounts are strings on the wire: money never crosses an API as a Double. */
data class CreditQuoteRequestDto(val amount: String, val termMonths: Int)

data class CreditQuoteDto(
    val amount: String = "",
    val currency: String = "",
    val termMonths: Int = 0,
    val monthlyPayment: String = "",
    val totalPayable: String = "",
    val totalCostOfCredit: String = "",
    /** Null means "could not be computed" — render as absent, never as 0%. */
    val aprcPercent: String? = null,
    val validUntil: String = "",
    val binding: Boolean = false,
)

data class CreditJourneyDto(
    val id: String = "",
    val productKind: String = "",
    val state: String = "",
    val awaitingCustomer: List<String> = emptyList(),
    val outcomeReasonCode: String? = null,
)

data class BalanceDto(
    val currency: String = "",
    val bookedAmount: BigDecimal = BigDecimal.ZERO,
    val availableAmount: BigDecimal = BigDecimal.ZERO,
)

data class TransactionPage(val data: List<TransactionDto> = emptyList())

data class TransactionDto(
    val amount: BigDecimal = BigDecimal.ZERO,
    val currencyCode: String = "",
    val type: String = "",
    val status: String = "",
    val bookingDate: String? = null,
    val description: String? = null,
)

data class FxRateDto(
    val base: String = "",
    val quote: String = "",
    val rate: String = "",
    val bid: String? = null,
    val ask: String? = null,
    val timestamp: String? = null,
    val refMid: String? = null,
    val spreadPct: String? = null,
)

data class CardDto(
    val id: UUID? = null,
    val maskedPan: String = "",
    val cardType: String = "",
    val network: String = "",
    val status: String = "",
    val expiryDate: String? = null,
    val currency: String = "",
)

data class StatementDto(
    val id: UUID? = null,
    val pocketCurrency: String = "",
    val periodFrom: String? = null,
    val periodTo: String? = null,
    val legalSequenceNumber: Long = 0,
    val openingBalance: BigDecimal = BigDecimal.ZERO,
    val closingBalance: BigDecimal = BigDecimal.ZERO,
    val entryCount: Int = 0,
    val status: String = "",
)

data class StandingOrderDto(
    val id: UUID? = null,
    val creditorIban: String = "",
    val creditorName: String = "",
    val status: String = "",
    val frequency: String = "",
    val amount: BigDecimal = BigDecimal.ZERO,
    val currency: String = "",
    val nextExecutionDate: String? = null,
    val remittanceInfo: String? = null,
)

/** Propagates the customer's bearer onto outbound calls; no token -> no header -> downstream 401. */
@ApplicationScoped
class CopilotAuthPropagationFilter(private val identity: SecurityIdentity) : ResteasyReactiveClientRequestFilter {
    override fun filter(requestContext: ResteasyReactiveClientRequestContext) {
        val rawToken = (identity.principal as? JsonWebToken)?.rawToken ?: return
        requestContext.headers.putSingle(HttpHeaders.AUTHORIZATION, "Bearer $rawToken")
    }
}
