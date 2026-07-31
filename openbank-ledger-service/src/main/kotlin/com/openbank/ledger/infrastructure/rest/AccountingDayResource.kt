// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.port.`in`.AccountingDayUseCase
import com.openbank.ledger.application.port.`in`.GetAccountingDayQuery
import com.openbank.ledger.application.port.`in`.ListAccountingDaysQuery
import com.openbank.ledger.application.port.`in`.OpenAccountingDayCommand
import com.openbank.ledger.application.port.`in`.TransitionAccountingDayCommand
import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
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
import java.time.LocalDate
import java.util.UUID

/**
 * The accounting-day authority (ADR-0207 D2).
 *
 * Access control follows the ledger book-of-record convention (K7 / ADR-0018, as
 * [YearCloseResource]): reads are gated to service/auditor/viewer/operator/admin; every state
 * change is operator-only, like posting a journal or attesting a year.
 *
 * These endpoints are the **operator** surface. Other services do not poll them per posting —
 * they react to `AccountingDayTransitioned` on the outbox (ADR-0207 D4), so that ledger-service
 * does not become a hard availability dependency of every money-path service.
 */
@Path("/api/v1/ledger/accounting-days")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AccountingDay", description = "Accounting-day authority and OPEN/CUTOFF/TIED_OUT/LOCKED state")
class AccountingDayResource(private val accountingDayUseCase: AccountingDayUseCase) {

    // SecurityIdentity, not @Context SecurityContext: in a Kotlin `suspend` resource method the
    // @Context principal does not reliably resolve to the bearer JsonWebToken (see YearCloseResource).
    @Inject
    lateinit var identity: SecurityIdentity

    @GET
    @Path("/current")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "")
    @Operation(summary = "The current accounting day per the business-date authority (ADR-0207 D1)")
    suspend fun current(): Response {
        val businessDate = accountingDayUseCase.currentBusinessDate()
        val record = runCatching { accountingDayUseCase.get(GetAccountingDayQuery(businessDate)) }.getOrNull()
        return Response.ok(
            CurrentAccountingDayResponse(
                businessDate = businessDate.toString(),
                status = record?.status?.name,
                opened = record != null,
            ),
        ).build()
    }

    @GET
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "")
    @Operation(summary = "List accounting days in [from, to]")
    suspend fun list(@QueryParam("from") from: String?, @QueryParam("to") to: String?): Response {
        val fromDate = parseDate(from, "from")
        val toDate = parseDate(to, "to")
        val days = accountingDayUseCase.list(ListAccountingDaysQuery(fromDate, toDate))
        return Response.ok(days.map { it.toResponse() }).build()
    }

    @GET
    @Path("/{businessDate}")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "#businessDate")
    @Operation(summary = "Get one accounting day's state")
    suspend fun get(@PathParam("businessDate") businessDate: String): Response {
        val record = accountingDayUseCase.get(GetAccountingDayQuery(parseDate(businessDate, "businessDate")))
        return Response.ok(record.toResponse()).build()
    }

    @POST
    @Path("/{businessDate}")
    @RolesAllowed(Roles.OPERATOR)
    @Authorize(action = "ledger.create", resource = "#businessDate")
    @Operation(summary = "Open an accounting day for posting (OPEN)")
    suspend fun open(@PathParam("businessDate") businessDate: String): Response {
        val record = accountingDayUseCase.open(
            OpenAccountingDayCommand(parseDate(businessDate, "businessDate"), openedBy = actingPrincipal()),
        )
        return Response.status(Response.Status.CREATED).entity(record.toResponse()).build()
    }

    @POST
    @Path("/{businessDate}/transitions/{to}")
    @RolesAllowed(Roles.OPERATOR)
    @Authorize(action = "ledger.approve", resource = "#businessDate")
    @Operation(
        summary = "Advance an accounting day one step (OPEN → CUTOFF → TIED_OUT → LOCKED); " +
            "monotonic, never backwards",
    )
    suspend fun transition(
        @PathParam("businessDate") businessDate: String,
        @PathParam("to") to: String,
    ): Response {
        val target = runCatching { AccountingDayStatus.valueOf(to.uppercase()) }.getOrElse {
            throw WebApplicationException(
                "Unknown accounting-day status '$to'; expected one of ${AccountingDayStatus.entries.map { s -> s.name }}",
                Response.Status.BAD_REQUEST,
            )
        }
        val record = accountingDayUseCase.transition(
            TransitionAccountingDayCommand(
                businessDate = parseDate(businessDate, "businessDate"),
                to = target,
                transitionedBy = actingPrincipal(),
            ),
        )
        return Response.ok(record.toResponse()).build()
    }

    private fun parseDate(value: String?, field: String): LocalDate {
        if (value.isNullOrBlank()) {
            throw WebApplicationException("$field is required (ISO-8601 date)", Response.Status.BAD_REQUEST)
        }
        return runCatching { LocalDate.parse(value) }.getOrElse {
            throw WebApplicationException("$field is not an ISO-8601 date: $value", Response.Status.BAD_REQUEST)
        }
    }

    /** The acting principal for the audit trail — the OIDC `sub`, else the principal name. */
    private fun actingPrincipal(): String {
        val principal = identity.principal
        val subject = (principal as? JsonWebToken)?.subject ?: principal?.name
        if (subject.isNullOrBlank()) {
            throw WebApplicationException(
                "Cannot resolve the acting principal from the bearer token",
                Response.Status.UNAUTHORIZED,
            )
        }
        return subject
    }
}

data class CurrentAccountingDayResponse(val businessDate: String, val status: String?, val opened: Boolean)

data class AccountingDayResponse(
    val id: UUID,
    val businessDate: String,
    val status: String,
    val acceptsPostings: Boolean,
    val openedAt: String,
    val openedBy: String,
    val cutoffAt: String?,
    val tiedOutAt: String?,
    val lockedAt: String?,
    val lastTransitionBy: String?,
    val version: Long,
)

private fun AccountingDayRecord.toResponse() = AccountingDayResponse(
    id = id,
    businessDate = businessDate.toString(),
    status = status.name,
    acceptsPostings = acceptsPostings,
    openedAt = openedAt.toString(),
    openedBy = openedBy,
    cutoffAt = cutoffAt?.toString(),
    tiedOutAt = tiedOutAt?.toString(),
    lockedAt = lockedAt?.toString(),
    lastTransitionBy = lastTransitionBy,
    version = version,
)
