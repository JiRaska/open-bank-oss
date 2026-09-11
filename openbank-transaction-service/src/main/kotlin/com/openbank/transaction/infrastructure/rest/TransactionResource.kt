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
import com.openbank.transaction.infrastructure.persistence.entity.MerchantLocationEntity
import com.openbank.transaction.infrastructure.persistence.repository.MerchantCatalogRepository
import com.openbank.transaction.infrastructure.persistence.repository.MerchantLocationRepository
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
    private val merchantLocations: MerchantLocationRepository,
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
        // A third bounded read, for the same reason as the others: a chain's coordinates depend on
        // WHICH town the descriptor named, and the catalogue row cannot know that. Keyed by the pair,
        // one query per page.
        val locations = merchantLocations.findByKeys(locationKeys(page.data.map { it.description }))
        return Response.ok(page.toResponse(merchants, overrides, locations)).build()
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
                status = parseEnumParam<TransactionStatus>("status", status),
                type = parseEnumParam<TransactionType>("type", type),
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
            rail = parseEnumParam<PaymentRail>("rail", request.rail),
            instructionType = parseEnumParam<InstructionType>("instructionType", request.instructionType),
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
 *
 * [logoUrl] is ORIGIN-RELATIVE and always points back at whichever host served this response. That
 * is the whole design: the catalogue also records where a logo was obtained from, and putting THAT
 * URL here instead would make every statement render fire a request at a third-party CDN carrying
 * the customer's IP address and the merchant they paid — a spending profile leaving the bank
 * through an `<img>` tag. The bytes are ingested once and served from this bank's own origin, so
 * the field is a path and never an external link.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MerchantResponse(
    val cleanName: String,
    val logoUrl: String?,
    val category: String?,
    val geo: MerchantGeoResponse?,
    val source: String = "ENRICHED",
)

/**
 * Where the merchant is — and how much that answer is worth.
 *
 * [precision] is `EXACT` only where the coordinates are about the place the money was spent: a
 * single-site merchant, or a location resolved from the device that took the payment. For a chain
 * it is `CITY`, because a brand has no single location and the seeded coordinates were a pin in
 * Prague that put every Billa purchase in the country at one address. A client may caption the town
 * for `CITY`; it must not drop a pin claiming a street.
 */
data class MerchantGeoResponse(
    val lat: Double,
    val lon: Double,
    val city: String?,
    val country: String?,
    val precision: String,
)

/**
 * How much of the content hash goes in the logo URL. A 64-bit prefix: the token only has to
 * distinguish one merchant's successive logos from each other, and a full hash makes every
 * statement row longer for nothing.
 */
private const val LOGO_VERSION_CHARS = 16

/**
 * @param location the merchant's row for the town THIS transaction's descriptor named, when there is
 *   one. It wins over the catalogue's own coordinates, which for a chain are a representative pin
 *   for the whole brand and cannot be about a particular purchase.
 */
private fun MerchantCatalogEntity.toResponse(location: MerchantLocationEntity? = null) = MerchantResponse(
    cleanName = cleanName,
    // Null when no logo has been ingested — absence stays absence, and a client renders whatever
    // it renders today. The hash makes the URL change whenever the bytes do, which is what lets
    // the logo route answer with a year-long immutable cache and still correct a wrong logo the
    // moment it is replaced.
    logoUrl = logoEtag?.let { "/api/v1/merchants/$descriptorKey/logo?size=64&v=${it.take(LOGO_VERSION_CHARS)}" },
    category = category,
    // Both coordinates or neither — the column constraint enforces it, and this mirrors it so a
    // half-populated row can never become a pin at latitude 0.
    geo = when {
        location != null -> MerchantGeoResponse(
            lat = location.lat,
            lon = location.lon,
            city = location.city,
            country = location.country,
            precision = location.geoPrecision,
        )
        lat != null && lon != null -> MerchantGeoResponse(
            lat = lat!!,
            lon = lon!!,
            city = city,
            country = country,
            precision = geoPrecision,
        )
        else -> null
    },
)

/**
 * The (merchant, town) pairs a page of descriptions can resolve to a location.
 *
 * Only descriptors that named a town produce a pair: with no town there is nothing to narrow to,
 * and asking for `<merchant>|` would either miss or — worse — match a row someone had keyed on the
 * empty string.
 */
private fun locationKeys(descriptions: List<String?>): Set<Pair<String, String>> =
    descriptions.mapNotNull { MerchantDescriptor.parse(it) }
        .mapNotNull { parsed -> parsed.cityToken?.let { parsed.key to it } }
        .toSet()

private fun Transaction.toResponse(
    merchants: Map<String, MerchantCatalogEntity> = emptyMap(),
    overrides: Map<String, String> = emptyMap(),
    locations: Map<String, MerchantLocationEntity> = emptyMap(),
): TransactionResponse {
    // ONE parse, not two. `MerchantDescriptor.normalise(d)` is defined as `parse(d)?.key`, so the
    // two sides of this merge were asking the same question twice — the catalogue lookup needs the
    // key, the location lookup needs the key AND the town, and `parse` returns both.
    val parsed = MerchantDescriptor.parse(description)
    val catalogue = parsed?.let { merchants[it.key] }
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
        merchant = catalogue?.toResponse(
            location = parsed?.cityToken?.let { locations["${parsed.key}|$it"] },
        ),
    )
}

private fun CursorPage<Transaction>.toResponse(
    merchants: Map<String, MerchantCatalogEntity> = emptyMap(),
    overrides: Map<String, String> = emptyMap(),
    locations: Map<String, MerchantLocationEntity> = emptyMap(),
) = CursorPage(data = data.map { it.toResponse(merchants, overrides, locations) }, pagination = pagination)

/**
 * Strict enum parsing for request inputs (issue #8699). The previous
 * `runCatching { X.valueOf(it) }.getOrNull()` turned an unparseable value into a LEGAL null, and
 * null then did two different wrong things: on the search filters it DROPPED the condition, so
 * `?status=FAILDE` returned every transaction with a 200 (while a malformed date three lines
 * below correctly 400s), and on the initiate path it persisted a money-path debit with
 * `rail = null` under a 201. Absent and unparseable must stay distinguishable: absent stays null,
 * unparseable is the caller's error — IllegalArgumentException, which libs-runtime maps to 400
 * (#526: never a service-local mapper).
 */
private inline fun <reified E : Enum<E>> parseEnumParam(name: String, raw: String?): E? {
    raw ?: return null
    return enumValues<E>().firstOrNull { it.name == raw }
        ?: throw IllegalArgumentException(
            "'$name' has unknown value '$raw' (allowed: ${enumValues<E>().joinToString()})",
        )
}
