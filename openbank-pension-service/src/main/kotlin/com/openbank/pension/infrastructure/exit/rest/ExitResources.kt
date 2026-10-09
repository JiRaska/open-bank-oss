// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.exit.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.exit.ClaimantDesignation
import com.openbank.pension.application.exit.ClaimantKyc
import com.openbank.pension.application.exit.ConfirmPayoutCommand
import com.openbank.pension.application.exit.DeathClaimService
import com.openbank.pension.application.exit.ExitForbiddenException
import com.openbank.pension.application.exit.ExitNotFoundException
import com.openbank.pension.application.exit.NotifyDeathCommand
import com.openbank.pension.application.exit.PayoutQuoteCommand
import com.openbank.pension.application.exit.PayoutService
import com.openbank.pension.application.exit.SignTerminationCommand
import com.openbank.pension.application.exit.TerminationService
import com.openbank.pension.application.exit.requireKey
import com.openbank.pension.application.port.`in`.Caller
import io.quarkus.security.identity.SecurityIdentity
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
import org.jboss.resteasy.reactive.server.ServerExceptionMapper
import java.util.UUID

/**
 * Participant-side exits (ADR-0334 S5): early termination, regular payout and partial withdrawal.
 *
 * Ownership is S1's rule, applied through the S1 use case: a caller without a staff role must send
 * the edge-stamped `X-Customer-Party-Id` and is confined to that party's contracts — another
 * party's contract answers 404, like an unknown id. Staff may READ without the header; every
 * money-moving command acts for the participant (the services refuse a staff caller) and needs an
 * `Idempotency-Key`.
 *
 * `@Path` directly above `class` (#3371); nullable params checked in the body (#3104).
 */
@Tag(name = "Pension exits", description = "Early termination, regular payout, partial withdrawal")
@Path("/api/v1/pension/contracts/{contractId}/exit")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
class PensionExitResource {

    @Inject
    lateinit var terminations: TerminationService

    @Inject
    lateinit var payouts: PayoutService

    @Inject
    lateinit var identity: SecurityIdentity

    private fun caller(party: String?): Caller {
        if (party != null) return Caller.customer(parseUuid(party, PARTY_HEADER))
        require(STAFF_ROLES.any(identity::hasRole)) { "header '$PARTY_HEADER' is required" }
        return Caller.STAFF
    }

    @GET
    @Path("/payout-eligibility")
    @Operation(summary = "Whether the pinned pack's payout conditions are met, and which forms are allowed")
    @Authorize(action = "pension.exit.read", resource = "#contractId")
    suspend fun eligibility(@PathParam("contractId") contractId: UUID, @HeaderParam(PARTY_HEADER) party: String?) =
        EligibilityResponse.from(payouts.eligibility(caller(party), contractId))

    @POST
    @Path("/termination/quote")
    @Operation(summary = "Binding early-termination preview: surrender value, fees, clawback, recapture, net payout")
    @Authorize(action = "pension.exit.terminate", resource = "#contractId")
    suspend fun quoteTermination(
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
    ): Response = Response.status(Response.Status.CREATED)
        .entity(TerminationResponse.from(terminations.quote(caller(party), contractId))).build()

    @POST
    @Path("/termination/{noticeId}/sign")
    @Operation(summary = "Sign the quoted notice under SCA; the contract goes TERMINATING and pays after the notice period")
    @Authorize(action = "pension.exit.terminate", resource = "#contractId")
    suspend fun signTermination(
        @PathParam("contractId") contractId: UUID,
        @PathParam("noticeId") noticeId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
        @HeaderParam(IDEMPOTENCY_HEADER) idempotencyKey: String?,
        request: SignRequest?,
    ): TerminationResponse {
        val key = requireKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        return TerminationResponse.from(
            terminations.sign(
                SignTerminationCommand(
                    caller(party), contractId, noticeId,
                    requireNotNull(body.scaChallengeId) { "scaChallengeId is required" },
                    requireNotNull(body.payoutIban) { "payoutIban is required" },
                    key,
                ),
            ),
        )
    }

