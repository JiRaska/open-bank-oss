// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.port.`in`.AttestYearCloseCommand
import com.openbank.ledger.application.port.`in`.CreateYearCloseDraftCommand
import com.openbank.ledger.application.port.`in`.GetFiscalYearTrialBalanceQuery
import com.openbank.ledger.application.port.`in`.GetYearCloseQuery
import com.openbank.ledger.application.port.`in`.VerifyYearCloseQuery
import com.openbank.ledger.application.port.`in`.YearCloseUseCase
import com.openbank.ledger.domain.model.FiscalYearTrialBalance
import com.openbank.ledger.domain.model.YearCloseRecord
import com.openbank.ledger.domain.model.YearCloseVerification
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
import java.math.BigDecimal
import java.util.UUID

/**
 * Entity-level statutory year-close (ADR-0078 D5 / issue #471, increment 1).
 *
 * Access control follows the ledger book-of-record convention (K7 / ADR-0018, see
 * [LedgerResource]): reads are gated to service/auditor/viewer/operator/admin; the two state
 * changes (draft creation, attestation) are operator-only, like posting a journal. Locked by
 * [YearCloseSecurityContractTest].
 */
@Path("/api/v1/ledger/close")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "YearClose", description = "Entity-level fiscal-year accounting close")
class YearCloseResource(private val yearCloseUseCase: YearCloseUseCase) {

    // OIDC identity from the CDI request-scoped SecurityIdentity, NOT @Context SecurityContext:
    // in a Kotlin `suspend` resource method the @Context principal does not reliably resolve to
    // the bearer JsonWebToken, whereas SecurityIdentity survives the coroutine dispatch.
    @Inject
    lateinit var identity: SecurityIdentity

    @GET
    @Path("/trial-balance")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "")
    @Operation(summary = "Fiscal-year GL trial balance, grouped by account type (computed on demand)")
    suspend fun trialBalance(@QueryParam("fiscalYear") fiscalYear: Int?): Response {
        val year = fiscalYear
            ?: throw WebApplicationException("fiscalYear is required", Response.Status.BAD_REQUEST)
        val trialBalance = yearCloseUseCase.getTrialBalance(GetFiscalYearTrialBalanceQuery(year))
        return Response.ok(trialBalance.toResponse()).build()
    }

    @GET
    @Path("/{fiscalYear}")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "#fiscalYear")
    @Operation(summary = "Get the year-close record for a fiscal year")
    suspend fun getYearClose(@PathParam("fiscalYear") fiscalYear: Int): Response {
        val record = yearCloseUseCase.getYearClose(GetYearCloseQuery(fiscalYear))
        return Response.ok(record.toResponse()).build()
    }

    @POST
    @Path("/{fiscalYear}")
    @RolesAllowed(Roles.OPERATOR)
    @Authorize(action = "ledger.close.draft", resource = "#fiscalYear")
    @Operation(summary = "Create or refresh the DRAFT year-close record from the current trial balance")
    suspend fun createDraft(@PathParam("fiscalYear") fiscalYear: Int): Response {
        val record = yearCloseUseCase.createDraft(
            CreateYearCloseDraftCommand(fiscalYear, draftedBy = actingPrincipal()),
        )
        return Response.ok(record.toResponse()).build()
    }

    @GET
    @Path("/{fiscalYear}/verify")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "#fiscalYear")
    @Operation(
        summary = "Re-verify a year close's content hash against a fresh trial balance (read-only, " +
            "never flips state); reports drift without failing",
    )
    suspend fun verify(@PathParam("fiscalYear") fiscalYear: Int): Response {
        val result = yearCloseUseCase.verify(VerifyYearCloseQuery(fiscalYear))
        return Response.ok(result.toResponse()).build()
    }

    @POST
    @Path("/{fiscalYear}/attest")
    @RolesAllowed(Roles.OPERATOR)
    @Authorize(action = "ledger.approve", resource = "#fiscalYear")
    @Operation(summary = "Attest the DRAFT year close (re-verifies the content hash, fail-closed)")
    suspend fun attest(@PathParam("fiscalYear") fiscalYear: Int): Response {
        val record = yearCloseUseCase.attest(AttestYearCloseCommand(fiscalYear, attestedBy = actingPrincipal()))
        return Response.ok(record.toResponse()).build()
    }

    /**
     * The attesting principal for the audit trail: the OIDC `sub` claim (stable Keycloak user
     * id) when the bearer is a [JsonWebToken], else the principal name (e.g. `@TestSecurity`
     * identities expose no JsonWebToken). Anonymous can never reach here (@RolesAllowed), but
     * fail closed anyway rather than attesting as an empty string.
     */
    private fun actingPrincipal(): String {
        val principal = identity.principal
        val subject = (principal as? JsonWebToken)?.subject ?: principal?.name
        if (subject.isNullOrBlank()) {
            throw WebApplicationException(
                "Cannot resolve attesting principal from the bearer token",
                Response.Status.UNAUTHORIZED,
            )
        }
        return subject
    }
}

data class TrialBalanceSectionResponse(
    val type: String,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
    val net: BigDecimal,
    val lines: List<TrialBalanceLineResponse>,
)

data class FiscalYearTrialBalanceResponse(
    val fiscalYear: Int,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
    val balanced: Boolean,
    val accountCount: Int,
    val contentHash: String,
    val sections: List<TrialBalanceSectionResponse>,
)

data class YearCloseRecordResponse(
    val id: UUID,
    val fiscalYear: Int,
    val status: String,
    val computedAt: String,
    val totalDebits: BigDecimal,
    val totalCredits: BigDecimal,
    val accountCount: Int,
    val contentHash: String,
    val draftedBy: String?,
    val attestedBy: String?,
    val attestedAt: String?,
)

private fun FiscalYearTrialBalance.toResponse() = FiscalYearTrialBalanceResponse(
    fiscalYear = fiscalYear,
    totalDebit = totalDebit,
    totalCredit = totalCredit,
    balanced = isBalanced,
    accountCount = accountCount,
    contentHash = contentHash(),
    sections = sections.map { section ->
        TrialBalanceSectionResponse(
            type = section.type.name,
            totalDebit = section.totalDebit,
            totalCredit = section.totalCredit,
            net = section.net,
            lines = section.lines.map {
                TrialBalanceLineResponse(
                    glAccountId = it.glAccountId,
                    code = it.code,
                    name = it.name,
                    type = it.type.name,
                    currency = it.currency,
                    totalDebit = it.totalDebit,
                    totalCredit = it.totalCredit,
                    net = it.net,
                )
            },
        )
    },
)

data class YearCloseVerificationResponse(
    val fiscalYear: Int,
    val status: String,
    val recordedHash: String,
    val recomputedHash: String,
    val matches: Boolean,
    val balanced: Boolean,
    val recomputedAt: String,
)

private fun YearCloseVerification.toResponse() = YearCloseVerificationResponse(
    fiscalYear = fiscalYear,
    status = status.name,
    recordedHash = recordedHash,
    recomputedHash = recomputedHash,
    matches = matches,
    balanced = balanced,
    recomputedAt = recomputedAt.toString(),
)

private fun YearCloseRecord.toResponse() = YearCloseRecordResponse(
    id = id,
    fiscalYear = fiscalYear,
    status = status.name,
    computedAt = computedAt.toString(),
    totalDebits = totalDebits,
    totalCredits = totalCredits,
    accountCount = accountCount,
    contentHash = contentHash,
    draftedBy = draftedBy,
    attestedBy = attestedBy,
    attestedAt = attestedAt?.toString(),
)
