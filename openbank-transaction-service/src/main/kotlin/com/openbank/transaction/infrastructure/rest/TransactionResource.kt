// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.payment.InstructionType
import com.openbank.libs.domain.payment.PaymentRail
import com.openbank.libs.security.Roles
import com.openbank.transaction.application.port.`in`.GetTransactionQuery
import com.openbank.transaction.application.port.`in`.InitiateTransactionCommand
import com.openbank.transaction.application.port.`in`.ListTransactionsQuery
import com.openbank.transaction.application.port.`in`.ReverseTransactionCommand
import com.openbank.transaction.application.port.`in`.TransactionUseCase
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.infrastructure.persistence.repository.PanacheTransactionRepository
import com.openbank.transaction.infrastructure.persistence.repository.TransactionSearchQuery
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate
import java.util.UUID

/**
 * Access control (K7 / ADR-0018): transaction history is customer financial data, so **no endpoint
 * may be `@PermitAll`/unauthenticated**. The list/search/get reads were previously `@PermitAll` — a
 * money-path disclosure exposure (the search endpoint queries by IBAN/amount/counterparty) — now gated
 * to service callers plus viewers/operators/admin. Initiating a transaction stays operator-only. Roles
 * come from [Roles] (not raw strings). Enforced by Quarkus OIDC and locked by
 * TransactionSecurityContractTest.
 */
@Path("/api/v1/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Transactions", description = "Transaction management")
class TransactionResource(
    private val transactionUseCase: TransactionUseCase,
    private val transactionRepository: PanacheTransactionRepository,
) {

    @GET
    @RolesAllowed(Roles.SERVICE, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "transaction.list", resource = "")
    @Operation(summary = "List transactions for an account")
    suspend fun listTransactions(
        @QueryParam("accountId") accountId: UUID,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): Response {
        val page = transactionUseCase.listTransactions(ListTransactionsQuery(accountId, limit, cursor))
        return Response.ok(page.toResponse()).build()
    }

    @GET
    @Path("/search")
    @RolesAllowed(Roles.SERVICE, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "transaction.search", resource = "")
    @Operation(summary = "Search transactions — BIAN aligned, supports IBAN/BBAN/reference/counterparty/amount/date")
    @Suppress("LongParameterList")
    suspend fun searchTransactions(
        @QueryParam("accountId") accountId: UUID?,
        @QueryParam("iban") iban: String?,
        @QueryParam("bban") bban: String?,
        @QueryParam("referenceNumber") referenceNumber: String?,
        @QueryParam("endToEndId") endToEndId: String?,
        @QueryParam("counterparty") counterparty: String?,
        @QueryParam("status") status: String?,
        @QueryParam("type") type: String?,
        @QueryParam("dateFrom") dateFrom: String?,
        @QueryParam("dateTo") dateTo: String?,
        @QueryParam("amountMin") amountMin: BigDecimal?,
        @QueryParam("amountMax") amountMax: BigDecimal?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): Response {
        val results = transactionRepository.search(
            TransactionSearchQuery(
                accountId = accountId,
                iban = iban,
                bban = bban,
                referenceNumber = referenceNumber,
                endToEndId = endToEndId,
                counterpartyName = counterparty,
                status = status?.let { runCatching { TransactionStatus.valueOf(it) }.getOrNull() },
                type = type?.let { runCatching { TransactionType.valueOf(it) }.getOrNull() },
                dateFrom = dateFrom?.let { LocalDate.parse(it) },
                dateTo = dateTo?.let { LocalDate.parse(it) },
                amountMin = amountMin,
                amountMax = amountMax,
                limit = limit.coerceIn(1, 200),
                offset = offset.coerceAtLeast(0),
            ),
        )
        return Response.ok(
            mapOf(
                "data" to results.map { it.toResponse() },
                "count" to results.size,
                "limit" to limit,
                "offset" to offset,
            ),
        ).build()
    }

    @GET
    @Path("/{transactionId}")
    @RolesAllowed(Roles.SERVICE, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "transaction.read", resource = "#transactionId")
    @Operation(summary = "Get transaction by ID")
    suspend fun getTransaction(@PathParam("transactionId") transactionId: UUID): Response {
        val tx = transactionUseCase.getTransaction(GetTransactionQuery(transactionId))
        return Response.ok(tx.toResponse()).build()
    }

    @POST
    @RolesAllowed(Roles.OPERATOR)
    @Authorize(action = "transaction.create", resource = "")
    @Operation(summary = "Initiate a new transaction")
    suspend fun initiateTransaction(
        request: InitiateTransactionRequest,
        @Context securityContext: SecurityContext,
    ): Response {
        val initiatedBy = runCatching { UUID.fromString(securityContext.userPrincipal?.name) }
            .getOrDefault(UUID.fromString("00000000-0000-0000-0000-000000000000"))
        val command = InitiateTransactionCommand(
            idempotencyKey = request.idempotencyKey,
            type = TransactionType.valueOf(request.type),
            sourceAccountId = request.sourceAccountId,
            targetAccountId = request.targetAccountId,
            amount = request.amount,
            currencyCode = request.currencyCode,
            settlementCurrencyCode = request.baseCurrencyCode,
            settlementAmount = request.baseAmount,
            description = request.description,
            valueDate = LocalDate.parse(request.valueDate),
            initiatedBy = initiatedBy,
            initiatedByPartyId = request.initiatedByPartyId,
            scaChallengeId = request.scaChallengeId,
            scaExemption = request.scaExemption,
            rail = request.rail?.let { runCatching { PaymentRail.valueOf(it) }.getOrNull() },
            instructionType = request.instructionType?.let { runCatching { InstructionType.valueOf(it) }.getOrNull() },
        )
        val tx = transactionUseCase.initiateTransaction(command)
        return Response.created(URI.create("/api/v1/transactions/${tx.id}"))
            .entity(tx.toResponse())
            .type(MediaType.APPLICATION_JSON)
            .build()
    }

    @POST
    @Path("/{transactionId}/reverse")
    @RolesAllowed(Roles.SERVICE, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "transaction.reverse", resource = "")
    @Operation(summary = "Reverse a completed transaction — R-transaction return path (ADR-0109)")
    suspend fun reverseTransaction(
        @PathParam("transactionId") transactionId: UUID,
        request: ReverseTransactionRequest,
    ): Response {
        val reversal = transactionUseCase.reverseTransaction(
            ReverseTransactionCommand(
                originalTransactionId = transactionId,
                idempotencyKey = request.idempotencyKey,
                reason = request.reason,
            ),
        )
        return Response.ok(reversal.toResponse()).build()
    }
}

