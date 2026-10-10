// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.treasury.application.port.`in`.PortfolioStatementUseCase
import com.openbank.treasury.application.port.out.StoredPortfolioStatement
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.infrastructure.iso20022.Semt002Statements
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.UUID

/**
 * The custodian's period-end statement of holdings (ADR-0337 amendment 2026-10-10), the source of
 * record for ČNB PSP 34-12 PS. Upload mirrors the nostro camt.053 upload (same Idempotency-Key
 * contract, same evidence: sha256 of the exact bytes and who supplied them); the period-end read is
 * tax-reporting's contract. Nothing here posts.
 *
 * `@RolesAllowed` is the coarse gate; OPA decides (`treasury.portfolio.read` is excluded from base
 * operator-read-any and compliance-read-any, so only treasury staff and tax-reporting's own client
 * reach it — treasury_rest_ext.rego).
 *
 * NOTE the annotation order: `@Path` sits immediately above `class` (#3371).
 */
@Tag(name = "Portfolio", description = "Custodian semt.002 statements of holdings (ADR-0337)")
@Path("/api/v1/treasury/portfolio")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, DEALER, APPROVER, SENIOR_APPROVER, Roles.API)
class PortfolioResource {

    @Inject
    lateinit var portfolio: PortfolioStatementUseCase

    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    @Path("/statements")
    @Consumes(MediaType.APPLICATION_XML, MediaType.TEXT_XML)
    @RolesAllowed(APPROVER)
    @Operation(summary = "Upload a custodian's semt.002 period-end statement of holdings")
    @Authorize(action = "treasury.portfolio.upload")
    suspend fun upload(@HeaderParam("Idempotency-Key") key: String?, body: ByteArray?): Response {
        val idempotencyKey = requireNotNull(key?.takeIf { it.isNotBlank() }) { "header 'Idempotency-Key' is required" }
        require(idempotencyKey.length <= MAX_KEY_LENGTH) { "header 'Idempotency-Key' is longer than $MAX_KEY_LENGTH" }
        val xml = requireNotNull(body?.takeIf { it.isNotEmpty() }) { "a semt.002 XML body is required" }
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(xml))
        val stored = portfolio.upload(
            Semt002Statements.parse(xml),
            sha256,
            idempotencyKey,
            Actor.fromPrincipalName(identity.principal.name),
        )
        return Response.status(Response.Status.CREATED).entity(PortfolioStatementResponse.from(stored)).build()
    }

    @GET
    @Path("/period-end")
    @Operation(summary = "The portfolio at exactly that date; 409 when no statement of holdings is stored for it")
    @Authorize(action = "treasury.portfolio.read")
    suspend fun periodEnd(@QueryParam("date") date: String?): PeriodEndPortfolioResponse =
        PeriodEndPortfolioResponse.from(portfolio.periodEnd(date(date)))

    @GET
    @Path("/statements")
    @Operation(summary = "Every version stored for a date, oldest first: the correction trail")
    @Authorize(action = "treasury.portfolio.read")
    suspend fun versions(@QueryParam("date") date: String?): List<PortfolioStatementResponse> =
        portfolio.versions(date(date)).map(PortfolioStatementResponse::from)

    private fun date(raw: String?): LocalDate {
        val value = requireNotNull(raw?.takeIf { it.isNotBlank() }) { "query parameter 'date' is required" }
        return try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("query parameter 'date' must be YYYY-MM-DD, was '$value'", e)
        }
    }

    private companion object {
        const val MAX_KEY_LENGTH = 128
    }
}

/** One position. Decimals as STRINGS (ADR-0337 amendment D2): no client parses a figure as a double. */
data class PortfolioPositionResponse(
    val instrumentClass: String,
    val isin: String,
    val cfi: String,
    val quantity: String,
    val valuation: String,
    val valuationCurrency: String,
)

/** The agreed tax-reporting contract: `asOf`, `currency`, `positions`. */
data class PeriodEndPortfolioResponse(
    val asOf: LocalDate,
    val entity: String,
    val currency: String,
    val statementUuid: UUID,
    val statementId: String?,
    val version: Int,
    val positions: List<PortfolioPositionResponse>,
) {
    companion object {
        fun from(s: StoredPortfolioStatement) = PeriodEndPortfolioResponse(
            asOf = s.snapshot.statementDate,
            entity = s.snapshot.entity,
            currency = s.snapshot.currency,
            statementUuid = s.id,
            statementId = s.snapshot.statementId,
            version = s.version,
            positions = s.snapshot.positions.sortedBy { it.isin }.map {
                PortfolioPositionResponse(
                    instrumentClass = it.instrumentClass,
                    isin = it.isin,
                    cfi = it.cfi,
                    quantity = it.quantity.stripTrailingZeros().toPlainString(),
                    valuation = it.valuation.toPlainString(),
                    valuationCurrency = it.valuationCurrency,
                )
            },
        )
    }
}

data class PortfolioStatementResponse(
    val id: UUID,
    val entity: String,
    val statementId: String?,
    val safekeepingAccount: String,
    val statementDate: LocalDate,
    val currency: String,
    val version: Int,
    val supersedes: UUID?,
    val supersededBy: UUID?,
    val supersededAt: Instant?,
    val positionCount: Int,
    val sha256: String,
    val uploadedBy: String,
    val uploadedAt: Instant,
) {
    companion object {
        fun from(s: StoredPortfolioStatement) = PortfolioStatementResponse(
            id = s.id,
            entity = s.snapshot.entity,
            statementId = s.snapshot.statementId,
            safekeepingAccount = s.snapshot.safekeepingAccount,
            statementDate = s.snapshot.statementDate,
            currency = s.snapshot.currency,
            version = s.version,
            supersedes = s.supersedes,
            supersededBy = s.supersededBy,
            supersededAt = s.supersededAt,
            positionCount = s.snapshot.positions.size,
            sha256 = s.sha256,
            uploadedBy = s.uploadedBy,
            uploadedAt = s.uploadedAt,
        )
    }
}
