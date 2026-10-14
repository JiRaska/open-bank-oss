// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.RequestFingerprint
import com.openbank.libs.security.Roles
import com.openbank.pensionfund.application.port.NotFoundException
import com.openbank.pensionfund.application.usecase.FundReportingService
import com.openbank.pensionfund.domain.model.FundPeriodReport
import com.openbank.pensionfund.domain.model.InstrumentClass
import com.openbank.pensionfund.domain.model.PeriodNotReportableException
import com.openbank.pensionfund.infrastructure.persistence.FUND_REPORTING_PAYLOAD_SCHEMA_VERSION
import com.openbank.pensionfund.infrastructure.persistence.FundReportingCloseRepository
import com.openbank.pensionfund.infrastructure.persistence.FundReportingSealCommand
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Period-end fund figures for statutory reporting (#12425, ADR-0336 D4). Aggregate only.
 *
 * `pension-fund.reporting.inspect` is served to staff and to tax-reporting-service's OWN client
 * (pension_fund_rest_ext.rego); it is excluded from `operator-read-any` in rules.yaml, so the
 * shared service-account does not reach it.
 */
@Tag(name = "Reporting", description = "Period-end fund aggregates for regulatory returns (#12425)")
@Path("/api/v1/reporting/funds")
@Produces(MediaType.APPLICATION_JSON)
class FundReportingResource {

    @Inject
    lateinit var reporting: FundReportingService

    @Inject
    lateinit var closes: FundReportingCloseRepository

    @Inject
    lateinit var identity: SecurityIdentity

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var objectMapper: ObjectMapper

    @GET
    @Path("/{fundId}/period-figures")
    @Operation(summary = "A fund's balance sheet, YTD P&L, unit roll-forward, portfolio and flows for one period")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE, Roles.AUDITOR)
    @Authorize(action = "pension-fund.reporting.inspect", resource = "#fundId")
    suspend fun periodFigures(
        @PathParam("fundId") fundId: UUID,
        @QueryParam("periodStart") periodStart: String?,
        @QueryParam("periodEnd") periodEnd: String?,
    ): FundPeriodFiguresResponse {
        val start = date("periodStart", periodStart)
        val end = date("periodEnd", periodEnd)
        return FundPeriodFiguresResponse.from(reporting.periodReport(fundId, start, end))
    }

    /** Seal only by an explicit staff command; the live reporting GET stays read-only. */
    @POST
    @Path("/{fundId}/period-closes")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "pension-fund.reporting.seal", resource = "#fundId")
    @Suppress("TooGenericExceptionCaught")
    suspend fun sealPeriod(
        @PathParam("fundId") fundId: UUID,
        @HeaderParam("Idempotency-Key") rawKey: String?,
        request: FundPeriodCloseRequest,
    ): Response {
        val key = requireNotNull(rawKey?.takeIf { it.isNotBlank() && it.length <= MAX_KEY_LENGTH }) {
            "Idempotency-Key must contain 1 to 128 characters"
        }
        val staff = sealStaffIdentity()
        val actor = staff.subject
        val scopedKey = RequestFingerprint.of(
            "POST",
            "fund-period-close",
            "${staff.issuer.length}:${staff.issuer}${actor.length}:$actor${key.length}:$key",
        )
        val requestSha256 = RequestFingerprint.of(
            "POST",
            "fund-period-close",
            "$fundId|${request.periodStart}|${request.periodEnd}",
        )
        fun replay(receipt: com.openbank.pensionfund.infrastructure.persistence.FundReportingCloseReceipt): Response {
            if (receipt.requestSha256 != requestSha256) throw IdempotencyKeyReusedException()
            return closeReceipt(receipt.close)
        }
        closes.byCommand(actor, scopedKey)?.let { return replay(it) }
        require(request.periodEnd.isBefore(LocalDate.now(clock))) { "the reporting period must have ended" }
        val report = reporting.periodReport(fundId, request.periodStart, request.periodEnd)
        if (report.closingValuationDate != request.periodEnd) {
            throw PeriodNotReportableException("fund $fundId has no published NAV at period close ${request.periodEnd}")
        }
        val payload = objectMapper.writeValueAsString(FundPeriodFiguresResponse.from(report))
        val close = try {
            closes.seal(
                FundReportingSealCommand(
                    fundId, request.periodStart, request.periodEnd, report.closingNavId,
                    report.fingerprint, FUND_REPORTING_PAYLOAD_SCHEMA_VERSION, payload,
                    actor, Instant.now(clock), scopedKey, requestSha256,
                ),
            )
        } catch (failure: RuntimeException) {
            // A concurrent command may win the unique receipt while this source was computed.
            val winner = closes.byCommand(actor, scopedKey) ?: throw failure
            return replay(winner)
        }
        return closeReceipt(close)
    }

    private fun closeReceipt(close: com.openbank.pensionfund.infrastructure.persistence.FundReportingClose): Response =
        Response.status(Response.Status.CREATED).entity(
            FundPeriodCloseReceipt(
                close.id, close.revision, close.fundId, close.periodStart, close.periodEnd,
                close.closingNavId, close.sourceFingerprint, close.payloadSchemaVersion,
                close.payloadSha256, close.createdBy, close.createdAt,
            ),
        ).build()

    private data class SealStaffIdentity(val issuer: String, val subject: String)

    /** A role alone is insufficient: client_credentials tokens may carry staff roles. */
    private fun sealStaffIdentity(): SealStaffIdentity {
        val jwt = identity.principal as? JsonWebToken ?: throw ForbiddenException("staff identity required")
        val staff = jwt.issuer?.takeIf { it.isNotBlank() }?.let { issuer ->
            jwt.subject?.takeIf { it.isNotBlank() }?.let { subject -> SealStaffIdentity(issuer, subject) }
        }
        val usernameClaim = jwt.getClaim<Any?>("preferred_username")
        val username = (usernameClaim as? String)?.takeIf { it.isNotBlank() }
        val names = listOfNotNull(identity.principal?.name, staff?.subject, username)
        val machine = "client_id" in jwt.claimNames ||
            names.any { it.startsWith("service-account-") || it.startsWith("agent:") }
        return staff?.takeUnless { identity.isAnonymous || username == null || machine }
            ?: throw ForbiddenException("staff identity required")
    }

    /** Serve precisely the sealed aggregate bytes, scoped to the requested fund. */
    @GET
    @Path("/{fundId}/period-closes/{closeId}")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE, Roles.AUDITOR)
    @Authorize(action = "pension-fund.reporting.inspect", resource = "#fundId")
    suspend fun sealedPeriod(@PathParam("fundId") fundId: UUID, @PathParam("closeId") closeId: UUID): Response {
        val close = closes.byId(fundId, closeId) ?: throw NotFoundException("fund reporting close $closeId not found")
        return Response.ok(close.payloadJson, MediaType.APPLICATION_JSON)
            .header("X-Reporting-Close-Id", close.id.toString())
            .header("X-Reporting-Close-Revision", close.revision.toString())
            .header("X-Reporting-Payload-Schema-Version", close.payloadSchemaVersion)
            .header("X-Reporting-Payload-SHA256", close.payloadSha256)
            .build()
    }

    private fun date(name: String, value: String?): LocalDate {
        requireNotNull(value) { "query parameter '$name' is required" }
        return try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("query parameter '$name' must be an ISO date (YYYY-MM-DD)", e)
        }
    }

    private companion object {
        const val MAX_KEY_LENGTH = 128
    }
}