    @GET
    @Path("/termination/{noticeId}")
    @Operation(summary = "One termination notice with its binding quote and progress")
    @Authorize(action = "pension.exit.read", resource = "#contractId")
    suspend fun getTermination(
        @PathParam("contractId") contractId: UUID,
        @PathParam("noticeId") noticeId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
    ) = TerminationResponse.from(terminations.get(caller(party), contractId, noticeId))

    @POST
    @Path("/payouts/quote")
    @Operation(summary = "Binding payout preview for a form (lump sum, annuity, phased, fixed period, partial)")
    @Authorize(action = "pension.exit.payout", resource = "#contractId")
    suspend fun quotePayout(
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
        request: PayoutQuoteRequest?,
    ): Response {
        val body = requireNotNull(request) { "request body is required" }
        val quoted = payouts.quote(
            PayoutQuoteCommand(
                caller(party), contractId, requireNotNull(body.form) { "form is required" }, body.amount, body.months,
            ),
        )
        return Response.status(Response.Status.CREATED).entity(PayoutResponse.from(quoted)).build()
    }

    @POST
    @Path("/payouts/{payoutId}/confirm")
    @Operation(summary = "Confirm the quoted payout under SCA to a verified own account")
    @Authorize(action = "pension.exit.payout", resource = "#contractId")
    suspend fun confirmPayout(
        @PathParam("contractId") contractId: UUID,
        @PathParam("payoutId") payoutId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
        @HeaderParam(IDEMPOTENCY_HEADER) idempotencyKey: String?,
        request: SignRequest?,
    ): PayoutResponse {
        val key = requireKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        return PayoutResponse.from(
            payouts.confirm(
                ConfirmPayoutCommand(
                    caller(party), contractId, payoutId,
                    requireNotNull(body.scaChallengeId) { "scaChallengeId is required" },
                    requireNotNull(body.payoutIban) { "payoutIban is required" },
                    key,
                ),
            ),
        )
    }

    @GET
    @Path("/payouts/{payoutId}")
    @Operation(summary = "One payout with its schedule")
    @Authorize(action = "pension.exit.read", resource = "#contractId")
    suspend fun getPayout(
        @PathParam("contractId") contractId: UUID,
        @PathParam("payoutId") payoutId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
    ) = PayoutResponse.from(payouts.get(caller(party), contractId, payoutId))

    @GET
    @Path("/payouts/{payoutId}/statement")
    @Operation(summary = "Payout statement: quoted, paid, withheld, outstanding")
    @Authorize(action = "pension.exit.read", resource = "#contractId")
    suspend fun statement(
        @PathParam("contractId") contractId: UUID,
        @PathParam("payoutId") payoutId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
    ) = PayoutStatementResponse.from(payouts.get(caller(party), contractId, payoutId))

    companion object {
        const val PARTY_HEADER = "X-Customer-Party-Id"
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        val STAFF_ROLES = listOf(Roles.OPERATOR, Roles.ADMIN, Roles.COMPLIANCE)
    }
}

/**
 * Operator-side death claims (ADR-0334 S5). Staff only — the customer edge has no route here, and
 * OPA grants these actions to human operators, never to a `service-account-`. Approval is
 * four-eyes, enforced in the aggregate (the registering operator cannot approve).
 */
@Tag(name = "Pension death claims", description = "Operator-driven settlement on the participant's death")
@Path("/api/v1/pension/death-claims")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.OPERATOR, Roles.ADMIN)
class PensionDeathClaimResource {

    @Inject
    lateinit var claims: DeathClaimService

    @Inject
    lateinit var identity: SecurityIdentity

    private fun operator(): String = identity.principal.name

