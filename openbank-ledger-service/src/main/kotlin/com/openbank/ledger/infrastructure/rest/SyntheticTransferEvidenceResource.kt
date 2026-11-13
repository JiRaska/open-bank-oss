// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.port.`in`.GetJournalsByTransactionQuery
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.SecurityContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.Operation
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

@Path("/api/v1/journals/synthetic/transactions")
@Produces(MediaType.APPLICATION_JSON)
class SyntheticTransferEvidenceResource(
    private val ledger: LedgerUseCase,
    @ConfigProperty(name = "openbank.synthetic-transfer.source-account-id")
    private val sourceAccountId: Optional<UUID>,
    @ConfigProperty(name = "openbank.synthetic-transfer.target-account-id")
    private val targetAccountId: Optional<UUID>,
) {
    @GET
    @Path("/{transactionId}")
    @RolesAllowed(Roles.API)
    @Authorize(action = "ledger.synthetic-transfer.prove", resource = "#transactionId")
    @Operation(summary = "Prove one posted synthetic journal for the configured transfer fixture")
    suspend fun prove(
        @PathParam("transactionId") transactionId: UUID,
        @Context securityContext: SecurityContext,
    ): Response {
        if (ServiceAccountIdentity.verifiedClientId(securityContext.userPrincipal) != VERIFIER_CLIENT) {
            throw ForbiddenException("Synthetic transfer evidence requires its verified reader")
        }
        val accounts = setOfNotNull(sourceAccountId.orElse(null), targetAccountId.orElse(null))
        val proof = if (accounts.size == 2) {
            ledger.getJournalsByTransaction(GetJournalsByTransactionQuery(transactionId))
                .singleOrNull()?.fixtureProof(accounts)
        } else {
            null
        }
        return Response.ok(proof ?: throw unavailable()).header("Cache-Control", "no-store").build()
    }

    private fun JournalEntry.fixtureProof(accountPair: Set<UUID>): SyntheticTransferJournalEvidence? {
        if (!synthetic || status != JournalStatus.POSTED || lines.size != 2) return null
        val debit = lines.singleOrNull { it.side == JournalSide.DEBIT } ?: return null
        val credit = lines.singleOrNull { it.side == JournalSide.CREDIT } ?: return null
        val debitAccount = debit.subAccountId ?: return null
        val creditAccount = credit.subAccountId ?: return null
        if (setOf(debitAccount, creditAccount) != accountPair || !debit.fixtureAmount() || !credit.fixtureAmount()) {
            return null
        }
        return SyntheticTransferJournalEvidence(
            journalId = id,
            transactionId = transactionId,
            debitAccountId = debitAccount,
            creditAccountId = creditAccount,
            valueDate = valueDate.toString(),
            postingCount = 1,
            amountMinor = 1,
            currency = "CZK",
            synthetic = synthetic,
        )
    }

    private fun JournalLine.fixtureAmount(): Boolean {
        if (amount.currency.code != "CZK" || baseAmount.currency.code != "CZK") return false
        return amount.amount.compareTo(CANARY_AMOUNT) == 0 && baseAmount.amount.compareTo(CANARY_AMOUNT) == 0
    }

    private fun unavailable() = NotFoundException("Synthetic transfer evidence unavailable")

    private companion object {
        const val VERIFIER_CLIENT = "openbank-synthetic-transfer-verifier"
        val CANARY_AMOUNT = BigDecimal("0.01")
    }
}

data class SyntheticTransferJournalEvidence(
    val journalId: UUID,
    val transactionId: UUID,
    val debitAccountId: UUID,
    val creditAccountId: UUID,
    val valueDate: String,
    val postingCount: Int,
    val amountMinor: Int,
    val currency: String,
    val synthetic: Boolean,
)
