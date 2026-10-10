// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.exit.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.pension.application.exit.ChangePayoutAccountCommand
import com.openbank.pension.application.exit.ConfirmPayoutCommand
import com.openbank.pension.application.exit.ExitForbiddenException
import com.openbank.pension.application.exit.ExitNotFoundException
import com.openbank.pension.application.exit.PayoutQuoteCommand
import com.openbank.pension.application.exit.PayoutService
import com.openbank.pension.application.exit.SignTerminationCommand
import com.openbank.pension.application.exit.TerminationService
import com.openbank.pension.application.exit.requireKey
import com.openbank.pension.application.port.`in`.Caller
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
import org.jboss.resteasy.reactive.server.ServerExceptionMapper
import java.util.UUID

/**
 * Participant-side exits (ADR-0334 S5): early termination, regular payout and partial withdrawal.
 *
 * Ownership is S1's [ContractAccessGuard] (the header is trusted only from the edge relay) plus
 * the S1 use case's visibility rule: another party's contract answers 404, like an unknown id.
 * Staff may READ without the header; every money-moving command acts for a vouched participant and
 * needs an `Idempotency-Key`.
 *
 * `@Path` directly above `class` (#3371); nullable params checked in the body (#3104).
 */
@Tag(name = "Pension exits", description = "Early termination, regular payout, partial withdrawal")
@Path("/api/v2/pension/contracts/{contractId}/exit")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
@Suppress("TooManyFunctions") // one method per participant exit route
class PensionExitResource {

    @Inject
    lateinit var terminations: TerminationService

    @Inject
    lateinit var payouts: PayoutService

    /** S1's single ownership rule: the party header is trusted only from the edge relay. */
    @Inject
    lateinit var access: ContractAccessGuard

    private fun reader(party: String?): Caller = access.readerFor(party)

    private fun participant(party: String?): Caller = access.actingParticipant(party)

    @GET
    @Path("/payout-eligibility")
    @Operation(summary = "Whether the pinned pack's payout conditions are met, and which forms are allowed")
    @Authorize(action = "pension.exit.inspect", resource = "#contractId")
    suspend fun eligibility(@PathParam("contractId") contractId: UUID, @HeaderParam(PARTY_HEADER) party: String?) =
        EligibilityResponse.from(payouts.eligibility(reader(party), contractId))

    @POST
    @Path("/termination/quote")
    @Operation(summary = "Binding early-termination preview: surrender value, fees, clawback, recapture, net payout")
    @Authorize(action = "pension.exit.terminate", resource = "#contractId")
    suspend fun quoteTermination(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
    ): Response {
        requireIdempotencyKey(idempotencyKey)
        return Response.status(Response.Status.CREATED)
            .entity(TerminationResponse.from(terminations.quote(participant(party), contractId))).build()
    }