data class FundPeriodCloseRequest(val periodStart: LocalDate, val periodEnd: LocalDate)

data class FundPeriodCloseReceipt(
    val id: UUID,
    val revision: Long,
    val fundId: UUID,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val closingNavId: UUID,
    val sourceFingerprint: String,
    val payloadSchemaVersion: String,
    val payloadSha256: String,
    val createdBy: String,
    val createdAt: Instant,
)

data class BalanceSheetDto(val totalAssets: BigDecimal, val totalLiabilities: BigDecimal, val totalEquity: BigDecimal)

/** Separately accumulated YTD lines; the revaluation lines are null (with a reason) when unknown. */
data class ProfitAndLossDto(
    val revaluationGains: BigDecimal?,
    val revaluationLosses: BigDecimal?,
    val otherInvestmentResult: BigDecimal?,
    val managementFees: BigDecimal,
    val profitLoss: BigDecimal,
    val linesUnavailableReason: String?,
    /** Deprecated 1.1.0 field — gains + positive other result; see [FundProfitAndLoss.income]. */
    val income: BigDecimal,
    /** Deprecated 1.1.0 field — losses + fees + negative other result; see [FundProfitAndLoss.expenses]. */
    val expenses: BigDecimal,
)

data class UnitsDto(
    val opening: BigDecimal,
    val issued: BigDecimal,
    val cancelled: BigDecimal,
    val closing: BigDecimal,
    val unitValue: BigDecimal,
    val unitValuePeriodMax: BigDecimal,
)