    @POST
    @Operation(summary = "Register a death with evidence; freezes the contract")
    @Authorize(action = "pension.death.notify")
    suspend fun notifyDeath(
        @HeaderParam(PensionExitResource.IDEMPOTENCY_HEADER) idempotencyKey: String?,
        request: NotifyDeathRequest?,
    ): Response {
        val key = requireKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        val claim = claims.notify(
            NotifyDeathCommand(
                operator(),
                requireNotNull(body.contractId) { "contractId is required" },
                requireNotNull(body.dateOfDeath) { "dateOfDeath is required" },
                requireNotNull(body.evidenceRef) { "evidenceRef is required" },
                key,
            ),
        )
        return Response.status(Response.Status.CREATED).entity(DeathClaimResponse.from(claim)).build()
    }

    @GET
    @Path("/{claimId}")
    @Operation(summary = "One death claim with its claimants")
    @Authorize(action = "pension.death.read", resource = "#claimId")
    suspend fun get(@PathParam("claimId") claimId: UUID) = DeathClaimResponse.from(claims.get(claimId))

    @PUT
    @Path("/{claimId}/claimants")
    @Operation(summary = "Replace the claimants (shares must total 100 %) while the claim is NOTIFIED")
    @Authorize(action = "pension.death.verify", resource = "#claimId")
    suspend fun replaceClaimants(@PathParam("claimId") claimId: UUID, request: ClaimantsRequest?): DeathClaimResponse {
        val list = requireNotNull(requireNotNull(request) { "request body is required" }.claimants) { "claimants is required" }
        val designations = list.mapIndexed { i, c ->
            val item = requireNotNull(c) { "claimants[$i] must not be null" }
            ClaimantDesignation(
                requireNotNull(item.name) { "claimants[$i].name is required" },
                item.partyId,
                requireNotNull(item.sharePercent) { "claimants[$i].sharePercent is required" },
                item.estate ?: false,
            )
        }
        return DeathClaimResponse.from(claims.replaceClaimants(claimId, designations))
    }

    @POST
    @Path("/{claimId}/claimants/{claimantId}/verification")
    @Operation(summary = "KYC-light verification of one claimant and their payout account")
    @Authorize(action = "pension.death.verify", resource = "#claimId")
    suspend fun verify(
        @PathParam("claimId") claimId: UUID,
        @PathParam("claimantId") claimantId: UUID,
        request: VerifyClaimantRequest?,
    ): DeathClaimResponse {
        val body = requireNotNull(request) { "request body is required" }
        val kyc = ClaimantKyc(
            requireNotNull(body.name) { "name is required" },
            requireNotNull(body.birthDate) { "birthDate is required" },
            requireNotNull(body.identityDocumentRef) { "identityDocumentRef is required" },
            requireNotNull(body.iban) { "iban is required" },
        )
        return DeathClaimResponse.from(claims.verifyClaimant(operator(), claimId, claimantId, kyc))
    }

    @POST
    @Path("/{claimId}/approve")
    @Operation(summary = "Four-eyes approval: values the contract, fixes each share and starts the payouts")
    @Authorize(action = "pension.death.approve", resource = "#claimId")
    suspend fun approve(
        @PathParam("claimId") claimId: UUID,
        @HeaderParam(PensionExitResource.IDEMPOTENCY_HEADER) idempotencyKey: String?,
    ): DeathClaimResponse {
        requireKey(idempotencyKey)
        return DeathClaimResponse.from(claims.approve(operator(), claimId))
    }
}

/** Only the exceptions this slice owns; `IllegalArgument/State` are mapped by libs-runtime and S1. */
class ExitExceptionMappers {
    @ServerExceptionMapper
    fun notFound(e: ExitNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()

    @ServerExceptionMapper
    fun forbidden(e: ExitForbiddenException): Response =
        Response.status(Response.Status.FORBIDDEN).entity(mapOf("error" to e.message)).build()
}

private fun parseUuid(value: String, field: String): UUID =
    runCatching { UUID.fromString(value) }.getOrElse { throw IllegalArgumentException("$field must be a UUID") }
