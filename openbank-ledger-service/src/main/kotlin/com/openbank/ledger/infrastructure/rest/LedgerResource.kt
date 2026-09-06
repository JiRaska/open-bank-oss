// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.port.`in`.GetJournalQuery
import com.openbank.ledger.application.port.`in`.GetJournalsByTransactionQuery
import com.openbank.ledger.application.port.`in`.GetSubLedgerBalancesQuery
import com.openbank.ledger.application.port.`in`.GetTrialBalanceQuery
import com.openbank.ledger.application.port.`in`.JournalLineRequest
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.`in`.ListJournalsQuery
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.application.port.`in`.ReplayBookedChangesCommand
import com.openbank.ledger.application.port.`in`.ReplayBookedChangesResult
import com.openbank.ledger.application.port.`in`.ReplayBookedChangesUseCase
import com.openbank.ledger.application.port.`in`.ReverseJournalCommand
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.LedgerScope
import com.openbank.ledger.domain.model.SubLedgerBalance
import com.openbank.ledger.domain.model.TrialBalance
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.libs.web.SYNTHETIC_TAINT_PROPERTY
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Access control (K7 / ADR-0018): the general ledger is the bank's book of record, so **no endpoint
 * may be `@PermitAll`/unauthenticated**. Reads (journals, trial balance) were previously `@PermitAll`
 * — a money-path disclosure exposure — now gated to service callers (balance reconciliation reads the
 * ledger, ADR-0039), auditors (SOX/DORA evidence), viewers, operators and admin. Posting/reversing a
 * journal stays operator-only. Roles come from [Roles] (not raw strings). Enforced by Quarkus OIDC and
 * locked by LedgerSecurityContractTest.
 */
