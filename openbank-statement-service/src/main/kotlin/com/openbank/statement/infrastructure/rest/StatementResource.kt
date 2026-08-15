// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.statement.application.port.`in`.AdHocExportUseCase
import com.openbank.statement.application.port.`in`.ClosePeriodUseCase
import com.openbank.statement.application.port.`in`.ListStatementsUseCase
import com.openbank.statement.application.port.`in`.RenderStatementDocumentUseCase
import com.openbank.statement.application.port.`in`.RenderStatementUseCase
import com.openbank.statement.application.port.`in`.RestatePeriodUseCase
import com.openbank.statement.application.port.`in`.SummarizeStatementUseCase
import com.openbank.statement.application.port.out.DocumentServiceException
import com.openbank.statement.application.usecase.NoClosedPeriodToRestateException
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
    private val restatePeriod: RestatePeriodUseCase,
    private val summarizeStatement: SummarizeStatementUseCase,
    private val renderStatementDocument: RenderStatementDocumentUseCase,
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
    ): Uni<Response> = closePeriod.closeMonth(accountId, requiredDate(from, "from"), requiredDate(to, "to"))
        .map { Response.ok(it).build() }
        .onFailure(ReconciliationException::class.java)
        .recoverWithItem { e ->
            Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
        }

    @POST
    @Path("/{accountId}/{currency}/restate")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "statement.restate", resource = "#accountId")
    @Operation(
        summary = "Restate a closed period after a data correction (ADR-0035 §D) — issues a NEW " +
            "sequenced close referencing the superseded one; never edits the existing record",
    )
    fun restate(
        @PathParam("accountId") accountId: UUID,
        @PathParam("currency") currency: String,
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
    ): Uni<Response> =
        restatePeriod.restatePocketPeriod(accountId, currency, requiredDate(from, "from"), requiredDate(to, "to"))
            .map { Response.ok(it).build() }
            .onFailure(NoClosedPeriodToRestateException::class.java)
            .recoverWithItem { e ->
                Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()
            }
            .onFailure(ReconciliationException::class.java)
            .recoverWithItem { e ->
                Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
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
    @Path("/{accountId}/{currency}/{legalSequence}/summary")
    @Authorize(action = "statement.read", resource = "#accountId")
    @Operation(
        summary = "Render a closed statement on demand as structured JSON (period, balances, itemized entries)",
        description = "The JSON twin of render — same closed period, no camt.053/MT940/PDF projection. " +
            "Added for callers (e.g. the MCP statement-query tool) that need to reason over the data " +
            "directly rather than parse a rendered document. Nothing is stored.",
    )
    fun summary(
        @PathParam("accountId") accountId: UUID,
        @PathParam("currency") currency: String,
        @PathParam("legalSequence") legalSequence: Long,
    ): Uni<Response> = summarizeStatement.summary(accountId, currency, legalSequence)
        .map { model -> Response.ok(model).build() }
        .onFailure(StatementNotFoundException::class.java)
        .recoverWithItem { e ->
            Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()
        }

    @GET
    @Path("/{accountId}/{currency}/{legalSequence}/document")
    // Reuses the "statement.read" action (not a new "statement.document" one): rules.yaml's
    // role_action_matrix only registers statement.list/statement.read, and this endpoint is the same
    // authorization decision as render() — read access to this account's closed statements — just a
    // different output format. A new action string would need its own matrix registration
    // (out of scope: only openbank-statement-service/ and this PR's threat model may change).
    @Authorize(action = "statement.read", resource = "#accountId")
    @Operation(
        summary = "Render the customer-facing styled statement via document-service's non-persisting " +
            "preview endpoint (ADR-0248) — synchronous, on customer request only; nothing is stored",
    )
    fun document(
        @PathParam("accountId") accountId: UUID,
        @PathParam("currency") currency: String,
        @PathParam("legalSequence") legalSequence: Long,
        @QueryParam("locale") @DefaultValue("cs") locale: String,
    ): Uni<Response> = renderStatementDocument.renderDocument(accountId, currency, legalSequence, locale)
        .map { rendered -> Response.ok(rendered.body).type(rendered.contentType).build() }
        .onFailure(StatementNotFoundException::class.java)
        .recoverWithItem { e ->
            Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()
        }
        .onFailure(DocumentServiceException::class.java)
        .recoverWithItem { e ->
            Response.status(Response.Status.BAD_GATEWAY).entity(mapOf("error" to e.message)).build()
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
    ): Uni<Response> = adHocExport.export(
        accountId,
        currency,
        requiredDate(from, "from"),
        requiredDate(to, "to"),
        parseFormat(format),
    )
        .map { rendered -> Response.ok(rendered.body).type(rendered.contentType).build() }

    private fun parseFormat(raw: String): StatementFormat =
        StatementFormat.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: StatementFormat.PDF

    /**
     * `from`/`to` are genuinely required — a period is never guessed.
     *
     * The parameter MUST be declared nullable for this to be reachable at all: JAX-RS injects `null`
     * for an absent query parameter, and on a non-`suspend` handler a non-nullable Kotlin type
     * compiles to an `Intrinsics.checkNotNullParameter` at offset 0, so the NPE lands before the
     * first statement of the body and the generic mapper answers 500 (issue #3104/#3624). With the
     * type nullable, `requireNotNull` runs and libs-runtime's `IllegalArgumentExceptionMapper`
     * renders 400 + `ApiError`.
     *
     * No `@DefaultValue` here on purpose: a period close assigns LEGAL SEQUENCES and is idempotent
     * on `(account, currency, period)`, so a guessed "last month" range does not fail — it writes
     * the WRONG statement. `format` on the same handlers genuinely is defaultable and keeps its
     * `@DefaultValue`.
     */
    private fun requiredDate(raw: String?, name: String): LocalDate {
        requireNotNull(raw) { "query parameter '$name' is required" }
        return LocalDate.parse(raw)
    }
}
