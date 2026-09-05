// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonInclude
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.payment.InstructionType
import com.openbank.libs.domain.payment.PaymentRail
import com.openbank.libs.security.Roles
import com.openbank.libs.spend.SpendCategory
import com.openbank.transaction.application.port.`in`.GetTransactionQuery
import com.openbank.transaction.application.port.`in`.InitiateTransactionCommand
import com.openbank.transaction.application.port.`in`.ListTransactionsQuery
import com.openbank.transaction.application.port.`in`.ReverseTransactionCommand
import com.openbank.transaction.application.port.`in`.TransactionUseCase
import com.openbank.transaction.domain.model.CounterpartyKey
import com.openbank.transaction.domain.model.MerchantDescriptor
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.infrastructure.persistence.entity.MerchantCatalogEntity
import com.openbank.transaction.infrastructure.persistence.repository.MerchantCatalogRepository
import com.openbank.transaction.infrastructure.persistence.repository.PanacheTransactionRepository
import com.openbank.transaction.infrastructure.persistence.repository.TransactionCategoryOverrideRepository
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
    private val merchantCatalog: MerchantCatalogRepository,
    private val categoryOverrides: TransactionCategoryOverrideRepository,
) {

    @GET
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "transaction.list", resource = "")
    @Operation(summary = "List transactions for an account")
    suspend fun listTransactions(
        @QueryParam("accountId") accountId: UUID?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): Response {
        // #3104 — listing "transactions for an account" with no account is a bad request, not a
        // server fault. Absent, this reached ListTransactionsQuery as null and answered 500.
        requireNotNull(accountId) { "query parameter 'accountId' is required" }
        val page = transactionUseCase.listTransactions(ListTransactionsQuery(accountId, limit, cursor))
        // D5 — one catalogue query per page, after the page is fetched. Enrichment is additive and
        // display-only: `description` is passed through untouched, because disputes and SPAYD are
        // built from the raw acquirer descriptor and must not inherit a prettified name.
        val merchants = merchantCatalog.findByDescriptors(page.data.map { it.description })
        // The customer's own categorisation of the counterparties on this page. One query for the
        // page, keyed by account, so it cannot reach rows belonging to a different account.
        val overrides = categoryOverrides.findFor(
            accountId,
            page.data.mapNotNull { CounterpartyKey.of(it.counterpartyName, it.description) },
        )
        return Response.ok(page.toResponse(merchants, overrides)).build()
    }

    @GET
    @Path("/search")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
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
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
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
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "transaction.reverse", resource = "")
    @Operation(summary = "Reverse a completed transaction — R-transaction return path (ADR-0111)")
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

    /**
     * ADR-0179 — sweep a duplicate party's pocket into the surviving party's pocket as part of an
     * identity merge.
     *
     * A **dedicated endpoint with its own action**, deliberately not a flavour of
     * `transaction.create`. Two reasons, and both are load-bearing:
     *
     *  1. `transaction.create` is on the M2M payment rails. `four_eyes_required` is computed from
     *     the action name alone with no awareness of the caller, so adding a four-eyes verb that
     *     matches `create` would pause every automated payment the moment enforcement is switched
     *     on (`rules.yaml: four_eyes` guardrail). A distinct operator-only action is the pattern
     *     that guardrail prescribes.
     *  2. The resulting journal must be *identifiable* as a bookkeeping correction. Passing
     *     `type = ADJUSTMENT` to the ordinary endpoint would not achieve that:
     *     [com.openbank.transaction.application.usecase.PaymentJournalFactory] never reads
     *     `transaction.type`, so the posting would be byte-identical to a customer payment. The
     *     structured description minted here is what carries the distinction into the ledger.
     *
     * `initiatedByPartyId` is deliberately left null — this is a bank-initiated correction, not a
     * customer-initiated movement, so it takes the documented system-posting path past the SCA
     * gate rather than carrying a challenge that no customer ever answered.
     */
    @POST
    @Path("/merge-sweep")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "transaction.sweep", resource = "")
    @Operation(
        summary = "Sweep a duplicate party's balance to the surviving party during an identity merge (ADR-0179)",
    )
    suspend fun mergeSweep(request: MergeSweepRequest, @Context securityContext: SecurityContext): Response {
        val initiatedBy = runCatching { UUID.fromString(securityContext.userPrincipal?.name) }
            .getOrDefault(UUID.fromString("00000000-0000-0000-0000-000000000000"))
        val command = InitiateTransactionCommand(
            idempotencyKey = request.idempotencyKey,
            type = TransactionType.ADJUSTMENT,
            sourceAccountId = request.sourceAccountId,
            targetAccountId = request.targetAccountId,
            amount = request.amount,
            currencyCode = request.currencyCode,
            settlementCurrencyCode = request.currencyCode,
            settlementAmount = request.amount,
            description = MergeSweepDescription.of(request),
            valueDate = LocalDate.parse(request.valueDate),
            initiatedBy = initiatedBy,
            // Bank-initiated correction: no customer initiated it, so no SCA challenge exists.
            initiatedByPartyId = null,
            scaChallengeId = null,
            scaExemption = null,
            rail = null,
            instructionType = null,
        )
        val tx = transactionUseCase.initiateTransaction(command)
        return Response.created(URI.create("/api/v1/transactions/${tx.id}"))
            .entity(tx.toResponse())
            .type(MediaType.APPLICATION_JSON)
            .build()
    }
}

