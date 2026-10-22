// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.port.`in`.CloseParticipationUseCase
import com.openbank.ledger.application.port.`in`.ReportParticipationCommand
import com.openbank.ledger.application.port.`in`.WaiveParticipationCommand
import com.openbank.ledger.domain.model.CloseParticipationRecord
import com.openbank.ledger.domain.model.ParticipationOutcome
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.libs.close.CloseOutcome
import com.openbank.libs.close.ParticipantDayStatus
import com.openbank.libs.security.Roles
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.LocalDate

/**
 * Day-close participation (ADR-0342).
 *
 * - `PUT …/{participant}` — a product service reports its outcome for the day. Admitted under
 *   `ledger.create`, the grant each participant already holds to post journals; the use case then
 *   narrows it to the ONE service-account declared for that participant.
 * - `PUT …/{participant}/waiver` — an operator records that a missing participant is not required
 *   for this day. `ledger.approve`, like every other day transition.
 * - `GET` — the day's completeness: every declared participant with its latest record, or MISSING.
 *   `complete` is false while any participant is missing OR its latest report is REFUSED.
 */
@Path("/api/v1/ledger/accounting-days/{businessDate}/participants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AccountingDay", description = "Accounting-day authority and OPEN/CUTOFF/TIED_OUT/LOCKED state")
class CloseParticipationResource(private val useCase: CloseParticipationUseCase) {

    @Inject
    lateinit var identity: SecurityIdentity

    @GET
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "#businessDate")
    @Operation(summary = "Day-close completeness: each declared participant's latest report, or MISSING")
    suspend fun list(@PathParam("businessDate") businessDate: String): Response {
        val completeness = useCase.completeness(parseDate(businessDate))
        val rows = completeness.expected.sorted().map { name ->
            completeness.reported[name]?.toResponse() ?: ParticipationResponse.missing(name)
        } + completeness.reported.values
            .filter { it.participant !in completeness.expected }
            .map { it.toResponse() }
        return Response.ok(
            CloseCompletenessResponse(
                businessDate = completeness.businessDate.toString(),
                complete = completeness.complete,
                missing = completeness.missing.sorted(),
                refused = completeness.refused.sorted(),
                participants = rows,
            ),
        ).build()
    }

    @PUT
    @Path("/{participant}")
    @RolesAllowed(Roles.API)
    @Authorize(action = "ledger.create", resource = "#participant")
    @Operation(summary = "Report a product service's day-close outcome (RAN, SKIPPED or REFUSED)")
    suspend fun report(
        @PathParam("businessDate") businessDate: String,
        @PathParam("participant") participant: String,
        // Nullable: JAX-RS injects null for an absent body and a suspend fun has no intrinsic.
        body: ReportParticipationRequest?,
    ): Response {
        val request = body ?: throw WebApplicationException("request body is required", Response.Status.BAD_REQUEST)
        val outcome = parseOutcome(request.outcome)
        val saved = useCase.report(
            ReportParticipationCommand(
                businessDate = parseDate(businessDate),
                participant = participant,
                outcome = outcome,
                journalsPosted = request.journalsPosted ?: 0,
                itemsProcessed = request.itemsProcessed ?: 0,
                reason = request.reason,
                callerPrincipal = ServiceAccountIdentity.verifiedPrincipalName(identity),
            ),
        )
        return Response.ok(saved.toResponse()).build()
    }

    @PUT
    @Path("/{participant}/waiver")
    @RolesAllowed(Roles.OPERATOR)
    @Authorize(action = "ledger.approve", resource = "#participant")
    @Operation(summary = "Waive a missing participant for this day; the reason is mandatory and recorded")
    suspend fun waive(
        @PathParam("businessDate") businessDate: String,
        @PathParam("participant") participant: String,
        body: WaiveParticipationRequest?,
    ): Response {
        // A waiver is a human exception even while OPA runs in advisory mode.
        if (!ServiceAccountIdentity.isVerifiedPerson(identity)) {
            throw WebApplicationException(
                "A human operator is required to waive a participant",
                Response.Status.FORBIDDEN,
            )
        }
        val saved = useCase.waive(
            WaiveParticipationCommand(
                businessDate = parseDate(businessDate),
                participant = participant,
                reason = body?.reason,
                waivedBy = actingPrincipal(),
            ),
        )
        return Response.ok(saved.toResponse()).build()
    }

    private fun parseDate(value: String): LocalDate = runCatching { LocalDate.parse(value) }.getOrElse {
        throw WebApplicationException("businessDate is not an ISO-8601 date: $value", Response.Status.BAD_REQUEST)
    }

    private fun parseOutcome(value: String?): ParticipationOutcome {
        val allowed = listOf(ParticipationOutcome.RAN, ParticipationOutcome.SKIPPED, ParticipationOutcome.REFUSED)
        return allowed.firstOrNull { it.name == value?.uppercase() } ?: throw WebApplicationException(
            "outcome must be one of ${allowed.joinToString { it.name }}",
            Response.Status.BAD_REQUEST,
        )
    }

    private fun actingPrincipal(): String {
        val subject = runCatching { (identity.principal as? JsonWebToken)?.subject }.getOrNull()
        if (subject.isNullOrBlank()) {
            throw WebApplicationException("Cannot resolve the acting principal", Response.Status.UNAUTHORIZED)
        }
        return subject
    }
}

data class ReportParticipationRequest(
    val outcome: String?,
    val journalsPosted: Int?,
    val itemsProcessed: Int?,
    val reason: String?,
)

data class WaiveParticipationRequest(val reason: String?)

data class ParticipationResponse(
    val participant: String,
    val status: ParticipantDayStatus,
    val journalsPosted: Int,
    val itemsProcessed: Int,
    val reason: String?,
    val reportedBy: String?,
    val reportedAt: String?,
    val late: Boolean,
) {
    companion object {
        fun missing(participant: String) =
            ParticipationResponse(participant, ParticipantDayStatus.MISSING, 0, 0, null, null, null, late = false)
    }
}

data class CloseCompletenessResponse(
    val businessDate: String,
    val complete: Boolean,
    val missing: List<String>,
    val refused: List<String>,
    val participants: List<ParticipationResponse>,
)

private fun CloseParticipationRecord.toResponse() = ParticipationResponse(
    participant = participant,
    status = when (outcome) {
        is ParticipationOutcome.Reported -> when (outcome.value) {
            CloseOutcome.RAN -> ParticipantDayStatus.RAN
            CloseOutcome.SKIPPED -> ParticipantDayStatus.SKIPPED
            CloseOutcome.REFUSED -> ParticipantDayStatus.REFUSED
        }
        ParticipationOutcome.Waived -> ParticipantDayStatus.WAIVED
    },
    journalsPosted = journalsPosted,
    itemsProcessed = itemsProcessed,
    reason = reason,
    reportedBy = reportedBy,
    reportedAt = reportedAt.toString(),
    late = late,
)
