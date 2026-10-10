// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.rest.funding

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.usecase.ContributionService
import com.openbank.pension.application.usecase.IncentiveService
import com.openbank.pension.application.usecase.ReceiptOutcome
import com.openbank.pension.domain.contribution.ContributionChannel
import com.openbank.pension.domain.contribution.EmployerBatch
import com.openbank.pension.domain.contribution.EmployerBatchLine
import com.openbank.pension.domain.contribution.IncomingPayment
import com.openbank.pension.domain.contribution.UnmatchedStatus
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Provider back-office funding operations (ADR-0334 S3): payment intake, employer bulk files, the
 * unmatched-payment queue, claim runs and agency receipts, returns, clawback preview and the tax
 * certificate. Staff only — `ROLE_API` is deliberately NOT allowed, so the customer edge can never
 * reach an operator route even if a path leaked into its proxy table.
 */
@Tag(
    name = "Pension funding operations",
    description = "Operator queue, employer batches and state incentive claim batches",
)
@Path("/api/v1/pension/funding/operations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.OPERATOR, Roles.ADMIN, Roles.PAYMENTS)
@Suppress("TooManyFunctions")
class FundingOperationsResource {

    @Inject
    lateinit var contributions: ContributionService

    @Inject
    lateinit var incentives: IncentiveService

    @Inject
    lateinit var identity: SecurityIdentity

    @Inject
    lateinit var clock: Clock

    @POST
    @Path("/payments")
    @Operation(summary = "Ingest a payment from the collection account; matched by reference or parked")
    @Authorize(action = "pension.funding.ingest")
    suspend fun ingest(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: IncomingPaymentRequest?,
    ): ReceiptResponse {
        requireIdempotencyKey(idempotencyKey)
        return when (
            val outcome = contributions.receive(
                requireNotNull(request) {
                    "request body is required"
                }.toDomain(),
            )
        ) {
            is ReceiptOutcome.Credited -> ReceiptResponse(
                "CREDITED",
                ContributionResponse.from(outcome.contribution),
                null,
            )
            is ReceiptOutcome.Duplicate -> ReceiptResponse(
                "DUPLICATE",
                ContributionResponse.from(outcome.contribution),
                null,
            )
            is ReceiptOutcome.Unmatched -> ReceiptResponse("UNMATCHED", null, UnmatchedResponse.from(outcome.unmatched))
        }
    }

    @POST
    @Path("/employer-batches")
    @Operation(summary = "One employer's bulk contribution file covering one payment")
    @Authorize(action = "pension.funding.ingest")
    suspend fun employerBatch(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: EmployerBatchRequest?,
    ): List<EmployerLineResponse> {
        requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        val batch = EmployerBatch(
            employerPartyId = requireNotNull(body.employerPartyId) { "employerPartyId is required" },
            payment = requireNotNull(body.payment) {
                "payment is required"
            }.toDomain(ContributionChannel.EMPLOYER_BATCH),
            lines = requireNotNull(body.lines) { "lines are required" }.mapIndexed { i, l ->
                val line = requireNotNull(l) { "lines[$i] must not be null" }
                EmployerBatchLine(
                    requireNotNull(line.contractReference) { "lines[$i].contractReference is required" },
                    requireNotNull(line.amount) { "lines[$i].amount is required" },
                )
            },
        )
        return contributions.receiveEmployerBatch(batch).map(EmployerLineResponse::from)
    }

    @GET
    @Path("/unmatched")
    @Operation(summary = "The unmatched-payment queue, optionally filtered by status")
    @Authorize(action = "pension.funding.operate")
    suspend fun unmatched(@QueryParam("status") status: UnmatchedStatus?): List<UnmatchedResponse> =
        contributions.unmatchedQueue(status).map(UnmatchedResponse::from)

    @POST
    @Path("/unmatched/{id}/assign")
    @Operation(summary = "Attribute a parked payment to a contract and credit it")
    @Authorize(action = "pension.funding.operate", resource = "#id")
    suspend fun assign(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("id") id: UUID,
        request: AssignUnmatchedRequest?,
    ): ContributionResponse {
        requireIdempotencyKey(idempotencyKey)
        val contractId = requireNotNull(request?.contractId) { "contractId is required" }
        return ContributionResponse.from(contributions.assignUnmatched(id, contractId, actor()))
    }

    @POST
    @Path("/unmatched/{id}/return")
    @Operation(summary = "Mark a parked payment for return to the payer")
    @Authorize(action = "pension.funding.operate", resource = "#id")
    suspend fun returnToPayer(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("id") id: UUID,
    ): UnmatchedResponse {
        requireIdempotencyKey(idempotencyKey)
        return UnmatchedResponse.from(contributions.returnUnmatched(id, actor()))
    }

    @POST
    @Path("/claim-runs")
    @Operation(summary = "Generate the claims of a closed month and file every PENDING claim")
    @Authorize(action = "pension.funding.operate")
    suspend fun claimRun(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: ClaimRunRequest?,
    ): ClaimRunResponse {
        requireIdempotencyKey(idempotencyKey)
        val raw = requireNotNull(request?.period) { "period (YYYY-MM) is required" }
        val period = try {
            YearMonth.parse(raw)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("period must be YYYY-MM", e)
        }
        val result = incentives.runMonthlyClaims(period)
        return ClaimRunResponse(
            result.claimsCreated,
            result.batches.map(ClaimBatchResponse::from),
            result.unfiledFormats,
        )
    }

    @GET
    @Path("/claim-batches")
    @Operation(summary = "Filed claim batches, newest first, with their filed payload")
    @Authorize(action = "pension.funding.operate")
    suspend fun batches(): List<ClaimBatchResponse> = incentives.listBatches().map(ClaimBatchResponse::from)

    @POST
    @Path("/claim-batches/{id}/receipt")
    @Operation(summary = "Apply the agency's receipt file, crediting accepted claims and rejecting the rest")
    @Authorize(action = "pension.funding.operate", resource = "#id")
    suspend fun receipt(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("id") id: UUID,
        request: ReceiptFileRequest?,
    ): ClaimBatchResponse {
        requireIdempotencyKey(idempotencyKey)
        val payload = requireNotNull(request?.payload?.takeIf { it.isNotBlank() }) { "payload is required" }
        return ClaimBatchResponse.from(incentives.reconcileReceiptFile(id, payload))
    }

    @POST
    @Path("/claims/{id}/return")
    @Operation(summary = "Return a received incentive to the agency; writes a RETURNED ledger entry")
    @Authorize(action = "pension.funding.operate", resource = "#id")
    suspend fun returnClaim(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("id") id: UUID,
    ): ClaimResponse {
        requireIdempotencyKey(idempotencyKey)
        return ClaimResponse.from(incentives.returnClaim(id))
    }

    @GET
    @Path("/contracts/{contractId}/clawback-preview")
    @Operation(summary = "What an early exit on the given date would have to return, per incentive")
    @Authorize(action = "pension.funding.operate", resource = "#contractId")
    suspend fun clawback(
        @PathParam("contractId") contractId: UUID,
        @QueryParam("on") on: LocalDate?,
    ): List<ClawbackItemResponse> =
        incentives.clawbackPreview(contractId, on ?: LocalDate.now(clock)).map(ClawbackItemResponse::from)

    @POST
    @Path("/contracts/{contractId}/tax-years/{year}/certificate")
    @Operation(summary = "Freeze a closed tax year and issue its tax certificate")
    @Authorize(action = "pension.funding.operate", resource = "#contractId")
    suspend fun certificate(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("contractId") contractId: UUID,
        @PathParam("year") year: Int,
    ): TaxYearSummaryResponse {
        requireIdempotencyKey(idempotencyKey)
        return TaxYearSummaryResponse.from(incentives.issueCertificate(contractId, year))
    }

    private fun actor(): String = identity.principal?.name ?: "anonymous"

    private fun IncomingPaymentRequest.toDomain(
        defaultChannel: ContributionChannel = ContributionChannel.BANK_TRANSFER,
    ) = IncomingPayment(
        paymentId = requireNotNull(paymentId?.takeIf { it.isNotBlank() }) { "paymentId is required" },
        amount = requireNotNull(amount) { "amount is required" },
        currency = requireNotNull(currency) { "currency is required" },
        valueDate = requireNotNull(valueDate) { "valueDate is required" },
        reference = reference,
        channel = channel ?: defaultChannel,
        payerAccount = payerAccount,
    )
}