/**
 * Mints the journal description for a merge sweep (ADR-0179).
 *
 * The ledger has no entry-type or reason-code column — a journal's only free field is
 * `description` — so this prefix is the sole thing distinguishing a merge correction from an
 * ordinary customer transfer in the trial balance, on a statement, and to an auditor. It is
 * therefore built here in one place and asserted in tests, not composed ad hoc by callers.
 */
object MergeSweepDescription {
    const val PREFIX = "MERGE-SWEEP"

    fun of(request: MergeSweepRequest): String =
        "$PREFIX ${request.mergeReference}: party ${request.sourcePartyId} -> ${request.survivingPartyId}"
}

data class ReverseTransactionRequest(val idempotencyKey: String, val reason: String)

/**
 * ADR-0179. [mergeReference] ties the posting back to the approved merge case, so the money
 * movement and the identity retirement are traceable to one another from either end.
 */
data class MergeSweepRequest(
    val idempotencyKey: String,
    val sourceAccountId: UUID,
    val targetAccountId: UUID,
    val sourcePartyId: UUID,
    val survivingPartyId: UUID,
    val amount: BigDecimal,
    val currencyCode: String,
    val valueDate: String,
    val mergeReference: String,
)

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
    // The category to SHOW. `merchantCategory` keeps its meaning — MCC-derived, a fact about the
    // card network — and is never overwritten by a customer's opinion; consumers that need the MCC
    // fact still read it. This field is the resolved answer, and [categorySource] says whose it is,
    // so a client can offer "you categorised this" with a way to undo.
    val category: String?,
    val categorySource: String?,
    // D5 — resolved merchant identity. Absent when the acquirer descriptor is not in the
    // catalogue, which is most of them: absence is what tells the client to render the raw
    // description, and it must never be filled with a guess.
    //
    // NON_NULL is contractual, not cosmetic. Serialising `"merchant": null` adds a key to every
    // existing response body, which is a wire change for consumers that have never heard of
    // enrichment — the sepa-payment Pact verification fails on exactly that. Additive means the
    // old bytes stay the old bytes when there is nothing to add.
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val merchant: MerchantResponse? = null,
)

/**
 * Public identity of the merchant behind a card transaction.
 *
 * [geo] is null for card-not-present merchants — an e-shop has no place where the money was spent,
 * and a head-office pin on a "where you spent" map would be fiction. [source] is always `ENRICHED`
 * here; the field exists so a client never has to infer whether a name is the bank's or the
 * acquirer's.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MerchantResponse(
    val cleanName: String,
    val logoUrl: String?,
    val category: String?,
    val geo: MerchantGeoResponse?,
    val source: String = "ENRICHED",
)

data class MerchantGeoResponse(val lat: Double, val lon: Double, val city: String?, val country: String?)

private fun MerchantCatalogEntity.toResponse() = MerchantResponse(
    cleanName = cleanName,
    logoUrl = logoUrl,
    category = category,
    // Both coordinates or neither — the column constraint enforces it, and this mirrors it so a
    // half-populated row can never become a pin at latitude 0.
    geo = if (lat != null && lon != null) {
        MerchantGeoResponse(lat = lat!!, lon = lon!!, city = city, country = country)
    } else {
        null
    },
)

private fun Transaction.toResponse(
    merchants: Map<String, MerchantCatalogEntity> = emptyMap(),
    overrides: Map<String, String> = emptyMap(),
): TransactionResponse {
    val catalogue = MerchantDescriptor.normalise(description)?.let { merchants[it] }
    // Unknown ids are dropped rather than shown. A category retired from the shared vocabulary
    // leaves rows behind, and echoing one back would name a category no client can render or undo.
    val mine = CounterpartyKey.of(counterpartyName, description)
        ?.let { overrides[it] }
        ?.takeIf { SpendCategory.isKnown(it) }
    val resolved = mine ?: merchantCategory ?: catalogue?.category
    return TransactionResponse(
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
        category = resolved,
        categorySource = when {
            resolved == null -> null
            mine != null -> "CUSTOMER"
            merchantCategory != null -> "MCC"
            else -> "CATALOGUE"
        },
        merchant = catalogue?.toResponse(),
    )
}

private fun CursorPage<Transaction>.toResponse(
    merchants: Map<String, MerchantCatalogEntity> = emptyMap(),
    overrides: Map<String, String> = emptyMap(),
) = CursorPage(data = data.map { it.toResponse(merchants, overrides) }, pagination = pagination)