    @POST
    @Path("/termination/{noticeId}/sign")
    @Operation(
        summary = "Sign the quoted notice under SCA; the contract goes TERMINATING and pays after the notice period",
    )
    @Authorize(action = "pension.exit.terminate", resource = "#contractId")
    suspend fun signTermination(
        @PathParam("contractId") contractId: UUID,
        @PathParam("noticeId") noticeId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: SignRequest?,
    ): TerminationResponse {
        val key = requireKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        return TerminationResponse.from(
            terminations.sign(
                SignTerminationCommand(
                    participant(party),
                    contractId,
                    noticeId,
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
    @Authorize(action = "pension.exit.inspect", resource = "#contractId")
    suspend fun getTermination(
        @PathParam("contractId") contractId: UUID,
        @PathParam("noticeId") noticeId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
    ) = TerminationResponse.from(terminations.get(reader(party), contractId, noticeId))

    @POST
    @Path("/payouts/quote")
    @Operation(summary = "Binding payout preview for a form (lump sum, annuity, phased, fixed period, partial)")
    @Authorize(action = "pension.exit.payout", resource = "#contractId")
    suspend fun quotePayout(
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        @PathParam("contractId") contractId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
        request: PayoutQuoteRequest?,
    ): Response {
        requireIdempotencyKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        val quoted = payouts.quote(
            PayoutQuoteCommand(
                participant(party),
                contractId,
                requireNotNull(body.form) {
                    "form is required"
                },
                body.amount,
                body.months,
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
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: SignRequest?,
    ): PayoutResponse {
        val key = requireKey(idempotencyKey)
        val body = requireNotNull(request) { "request body is required" }
        return PayoutResponse.from(
            payouts.confirm(
                ConfirmPayoutCommand(
                    participant(party),
                    contractId,
                    payoutId,
                    requireNotNull(body.scaChallengeId) { "scaChallengeId is required" },
                    requireNotNull(body.payoutIban) { "payoutIban is required" },
                    key,
                ),
            ),
        )
    }

    @PUT
    @Path("/payouts/{payoutId}/account")
    @Operation(
        summary = "Move the remaining scheduled payments to another verified own account (SCA-bound)",
    )
    @Authorize(action = "pension.exit.payout", resource = "#contractId")
    suspend fun changePayoutAccount(
        @PathParam("contractId") contractId: UUID,
        @PathParam("payoutId") payoutId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
        request: SignRequest?,
    ): PayoutResponse {
        val body = requireNotNull(request) { "request body is required" }
        return PayoutResponse.from(
            payouts.changePayoutAccount(
                ChangePayoutAccountCommand(
                    participant(party),
                    contractId,
                    payoutId,
                    requireNotNull(body.scaChallengeId) { "scaChallengeId is required" },
                    requireNotNull(body.payoutIban) { "payoutIban is required" },
                ),
            ),
        )
    }

    @GET
    @Path("/payouts/{payoutId}")
    @Operation(summary = "One payout with its schedule")
    @Authorize(action = "pension.exit.inspect", resource = "#contractId")
    suspend fun getPayout(
        @PathParam("contractId") contractId: UUID,
        @PathParam("payoutId") payoutId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
    ) = PayoutResponse.from(payouts.get(reader(party), contractId, payoutId))

    @GET
    @Path("/payouts/{payoutId}/statement")
    @Operation(summary = "Payout statement: quoted, paid, withheld, outstanding")
    @Authorize(action = "pension.exit.inspect", resource = "#contractId")
    suspend fun statement(
        @PathParam("contractId") contractId: UUID,
        @PathParam("payoutId") payoutId: UUID,
        @HeaderParam(PARTY_HEADER) party: String?,
    ) = PayoutStatementResponse.from(payouts.get(reader(party), contractId, payoutId))

    companion object {
        const val PARTY_HEADER = ContractAccessGuard.PARTY_HEADER
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
    }
}

/** Only the exceptions this slice owns; `IllegalArgument/State` are mapped by libs-runtime and S1. */
class ExitExceptionMappers {
    @ServerExceptionMapper
    fun notFound(e: ExitNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()

    /** A write lost an optimistic-lock race (S8): the caller re-reads and retries; nothing was changed. */
    @ServerExceptionMapper
    fun concurrent(e: com.openbank.pension.application.exit.ExitConcurrentUpdateException): Response =
        Response.status(Response.Status.CONFLICT).header(RETRY_AFTER, RETRY_AFTER_SECONDS)
            .entity(mapOf("error" to e.message, "retryable" to true)).build()

    @Suppress("UnusedParameter") // the exception TYPE selects the mapper; its message is not echoed
    @ServerExceptionMapper
    fun staleRow(e: org.hibernate.StaleStateException): Response = concurrentWrite()

    @Suppress("UnusedParameter")
    @ServerExceptionMapper
    fun optimisticLock(e: jakarta.persistence.OptimisticLockException): Response = concurrentWrite()

    /** Two writers raced at flush (@Version): the loser changed nothing and may re-read and retry. */
    private fun concurrentWrite(): Response = Response.status(
        Response.Status.CONFLICT,
    ).header(RETRY_AFTER, RETRY_AFTER_SECONDS)
        .entity(mapOf("error" to "the record changed concurrently; retry", "retryable" to true)).build()

    private companion object {
        /** A lost optimistic-lock race is transient: the client re-reads and retries after this. */
        const val RETRY_AFTER = "Retry-After"
        const val RETRY_AFTER_SECONDS = "1"
    }

    @ServerExceptionMapper
    fun forbidden(e: ExitForbiddenException): Response =
        Response.status(Response.Status.FORBIDDEN).entity(mapOf("error" to e.message)).build()
}