@Path("/api/v1/journals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Ledger", description = "General ledger journal entries")
class LedgerResource(
    private val clock: Clock,
    private val ledgerUseCase: LedgerUseCase,
    private val replayUseCase: ReplayBookedChangesUseCase,
) {

    @GET
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.list", resource = "")
    @Operation(summary = "List journal entries")
    suspend fun listJournals(
        @QueryParam("fromDate") @DefaultValue("2020-01-01") fromDate: String,
        @QueryParam("toDate") toDate: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): Response {
        val to = toDate?.let { LocalDate.parse(it) } ?: LocalDate.now(clock)
        val from = LocalDate.parse(fromDate)
        val page = ledgerUseCase.listJournals(ListJournalsQuery(from, to, limit, cursor))
        return Response.ok(page.toResponse()).build()
    }

    @GET
    @Path("/trial-balance")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "")
    @Operation(
        summary = "Trial balance — debit/credit totals per GL account (must net to zero)",
        description = "scope selects the population (ADR-0252): REAL_ONLY (default), " +
            "SYNTHETIC_ONLY or ALL. Omitting it excludes bank-owned canary activity, which is the " +
            "answer a regulatory reader needs; the response echoes the scope it counted.",
    )
    suspend fun trialBalance(
        @QueryParam("asOf") asOf: String?,
        // Nullable, not @DefaultValue: absent means REAL_ONLY, which LedgerScope.parse owns
        // alongside the rejection of an unrecognised value (a typo must not silently answer real).
        @QueryParam("scope") scope: String?,
    ): Response {
        val date = asOf?.let { LocalDate.parse(it) } ?: LocalDate.now(clock)
        val trialBalance = ledgerUseCase.getTrialBalance(GetTrialBalanceQuery(date, LedgerScope.parse(scope)))
        return Response.ok(trialBalance.toResponse()).build()
    }

    @GET
    @Path("/sub-ledger-balances")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "")
    @Operation(summary = "Per-customer deposit-control sub-ledger balances (ADR-0039 Phase B)")
    suspend fun subLedgerBalances(
        @QueryParam("asOf") asOf: String?,
        @QueryParam("subAccountId") subAccountId: UUID?,
    ): Response {
        val date = asOf?.let { LocalDate.parse(it) } ?: LocalDate.now(clock)
        val balances = ledgerUseCase.getSubLedgerBalances(GetSubLedgerBalancesQuery(date, subAccountId))
        val result = SubLedgerBalancesResponse(
            asOf = date.toString(),
            balances = balances.map { it.toResponse() },
        )
        return Response.ok(result).build()
    }

    @GET
    @Path("/{journalId}")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "#journalId")
    @Operation(summary = "Get journal entry by ID")
    suspend fun getJournal(@PathParam("journalId") journalId: UUID): Response {
        val entry = ledgerUseCase.getJournal(GetJournalQuery(journalId))
        return Response.ok(entry.toResponse()).build()
    }

    @GET
    @Path("/transaction/{transactionId}")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "#transactionId")
    @Operation(summary = "Get journal entries by transaction ID")
    suspend fun getJournalsByTransaction(@PathParam("transactionId") transactionId: UUID): Response {
        val entries = ledgerUseCase.getJournalsByTransaction(GetJournalsByTransactionQuery(transactionId))
        return Response.ok(entries.map { it.toResponse() }).build()
    }

    @POST
    @RolesAllowed(Roles.OPERATOR)
    @Authorize(action = "ledger.create", resource = "")
    @Operation(summary = "Post a balanced journal entry")
    suspend fun postJournal(
        // Nullable on purpose: JAX-RS injects null for an absent body, and a `suspend fun` emits no
        // `Intrinsics.checkNotNullParameter`, so a non-nullable declaration would let that null flow
        // into the body and NPE at the first dereference -- a 500 for what is a malformed request.
        request: PostJournalRequest?,
        @Context
        requestContext: ContainerRequestContext,
    ): Response {
        requireNotNull(request) { "a request body is required" }
        val command = PostJournalCommand(
            idempotencyKey = request.idempotencyKey,
            transactionId = request.transactionId,
            entryDate = LocalDate.parse(request.entryDate),
            valueDate = LocalDate.parse(request.valueDate),
            description = request.description,
            lines = request.requireLines().map { it.toCommand() },
            postedBy = request.createdBy,
            // The filter sets this property only after authenticating a configured canary
            // principal. Never accept a caller-supplied header or coroutine MDC as synthetic.
            synthetic = requestContext.getProperty(SYNTHETIC_TAINT_PROPERTY) == true,
        )
        val entry = ledgerUseCase.postJournal(command)
        return Response.created(URI.create("/api/v1/journals/${entry.id}"))
            .entity(entry.toResponse())
            .type(MediaType.APPLICATION_JSON)
            .build()
    }

    @POST
    @Path("/{journalId}/reverse")
    @RolesAllowed(Roles.OPERATOR)
    @Authorize(action = "ledger.reverse", resource = "#journalId")
    @Operation(summary = "Reverse a posted journal entry")
    suspend fun reverseJournal(@PathParam("journalId") journalId: UUID, request: ReverseJournalRequest): Response {
        val cmd = ReverseJournalCommand(journalId = journalId, reason = request.reason, reversedBy = request.reversedBy)
        val entry = ledgerUseCase.reverseJournal(cmd)
        return Response.ok(entry.toResponse()).build()
    }

    @POST
    @Path("/replay-booked-changes")
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.replay", resource = "")
    @Operation(
        summary = "Re-emit historical AccountBookedChanged events for a date window (ops recovery, #860)",
        description = "Reconstructs and re-enqueues the ledger's own already-posted booked movements so a " +
            "downstream projection that missed them can catch up idempotently. Posts no journal and mutates " +
            "no ledger state. dryRun (default true) previews the counts + net delta per currency without emitting.",
    )
    suspend fun replayBookedChanges(request: ReplayBookedChangesRequest): Response {
        val result = replayUseCase.replay(
            ReplayBookedChangesCommand(
                from = LocalDate.parse(request.from),
                to = LocalDate.parse(request.to),
                dryRun = request.dryRun,
            ),
        )
        return Response.ok(result.toResponse()).build()
    }
}

data class PostJournalLineRequest(
    val glAccountId: UUID,
    val side: String,
    val amount: BigDecimal,
    val currencyCode: String,
    val fxRate: BigDecimal? = null,
    val baseAmount: BigDecimal,
    val baseCurrencyCode: String,
    val subAccountId: UUID? = null,
) {
    fun toCommand() = JournalLineRequest(
        glAccountId = glAccountId,
        side = JournalSide.valueOf(side),
        amount = amount,
        currencyCode = currencyCode,
        fxRate = fxRate,
        baseAmount = baseAmount,
        baseCurrencyCode = baseCurrencyCode,
        subAccountId = subAccountId,
    )
}

