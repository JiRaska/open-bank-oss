// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.statement.application.port.`in`.AdHocExportUseCase
import com.openbank.statement.application.port.`in`.ClosePeriodUseCase
import com.openbank.statement.application.port.`in`.ListStatementsUseCase
import com.openbank.statement.application.port.`in`.RenderStatementUseCase
import com.openbank.statement.application.usecase.ReconciliationException
import com.openbank.statement.application.usecase.StatementNotFoundException
import com.openbank.statement.domain.model.StatementFormat
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.LocalDate
import java.util.UUID

@Path("/api/v1/statements")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Statements", description = "Account statements: per-pocket period-close + on-demand render (ADR-0035)")
@RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_API")
class StatementResource(
    private val closePeriod: ClosePeriodUseCase,
    private val renderStatement: RenderStatementUseCase,
    private val listStatements: ListStatementsUseCase,
    private val adHocExport: AdHocExportUseCase,
) {

    @POST
    @Path("/{accountId}/close")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "statement.close", resource = "#accountId")
    @Operation(summary = "Close a month for every pocket of an account (assigns sequences, reconciles fail-closed)")
    fun close(
        @PathParam("accountId") accountId: UUID,
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
    ): Uni<Response> {
        // #3624 — the period being closed has no defensible default: a close assigns legal
        // sequences and is idempotent on (account, currency, period), so guessing the range would
        // write the wrong statement rather than fail. This handler is a plain `fun`, so Kotlin
        // emitted Intrinsics.checkNotNullParameter at bytecode offset 0 and an omitted ?from= threw
        // NPE before the body ran — a guard written here would have been dead code. Nullable makes
        // it reachable; libs-runtime maps IllegalArgumentException to 400.
        requireNotNull(from) { FROM_REQUIRED }
        requireNotNull(to) { TO_REQUIRED }
        return closePeriod.closeMonth(accountId, LocalDate.parse(from), LocalDate.parse(to))
            .map { Response.ok(it).build() }
            .onFailure(ReconciliationException::class.java)
            .recoverWithItem { e ->
                Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
            }
    }

    @GET
    @Path("/{accountId}")
    @Authorize(action = "statement.list", resource = "#accountId")
    @Operation(summary = "List the retained period-close records for an account")
    fun list(@PathParam("accountId") accountId: UUID): Uni<Response> =
        listStatements.list(accountId).map { Response.ok(it).build() }

    @GET
    @Path("/{accountId}/{currency}/{legalSequence}")
    @Authorize(action = "statement.read", resource = "#accountId")
    @Operation(summary = "Render a closed statement on demand (camt.053 / MT940 / PDF) — nothing is stored")
    fun render(
        @PathParam("accountId") accountId: UUID,
        @PathParam("currency") currency: String,
        @PathParam("legalSequence") legalSequence: Long,
        @QueryParam("format") @DefaultValue("PDF") format: String,
    ): Uni<Response> = renderStatement.render(accountId, currency, legalSequence, parseFormat(format))
        .map { rendered -> Response.ok(rendered.body).type(rendered.contentType).build() }
        .onFailure(StatementNotFoundException::class.java)
        .recoverWithItem { e ->
            Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()
        }

    @GET
    @Path("/{accountId}/{currency}/export")
    @Authorize(action = "statement.export", resource = "#accountId")
    @Operation(summary = "Ad-hoc, non-sequenced informational export for an arbitrary date range (ADR-0035 §F.3)")
    fun export(
        @PathParam("accountId") accountId: UUID,
        @PathParam("currency") currency: String,
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
        @QueryParam("format") @DefaultValue("PDF") format: String,
    ): Uni<Response> {
        // #3624 — `format` is safe (it carries @DefaultValue, so JAX-RS never injects null); the
        // date range is the whole meaning of an ad-hoc export and was a 500 when omitted.
        requireNotNull(from) { FROM_REQUIRED }
        requireNotNull(to) { TO_REQUIRED }
        return adHocExport
            .export(accountId, currency, LocalDate.parse(from), LocalDate.parse(to), parseFormat(format))
            .map { rendered -> Response.ok(rendered.body).type(rendered.contentType).build() }
    }

    private fun parseFormat(raw: String): StatementFormat =
        StatementFormat.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: StatementFormat.PDF

    private companion object {
        const val FROM_REQUIRED = "query parameter 'from' is required"
        const val TO_REQUIRED = "query parameter 'to' is required"
    }
}