data class ReverseTransactionRequest(val idempotencyKey: String, val reason: String)

data class InitiateTransactionRequest(
    val idempotencyKey: String,
    val type: String,
    val sourceAccountId: UUID? = null,
    val targetAccountId: UUID? = null,
    val amount: BigDecimal,
    val currencyCode: String,
    val baseAmount: BigDecimal? = null,
    val baseCurrencyCode: String? = null,
    val description: String? = null,
    val valueDate: String,
    val bookingDate: String? = null,
    /** Customer party that initiated this movement (set by the customer edge, never by clients). */
    val initiatedByPartyId: UUID? = null,
    /** Consumed SCA challenge id (ADR-0021 settlement gate) — verified at the edge. */
    val scaChallengeId: UUID? = null,
    /** Documented SCA exemption (e.g. PSD2_RTS_ART15_OWN_ACCOUNT). */
    val scaExemption: String? = null,
    /** Which scheme carried the money — a [PaymentRail] name (ADR-0103 D2). */
    val rail: String? = null,
    /** How the movement was instructed — an [InstructionType] name (ADR-0103 D2). */
    val instructionType: String? = null,
)

data class TransactionResponse(
    val id: UUID,
    val referenceNumber: String,
    val type: String,
    val sourceAccountId: UUID?,
    val targetAccountId: UUID?,
    val amount: java.math.BigDecimal,
    val currencyCode: String,
    val status: String,
    val description: String?,
    val valueDate: String,
    val bookingDate: String,
    val initiatedAt: String,
    val completedAt: String?,
    // ADR-0103 — how the money moved + how it was instructed. Null until stamped (D2);
    // consumers (customer app, statements, analytics) read these instead of guessing.
    val rail: String?,
    val instructionType: String?,
    val merchantCategory: String?,
)

private fun Transaction.toResponse() = TransactionResponse(
    id = id,
    referenceNumber = referenceNumber,
    type = type.name,
    sourceAccountId = sourceAccountId,
    targetAccountId = targetAccountId,
    amount = amount.amount,
    currencyCode = amount.currency.code,
    status = status.name,
    description = description,
    valueDate = valueDate.toString(),
    bookingDate = bookingDate.toString(),
    initiatedAt = initiatedAt.toString(),
    completedAt = completedAt?.toString(),
    rail = rail?.name,
    instructionType = instructionType?.name,
    merchantCategory = merchantCategory,
)

private fun CursorPage<Transaction>.toResponse() =
    CursorPage(data = data.map { it.toResponse() }, pagination = pagination)