data class PostJournalRequest(
    val idempotencyKey: String,
    val transactionId: UUID,
    val entryDate: String,
    val valueDate: String,
    val description: String? = null,
    val createdBy: UUID,
    /**
     * Declared with a NULLABLE element type on purpose, because that is the truth on the wire.
     *
     * Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS; it does not check the ELEMENTS of
     * a collection. So `"lines": [null]` deserialises happily into a `List<PostJournalLineRequest>`
     * holding a null, and Kotlin's non-null element type is a compile-time promise nothing keeps.
     * Writing the type honestly is what makes [requireLines] reachable instead of dead code.
     */
    val lines: List<PostJournalLineRequest?>,
) {
    /**
     * The lines, with every element proven present.
     *
     * `IllegalArgumentException` is mapped to 400 by libs-runtime's `CommonExceptionMappers`, so no
     * service-local mapper is needed or wanted (two mappers for one type are selected at random per
     * request, #526).
     */
    fun requireLines(): List<PostJournalLineRequest> = lines.mapIndexed { index, line ->
        requireNotNull(line) { "lines[$index] must not be null" }
    }
}

data class ReverseJournalRequest(val reason: String, val reversedBy: UUID)

data class JournalLineResponse(
    val id: UUID,
    val glAccountId: UUID,
    val side: String,
    val amount: java.math.BigDecimal,
    val currencyCode: String,
    val baseAmount: java.math.BigDecimal,
    val baseCurrencyCode: String,
    val sequence: Int,
    val subAccountId: UUID? = null,
)

data class JournalEntryResponse(
    val id: UUID,
    val entryNumber: Long?,
    val transactionId: UUID,
    val entryDate: String,
    val valueDate: String,
    val description: String?,
    val status: String,
    val lines: List<JournalLineResponse>,
    val createdAt: String,
    /** ADR-0252: posted by a bank-owned canary, so excluded from the regulatory aggregates. */
    val synthetic: Boolean,
)

private fun JournalLine.toResponse() = JournalLineResponse(
    id = id,
    glAccountId = glAccountId,
    side = side.name,
    amount = amount.amount,
    currencyCode = amount.currency.code,
    baseAmount = baseAmount.amount,
    baseCurrencyCode = baseAmount.currency.code,
    sequence = sequence,
    subAccountId = subAccountId,
)

private fun JournalEntry.toResponse() = JournalEntryResponse(
    id = id,
    entryNumber = entryNumber,
    transactionId = transactionId,
    entryDate = entryDate.toString(),
    valueDate = valueDate.toString(),
    description = description,
    status = status.name,
    lines = lines.map { it.toResponse() },
    createdAt = createdAt.toString(),
    synthetic = synthetic,
)

private fun CursorPage<JournalEntry>.toResponse() =
    CursorPage(data = data.map { it.toResponse() }, pagination = pagination)

data class TrialBalanceLineResponse(
    val glAccountId: UUID,
    val code: String,
    val name: String,
    val type: String,
    val currency: String,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
    val net: BigDecimal,
)

data class TrialBalanceResponse(
    val asOf: String,
    /** Which population these totals were computed over (ADR-0252); REAL_ONLY unless asked. */
    val scope: String,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
    val balanced: Boolean,
    val lines: List<TrialBalanceLineResponse>,
)

data class SubLedgerBalanceResponse(
    val subAccountId: UUID,
    val currency: String,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
    val net: BigDecimal,
)

data class SubLedgerBalancesResponse(val asOf: String, val balances: List<SubLedgerBalanceResponse>)

private fun SubLedgerBalance.toResponse() = SubLedgerBalanceResponse(
    subAccountId = subAccountId,
    currency = currency,
    totalDebit = totalDebit,
    totalCredit = totalCredit,
    net = net,
)

private fun TrialBalance.toResponse() = TrialBalanceResponse(
    asOf = asOf.toString(),
    scope = scope.name,
    totalDebit = totalDebit,
    totalCredit = totalCredit,
    balanced = isBalanced,
    lines = lines.map {
        TrialBalanceLineResponse(
            glAccountId = it.glAccountId,
            code = it.code,
            name = it.name,
            type = it.type.name,
            currency = it.currency,
            totalDebit = it.totalDebit,
            totalCredit = it.totalCredit,
            net = it.net,
        )
    },
)

data class ReplayBookedChangesRequest(val from: String, val to: String, val dryRun: Boolean = true)

data class ReplayBookedChangesResponse(
    val dryRun: Boolean,
    val from: String,
    val to: String,
    val journalEntriesScanned: Int,
    val bookedChangeEvents: Int,
    val accountsTouched: Int,
    val netDeltaByCurrency: Map<String, BigDecimal>,
)

private fun ReplayBookedChangesResult.toResponse() = ReplayBookedChangesResponse(
    dryRun = dryRun,
    from = from.toString(),
    to = to.toString(),
    journalEntriesScanned = journalEntriesScanned,
    bookedChangeEvents = bookedChangeEvents,
    accountsTouched = accountsTouched,
    netDeltaByCurrency = netDeltaByCurrency,
)
