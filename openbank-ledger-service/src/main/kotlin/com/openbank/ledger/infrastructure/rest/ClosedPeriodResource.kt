// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.port.`in`.ClosedPeriodUseCase
import com.openbank.ledger.application.port.`in`.CreateClosedPeriodDraftCommand
import com.openbank.ledger.application.port.`in`.FreezeClosedPeriodCommand
import com.openbank.ledger.application.port.`in`.GetClosedPeriodQuery
import com.openbank.ledger.application.port.`in`.GetPeriodTrialBalanceQuery
import com.openbank.ledger.application.port.`in`.ListClosedPeriodsQuery
import com.openbank.ledger.application.port.`in`.VerifyClosedPeriodQuery
import com.openbank.ledger.domain.model.AccountingPeriod
import com.openbank.ledger.domain.model.ClosedPeriodRecord
import com.openbank.ledger.domain.model.ClosedPeriodVerification
import com.openbank.ledger.domain.model.PeriodTrialBalance
import com.openbank.ledger.domain.model.PeriodType
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Entity-level statutory period close (ADR-0096 D1).
 *
 * A period is addressed as `{type}/{date}` — any date inside it — and normalised to the whole
 * calendar period server-side. That avoids the class of caller error where a half-month range is
 * submitted as a "close": a statutory period is a whole month, quarter or year, never an arbitrary
 * window, or two closes could overlap and disagree about the same journal.
 *
 * Access control follows the ledger book-of-record convention (K7 / ADR-0018, as
 * [YearCloseResource]): reads for service/auditor/viewer/operator/admin, both state changes
 * operator-only.
 */
@Path("/api/v1/ledger/periods")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "ClosedPeriod", description = "Statutory GL period freeze — attested, immutable trial balance")
class ClosedPeriodResource(private val closedPeriodUseCase: ClosedPeriodUseCase) {

    @Inject
    lateinit var identity: SecurityIdentity

    @GET
    @Path("/{type}/{date}/trial-balance")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "#date")
    @Operation(summary = "Trial balance for the period containing the date (computed on demand)")
    suspend fun trialBalance(@PathParam("type") type: String, @PathParam("date") date: String): Response {
        val tb = closedPeriodUseCase.getTrialBalance(GetPeriodTrialBalanceQuery(period(type, date)))
        return Response.ok(tb.toResponse()).build()
    }

    @GET
    @Path("/{type}/{date}/frozen-trial-balance")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "#date")
    @Operation(summary = "Immutable FROZEN LINES_V1 trial balance for regulatory reporting (fail-closed)")
    suspend fun frozenTrialBalance(@PathParam("type") type: String, @PathParam("date") date: String): Response {
        val tb = closedPeriodUseCase.getFrozenTrialBalance(GetPeriodTrialBalanceQuery(period(type, date)))
        return Response.ok(tb.toResponse()).build()
    }

    @GET
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "")
    @Operation(summary = "List closed-period records overlapping a date range")
    suspend fun list(@QueryParam("from") from: String?, @QueryParam("to") to: String?): Response {
        val records = closedPeriodUseCase.list(ListClosedPeriodsQuery(parseDate(from, "from"), parseDate(to, "to")))
        return Response.ok(records.map { it.toResponse() }).build()
    }

    @GET
    @Path("/{type}/{date}")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "#date")
    @Operation(summary = "Get the close record for the period containing the date")
    suspend fun get(@PathParam("type") type: String, @PathParam("date") date: String): Response =
        Response.ok(closedPeriodUseCase.get(GetClosedPeriodQuery(period(type, date))).toResponse()).build()

    @POST
    @Path("/{type}/{date}")
    @RolesAllowed(Roles.OPERATOR)
    @Authorize(action = "ledger.close.draft", resource = "#date")
    @Operation(summary = "Create or refresh the DRAFT close from the current journal")
    suspend fun createDraft(@PathParam("type") type: String, @PathParam("date") date: String): Response {
        val record = closedPeriodUseCase.createDraft(
            CreateClosedPeriodDraftCommand(period(type, date), draftedBy = actingPrincipal()),
        )
        return Response.ok(record.toResponse()).build()
    }

    @GET
    @Path("/{type}/{date}/verify")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "#date")
    @Operation(
        summary = "Re-verify a close against a fresh trial balance (read-only, never flips state); " +
            "reports drift rather than failing",
    )
    suspend fun verify(@PathParam("type") type: String, @PathParam("date") date: String): Response =
        Response.ok(closedPeriodUseCase.verify(VerifyClosedPeriodQuery(period(type, date))).toResponse()).build()

    @POST
    @Path("/{type}/{date}/freeze")
    @RolesAllowed(Roles.OPERATOR)
    @Authorize(action = "ledger.approve", resource = "#date")
    @Operation(summary = "Freeze the DRAFT close (re-verifies the content hash, fail-closed; four-eyes)")
    suspend fun freeze(@PathParam("type") type: String, @PathParam("date") date: String): Response {
        val record = closedPeriodUseCase.freeze(
            FreezeClosedPeriodCommand(period(type, date), frozenBy = actingPrincipal()),
        )
        return Response.ok(record.toResponse()).build()
    }

    /** Normalise `{type}/{date}` to the whole calendar period containing that date. */
    private fun period(type: String, date: String): AccountingPeriod {
        val periodType = runCatching { PeriodType.valueOf(type.uppercase()) }.getOrElse {
            val known = PeriodType.entries.joinToString(", ") { it.name }
            throw WebApplicationException(
                "Unknown period type '$type'; expected one of $known",
                Response.Status.BAD_REQUEST,
            )
        }
        return periodType.of(parseDate(date, "date"))
    }

    private fun parseDate(value: String?, field: String): LocalDate {
        if (value.isNullOrBlank()) {
            throw WebApplicationException("$field is required (ISO-8601 date)", Response.Status.BAD_REQUEST)
        }
        return runCatching { LocalDate.parse(value) }.getOrElse {
            throw WebApplicationException("$field is not an ISO-8601 date: $value", Response.Status.BAD_REQUEST)
        }
    }

    private fun actingPrincipal(): String {
        val principal = identity.principal
        val subject = (principal as? JsonWebToken)?.subject ?: principal?.name
        if (subject.isNullOrBlank()) {
            throw WebApplicationException("Cannot resolve the acting principal", Response.Status.UNAUTHORIZED)
        }
        return subject
    }
}

