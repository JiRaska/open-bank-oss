// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.port.`in`.JournalLineRequest
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.application.usecase.AccountingClassService
import com.openbank.ledger.application.usecase.LedgerBookKind
import com.openbank.ledger.application.usecase.PensionBookIdentity
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.checkConflict
import com.openbank.ledger.domain.model.requireValid
import com.openbank.ledger.infrastructure.persistence.repository.PensionFeeReceipts
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.security.Roles
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

data class PensionFeePostingRequest(
    val bookId: UUID,
    val fundId: UUID,
    val navId: UUID,
    val valuationDate: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val correctsNavId: UUID? = null,
)

data class PensionFeePostingResponse(
    val bookId: UUID,
    val navId: UUID,
    val postingDate: LocalDate,
    val outcome: String,
    val journalId: UUID?,
    val synthetic: Boolean,
)

/** A closed purpose: no arbitrary accounts, cash settlement or bank posting through this route. */
@Path("/api/v1/ledger/pension-accounting")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class PensionAccountingResource(
    private val book: PensionBookIdentity,
    private val ledger: LedgerUseCase,
    private val classes: AccountingClassService,
    private val receipts: PensionFeeReceipts,
    private val clock: AccountingClock,
    private val identity: SecurityIdentity,
) {
    @jakarta.inject.Inject
    lateinit var taint: com.openbank.libs.web.SyntheticTaintContext

    @jakarta.inject.Inject
    lateinit var accountingDays: com.openbank.ledger.application.port.`in`.AccountingDayUseCase

    @GET
    @Path("/identity")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.AUDITOR)
    @Authorize(action = "ledger.pension.inspect", resource = "")
    fun identity(): Map<String, Any?> = mapOf("kind" to book.kind, "bookId" to book.bookId)

    @GET
    @Path("/closing-days")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.AUDITOR)
    @Authorize(action = "ledger.pension.inspect", resource = "")
    suspend fun closingDays(
        @jakarta.ws.rs.QueryParam("from") from: LocalDate?,
        @jakarta.ws.rs.QueryParam("to") to: LocalDate?,
    ): List<Map<String, String>> {
        if (from == null && to == null) {
            return receipts.pendingDays(clock.today())
                .map { (date, status) -> mapOf("businessDate" to date.toString(), "status" to status) }
        }
        requireNotNull(from) { "from is required" }
        requireNotNull(to) { "to is required" }
        requireValid(!to.isBefore(from) && java.time.temporal.ChronoUnit.DAYS.between(from, to) <= MAX_DAYS) {
            "Closing-day lookup is bounded to 31 days"
        }
        return accountingDays.list(com.openbank.ledger.application.port.`in`.ListAccountingDaysQuery(from, to))
            .map { mapOf("businessDate" to it.businessDate.toString(), "status" to it.status.name) }
    }

    @POST
    @Path("/management-fees")
    @RolesAllowed(Roles.API)
    @Authorize(action = "ledger.pension.fee", resource = "")
    suspend fun accrue(
        request: PensionFeePostingRequest?,
        @HeaderParam("x-openbank-synthetic") synthetic: String?,
    ): PensionFeePostingResponse {
        requireNotNull(request) { "a request body is required" }
        checkConflict(book.kind != LedgerBookKind.BANK && request.bookId == book.bookId) {
            "Pension accounting destination does not match this book"
        }
        checkConflict(book.kind != LedgerBookKind.PENSION_FUND || request.fundId == book.bookId) {
            "Pension fund accounting belongs to another fund"
        }
        if (ServiceAccountIdentity.verifiedPrincipalName(identity) != "service-account-openbank-pension-fund") {
            throw ForbiddenException("Management fee source must be pension-fund-service's verified client")
        }
        checkConflict(!com.openbank.libs.synthetic.SyntheticTaint.isTainted(synthetic) || taint.tainted) {
            "Synthetic accounting requires an authenticated trusted taint decision"
        }
        requireValid(request.currency == "CZK" && request.amount.scale() <= MONEY_SCALE) {
            "Pension fee chart currently supports CZK amounts with at most two decimal places"
        }
        requireValid(request.amount.signum() >= 0 || request.correctsNavId != null) {
            "A negative accrual requires a NAV correction reference"
        }
        request.correctsNavId?.let { original ->
            requireValid(original != request.navId) { "A NAV cannot correct itself" }
            receipts.requireOriginal(original, request.fundId, taint.tainted)
        }
        requireValid(!clock.isFuture(request.valuationDate)) { "NAV valuation date cannot be in the future" }
        val normalized = request.amount.setScale(MONEY_SCALE)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(
            listOf(
                request.bookId,
                request.fundId,
                request.navId,
                request.valuationDate,
                normalized,
                request.currency,
                request.correctsNavId,
                taint.tainted,
            ).joinToString("|").toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }
        val receipt = receipts.reserve(request.navId, request.fundId, fingerprint, clock.today(), taint.tainted)
        val journalId = if (receipt.outcome == "PENDING" && normalized.signum() != 0) {
            post(request, receipt.postingDate, normalized)
        } else {
            receipt.journalId
        }
        if (receipt.outcome == "PENDING") receipts.complete(request.navId, journalId)
        return PensionFeePostingResponse(
            request.bookId,
            request.navId,
            receipt.postingDate,
            if (journalId == null) "NO_POSTING" else "POSTED",
            journalId,
            taint.tainted,
        )
    }

    private suspend fun post(request: PensionFeePostingRequest, date: LocalDate, amount: BigDecimal): UUID {
        val company = book.kind == LedgerBookKind.PENSION_COMPANY
        val debit = if (company) "PENSION_MANAGEMENT_FEE_RECEIVABLE" else "PENSION_MANAGEMENT_FEE_EXPENSE"
        val credit = if (company) "PENSION_MANAGEMENT_FEE_INCOME" else "PENSION_MANAGEMENT_FEE_PAYABLE"
        val lines = listOf(debit to JournalSide.DEBIT, credit to JournalSide.CREDIT).map { (purpose, side) ->
            val mapping = classes.resolve(purpose, if (purpose.endsWith("INCOME")) "FEE_INCOME" else "PRINCIPAL", "CZK")
            JournalLineRequest(
                mapping.glAccountId,
                if (amount.signum() >
                    0
                ) {
                    side
                } else if (side == JournalSide.DEBIT) {
                    JournalSide.CREDIT
                } else {
                    JournalSide.DEBIT
                },
                amount.abs(),
                "CZK",
                null,
                amount.abs(),
                "CZK",
            )
        }
        val command = PostJournalCommand(
            "pension-management-fee:${request.navId}",
            request.navId,
            date,
            request.valuationDate,
            "Pension management fee NAV ${request.navId}; fund ${request.fundId}",
            lines,
            UUID.nameUUIDFromBytes(identity.principal.name.toByteArray(Charsets.UTF_8)),
            synthetic = taint.tainted,
        )
        val entry = ledger.postJournalWithOutcome(command).entry
        checkConflict(matches(entry, command)) {
            "Existing journal idempotency key belongs to another accounting payload"
        }
        return entry.id
    }

    private fun matches(entry: com.openbank.ledger.domain.model.JournalEntry, command: PostJournalCommand): Boolean =
        entry.transactionId == command.transactionId &&
            entry.entryDate == command.entryDate &&
            entry.valueDate == command.valueDate &&
            entry.createdBy == command.postedBy &&
            entry.description == command.description &&
            entry.synthetic == command.synthetic &&
            entry.lines.size == command.lines.size &&
            entry.lines.zip(command.lines).all { (actual, expected) ->
                actual.glAccountId == expected.glAccountId &&
                    actual.side == expected.side &&
                    actual.baseAmount.amount.compareTo(expected.baseAmount) == 0 &&
                    actual.amount.currency.code == expected.currencyCode &&
                    actual.subAccountId == null
            }

    private companion object {
        const val MONEY_SCALE = 2
        const val MAX_DAYS = 31
    }
}