data class PortfolioDto(
    val carryingValue: BigDecimal?,
    val holdingsCount: Int?,
    val cash: BigDecimal?,
    val carryingValueByClass: Map<InstrumentClass, BigDecimal>?,
    val unclassifiedCount: Int?,
    val loansOutstanding: BigDecimal?,
)

data class FlowsDto(
    val subscriptions: BigDecimal,
    val redemptions: BigDecimal,
    val switchesIn: BigDecimal,
    val switchesOut: BigDecimal,
    val unitFees: BigDecimal,
    val managementFeesAccrued: BigDecimal,
)

data class EntitlementsDto(
    val opening: BigDecimal,
    val increase: BigDecimal,
    val decrease: BigDecimal,
    val closing: BigDecimal,
)

data class ParticipantCountsDto(val holders: Int, val subscribing: Int)

data class FundPeriodFiguresResponse(
    val fundId: UUID,
    val currency: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val closingNavId: UUID,
    val closingValuationDate: LocalDate,
    val balanceSheet: BalanceSheetDto,
    val profitAndLossYtd: ProfitAndLossDto,
    val units: UnitsDto,
    val portfolio: PortfolioDto,
    val flows: FlowsDto,
    val entitlements: EntitlementsDto,
    val participants: ParticipantCountsDto,
    val basisNavIds: List<UUID>,
    val fingerprint: String,
) {
    companion object {
        fun from(r: FundPeriodReport) = FundPeriodFiguresResponse(
            fundId = r.fundId,
            currency = r.currency,
            periodStart = r.periodStart,
            periodEnd = r.periodEnd,
            closingNavId = r.closingNavId,
            closingValuationDate = r.closingValuationDate,
            balanceSheet = BalanceSheetDto(
                r.balanceSheet.totalAssets,
                r.balanceSheet.totalLiabilities,
                r.balanceSheet.totalEquity,
            ),
            profitAndLossYtd = ProfitAndLossDto(
                r.profitAndLossYtd.revaluationGains,
                r.profitAndLossYtd.revaluationLosses,
                r.profitAndLossYtd.otherInvestmentResult,
                r.profitAndLossYtd.managementFees,
                r.profitAndLossYtd.profitLoss,
                r.profitAndLossYtd.linesUnavailableReason,
                r.profitAndLossYtd.income,
                r.profitAndLossYtd.expenses,
            ),
            units = UnitsDto(
                r.units.opening,
                r.units.issued,
                r.units.cancelled,
                r.units.closing,
                r.units.unitValue,
                r.units.unitValuePeriodMax,
            ),
            portfolio = PortfolioDto(
                r.portfolio.carryingValue,
                r.portfolio.holdingsCount,
                r.portfolio.cash,
                r.portfolio.carryingValueByClass,
                r.portfolio.unclassifiedCount,
                r.portfolio.loansOutstanding,
            ),
            flows = FlowsDto(
                r.flows.subscriptions,
                r.flows.redemptions,
                r.flows.switchesIn,
                r.flows.switchesOut,
                r.flows.unitFees,
                r.managementFeesAccrued,
            ),
            entitlements = EntitlementsDto(
                r.entitlements.opening,
                r.entitlements.increase,
                r.entitlements.decrease,
                r.entitlements.closing,
            ),
            participants = ParticipantCountsDto(r.participants.holders, r.participants.subscribing),
            basisNavIds = r.basisNavIds,
            fingerprint = r.fingerprint,
        )
    }
}