data class PeriodTrialBalanceResponse(
    val period: String,
    val from: String,
    val to: String,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
    val balanced: Boolean,
    val accountCount: Int,
    val contentHash: String,
    val lines: List<PeriodTrialBalanceLineResponse>,
)

data class PeriodTrialBalanceLineResponse(
    val code: String,
    val type: String,
    val currency: String,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
    val net: BigDecimal,
)

data class ClosedPeriodResponse(
    val id: UUID,
    val period: String,
    val periodType: String,
    val from: String,
    val to: String,
    val status: String,
    val evidenceState: String,
    val computedAt: String,
    val totalDebits: BigDecimal,
    val totalCredits: BigDecimal,
    val accountCount: Int,
    val contentHash: String,
    val draftedBy: String?,
    val frozenBy: String?,
    val frozenAt: String?,
)

data class ClosedPeriodVerificationResponse(
    val period: String,
    val status: String,
    val recordedHash: String,
    val recomputedHash: String,
    val matches: Boolean,
    val balanced: Boolean,
    val recomputedAt: String,
)

private fun PeriodTrialBalance.toResponse() = PeriodTrialBalanceResponse(
    period = period.label,
    from = period.from.toString(),
    to = period.to.toString(),
    totalDebit = totalDebit,
    totalCredit = totalCredit,
    balanced = isBalanced,
    accountCount = accountCount,
    contentHash = contentHash(),
    lines = lines.map {
        PeriodTrialBalanceLineResponse(
            code = it.code,
            type = it.type.name,
            currency = it.currency,
            totalDebit = it.totalDebit,
            totalCredit = it.totalCredit,
            net = it.net,
        )
    },
)

private fun ClosedPeriodRecord.toResponse() = ClosedPeriodResponse(
    id = id,
    period = period.label,
    periodType = period.type.name,
    from = period.from.toString(),
    to = period.to.toString(),
    status = status.name,
    evidenceState = evidenceState.name,
    computedAt = computedAt.toString(),
    totalDebits = totalDebits,
    totalCredits = totalCredits,
    accountCount = accountCount,
    contentHash = contentHash,
    draftedBy = draftedBy,
    frozenBy = frozenBy,
    frozenAt = frozenAt?.toString(),
)

private fun ClosedPeriodVerification.toResponse() = ClosedPeriodVerificationResponse(
    period = period.label,
    status = status.name,
    recordedHash = recordedHash,
    recomputedHash = recomputedHash,
    matches = matches,
    balanced = balanced,
    recomputedAt = recomputedAt.toString(),
)
