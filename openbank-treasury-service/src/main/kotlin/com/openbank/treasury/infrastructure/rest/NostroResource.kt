// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.treasury.application.port.`in`.NostroReconciliationUseCase
import com.openbank.treasury.application.port.out.StoredStatement
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.LedgerNostroLine
import com.openbank.treasury.domain.model.NostroMatch
import com.openbank.treasury.domain.model.NostroReconciliation
import com.openbank.treasury.domain.model.StatementEntry
import com.openbank.treasury.infrastructure.iso20022.Camt053Parser
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
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID

/**
 * Nostro reconciliation (ADR-0315, #10896): upload a correspondent's camt.053 for a configured
 * nostro account, then read the comparison with the ledger's nostro GL. Nothing here posts — an
 * unmatched item is listed for a person, never auto-booked.
 *
 * NOTE the annotation order: `@Path` sits immediately above `class` (#3371).
 */
@Tag(name = "Nostro reconciliation", description = "camt.053 statements vs the ledger nostro GL (ADR-0315)")
@Path("/api/v1/treasury/nostro")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, DEALER, APPROVER, SENIOR_APPROVER)
class NostroResource {

    @Inject
    lateinit var nostro: NostroReconciliationUseCase

    @Inject
    lateinit var identity: SecurityIdentity

    @POST
    @Path("/statements")
    @Consumes(MediaType.APPLICATION_XML, MediaType.TEXT_XML)
    @RolesAllowed(APPROVER)
    @Operation(summary = "Upload a camt.053 end-of-day statement for a configured nostro account")
    @Authorize(action = "treasury.nostro.upload")
    suspend fun upload(@HeaderParam("Idempotency-Key") key: String?, body: ByteArray?): Response {
        val idempotencyKey = requireNotNull(key?.takeIf { it.isNotBlank() }) { "header '$IDEMPOTENCY_KEY' is required" }
        require(idempotencyKey.length <= MAX_KEY_LENGTH) { "header '$IDEMPOTENCY_KEY' is longer than $MAX_KEY_LENGTH" }
        val xml = requireNotNull(body?.takeIf { it.isNotEmpty() }) { "a camt.053 XML body is required" }
        val statement = Camt053Parser.parse(xml)
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(xml))
        val stored = nostro.upload(statement, sha256, idempotencyKey, Actor.fromPrincipalName(identity.principal.name))
        return Response.status(Response.Status.CREATED).entity(StatementUploadResponse.from(stored)).build()
    }

    @GET
    @Path("/statements/{id}/reconciliation")
    @Operation(summary = "Statement vs ledger nostro GL: matches, unmatched on both sides, balance differences")
    @Authorize(action = "treasury.nostro.read", resource = "#id")
    suspend fun reconciliation(@PathParam("id") id: UUID): ReconciliationResponse =
        ReconciliationResponse.from(id, nostro.reconcile(id))

    private companion object {
        const val MAX_KEY_LENGTH = 128
    }
}

data class StatementUploadResponse(
    val id: UUID,
    val statementId: String,
    val iban: String,
    val glCode: String,
    val currency: String,
    val statementDate: LocalDate,
    val openingBalance: BigDecimal,
    val closingBalance: BigDecimal,
    val entryCount: Int,
    val sha256: String,
    val uploadedBy: String,
    val uploadedAt: Instant,
) {
    companion object {
        fun from(s: StoredStatement) = StatementUploadResponse(
            id = s.id,
            statementId = s.statement.statementId,
            iban = s.statement.iban,
            glCode = s.glCode,
            currency = s.statement.currency,
            statementDate = s.statement.statementDate,
            openingBalance = s.statement.openingBalance,
            closingBalance = s.statement.closingBalance,
            entryCount = s.statement.entries.size,
            sha256 = s.sha256,
            uploadedBy = s.uploadedBy,
            uploadedAt = s.uploadedAt,
        )
    }
}

data class StatementEntryResponse(
    val sequence: Int,
    val amount: BigDecimal,
    val currency: String,
    val direction: String,
    val bookingDate: LocalDate,
    val reference: String?,
) {
    companion object {
        fun from(e: StatementEntry) =
            StatementEntryResponse(e.sequence, e.amount, e.currency, e.direction.name, e.bookingDate, e.reference)
    }
}

data class LedgerLineResponse(
    val journalId: UUID,
    val lineId: UUID,
    val transactionId: UUID,
    val entryDate: LocalDate,
    val side: String,
    val amount: BigDecimal,
    val currency: String,
    val description: String?,
) {
    companion object {
        fun from(l: LedgerNostroLine) = LedgerLineResponse(
            l.journalId,
            l.lineId,
            l.transactionId,
            l.entryDate,
            l.side.name,
            l.amount,
            l.currency,
            l.description,
        )
    }
}

data class MatchResponse(val matchType: String, val entry: StatementEntryResponse, val ledgerLine: LedgerLineResponse) {
    companion object {
        fun from(m: NostroMatch) =
            MatchResponse(m.type.name, StatementEntryResponse.from(m.entry), LedgerLineResponse.from(m.line))
    }
}

data class ReconciliationResponse(
    val statementUuid: UUID,
    val statementId: String,
    val iban: String,
    val glCode: String,
    val currency: String,
    val statementDate: LocalDate,
    val statementOpeningBalance: BigDecimal,
    val ledgerOpeningBalance: BigDecimal?,
    val openingDifference: BigDecimal?,
    val statementClosingBalance: BigDecimal,
    val ledgerClosingBalance: BigDecimal?,
    val closingDifference: BigDecimal?,
    val balanceNotStated: String?,
    val reconciled: Boolean?,
    val matches: List<MatchResponse>,
    val unmatchedStatementEntries: List<StatementEntryResponse>,
    val unmatchedLedgerLines: List<LedgerLineResponse>,
) {
    companion object {
        fun from(id: UUID, r: NostroReconciliation) = ReconciliationResponse(
            statementUuid = id,
            statementId = r.statement.statementId,
            iban = r.statement.iban,
            glCode = r.glCode,
            currency = r.statement.currency,
            statementDate = r.statement.statementDate,
            statementOpeningBalance = r.statement.openingBalance,
            ledgerOpeningBalance = r.ledgerOpeningBalance,
            openingDifference = r.openingDifference,
            statementClosingBalance = r.statement.closingBalance,
            ledgerClosingBalance = r.ledgerClosingBalance,
            closingDifference = r.closingDifference,
            balanceNotStated = r.balanceNotStated,
            reconciled = r.reconciled,
            matches = r.matches.map(MatchResponse::from),
            unmatchedStatementEntries = r.unmatchedStatementEntries.map(StatementEntryResponse::from),
            unmatchedLedgerLines = r.unmatchedLedgerLines.map(LedgerLineResponse::from),
        )
    }
}
