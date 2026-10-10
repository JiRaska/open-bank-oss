// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.rest.funding

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.port.`in`.Caller
import com.openbank.pension.application.port.out.ContractNotFoundException
import com.openbank.pension.application.port.out.MandateRequest
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.application.usecase.ContributionService
import com.openbank.pension.application.usecase.IncentiveService
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.infrastructure.authz.ContractAccessGuard
import com.openbank.pension.infrastructure.rest.requireIdempotencyKey
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * Participant-facing funding of one contract (ADR-0334 S3): payment reference, contributions,
 * regular-payment mandate, incentive status, and the tax year.
 *
 * Its own root, NOT under S1's `/api/v1/pension/contracts`: two resource classes sharing a path
 * prefix with a template segment compete in JAX-RS class matching, and the loser's routes 404.
 * `@Path` sits directly above `class` (#3371). Every route resolves the contract through
 * S1's shared [ContractAccessGuard] before doing anything else: the party header is trusted only
 * from the edge relay, header-less callers are staff readers, writes act for the participant, and a
 * foreign contract is a 404.
 */
@Tag(name = "Pension funding", description = "Contributions, state incentives and tax years of a pension contract")
@Path("/api/v1/pension/funding/contracts/{contractId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE)
class ContractFundingResource {

    @Inject
    lateinit var contributions: ContributionService

    @Inject
    lateinit var incentives: IncentiveService

    @Inject
    lateinit var guard: ContractAccessGuard

    @Inject
    lateinit var contractRepository: PensionContractRepository

    /** S1's single ownership rule, applied to the contract as S1 stores it — never re-implemented here. */
    private suspend fun visible(caller: Caller, contractId: UUID): PensionContract = guard.requireVisible(
        caller,
        contractRepository.findById(contractId) ?: throw ContractNotFoundException(contractId),
    )

    @GET
    @Path("/payment-reference")
    @Operation(summary = "The reference a payer quotes so a payment matches this contract")
    @Authorize(action = "pension.funding.inspect", resource = "#contractId")
    suspend fun reference(
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(ContractAccessGuard.PARTY_HEADER) party: String?,
    ): PaymentReferenceResponse {
        visible(guard.readerFor(party), contractId)
        return PaymentReferenceResponse(contractId, contributions.paymentReference(contractId))
    }

    @GET
    @Path("/contributions")
    @Operation(summary = "Every contribution credited to the contract, oldest first")
    @Authorize(action = "pension.funding.inspect", resource = "#contractId")
    suspend fun list(
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(ContractAccessGuard.PARTY_HEADER) party: String?,
    ): List<ContributionResponse> {
        visible(guard.readerFor(party), contractId)
        return contributions.list(contractId).map(ContributionResponse::from)
    }

    @POST
    @Path("/mandates")
    @Operation(summary = "Set up a standing order or SEPA direct debit quoting the contract reference")
    @Authorize(action = "pension.funding.mandate", resource = "#contractId")
    suspend fun mandate(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(ContractAccessGuard.PARTY_HEADER) party: String?,
        request: MandateSetupRequest?,
    ): Response {
        requireIdempotencyKey(idempotencyKey)
        val contract = visible(guard.actingParticipant(party), contractId)
        val body = requireNotNull(request) { "request body is required" }
        val id = contributions.setUpMandate(
            MandateRequest(
                contractId = contractId,
                participantPartyId = contract.participantPartyId,
                kind = requireNotNull(body.kind) { "kind is required" },
                debtorIban = requireNotNull(body.debtorIban?.takeIf { it.isNotBlank() }) { "debtorIban is required" },
                amount = requireNotNull(body.amount?.takeIf { it.signum() > 0 }) { "amount must be positive" },
                currency = requireNotNull(body.currency) { "currency is required" },
                reference = "",
                firstCollection = requireNotNull(body.firstCollection) { "firstCollection is required" },
            ),
        )
        return Response.status(Response.Status.CREATED).entity(MandateResponse(id)).build()
    }

    @PUT
    @Path("/employers/{employerPartyId}")
    @Operation(summary = "Authorise an employer to contribute to this contract through its bulk files")
    @Authorize(action = "pension.funding.mandate", resource = "#contractId")
    suspend fun enrolEmployer(
        @PathParam("contractId") contractId: UUID,
        @PathParam("employerPartyId") employerPartyId: UUID,
        @HeaderParam(ContractAccessGuard.PARTY_HEADER) party: String?,
    ): Response {
        visible(guard.actingParticipant(party), contractId)
        contributions.enrolEmployer(contractId, employerPartyId)
        return Response.noContent().build()
    }

    @GET
    @Path("/incentives")
    @Operation(summary = "Incentive claims and the per-incentive ledger balance")
    @Authorize(action = "pension.funding.inspect", resource = "#contractId")
    suspend fun incentiveStatus(
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(ContractAccessGuard.PARTY_HEADER) party: String?,
    ): IncentiveStatusResponse {
        visible(guard.readerFor(party), contractId)
        val status = incentives.status(contractId)
        return IncentiveStatusResponse(
            status.claims.map(ClaimResponse::from),
            status.balances.map(BalanceResponse::from),
        )
    }

    @GET
    @Path("/tax-years/{year}")
    @Operation(summary = "Contributions by source, incentives and deductible amount for one tax year")
    @Authorize(action = "pension.funding.inspect", resource = "#contractId")
    suspend fun taxYear(
        @PathParam("contractId") contractId: UUID,
        @PathParam("year") year: Int,
        @HeaderParam(ContractAccessGuard.PARTY_HEADER) party: String?,
    ): TaxYearSummaryResponse {
        visible(guard.readerFor(party), contractId)
        return TaxYearSummaryResponse.from(incentives.taxSummary(contractId, year))
    }

    @PUT
    @Path("/tax-years/{year}/external-cap-usage")
    @Operation(summary = "Declare shared-cap usage at other providers for the tax year")
    @Authorize(action = "pension.funding.declare", resource = "#contractId")
    suspend fun declareExternal(
        @PathParam("contractId") contractId: UUID,
        @PathParam("year") year: Int,
        @HeaderParam(ContractAccessGuard.PARTY_HEADER) party: String?,
        request: ExternalCapUsageRequest?,
    ): TaxYearSummaryResponse {
        visible(guard.actingParticipant(party), contractId)
        val usage = requireNotNull(request?.usage) { "usage is required" }
            .mapValues { (group, v) -> requireNotNull(v) { "usage['$group'] must not be null" } }
        incentives.declareExternalCapUsage(contractId, year, usage)
        return TaxYearSummaryResponse.from(incentives.taxSummary(contractId, year))
    }
}
