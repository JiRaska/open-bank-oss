// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.port.`in`.GetJournalQuery
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.usecase.BranchPaymentReceipt
import com.openbank.ledger.application.usecase.BranchPaymentRequest
import com.openbank.ledger.domain.model.checkConflict
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.libs.web.SYNTHETIC_TAINT_PROPERTY
import com.openbank.libs.web.SyntheticTaintContext
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

/** Both books, their outbox and the receipt commit in one database transaction. */
@Path("/api/v1/journals/branch-payments")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class BranchPaymentResource(
    private val ledger: LedgerUseCase,
    private val identity: SecurityIdentity,
    private val taint: SyntheticTaintContext,
) {
    @POST
    @RolesAllowed(Roles.API)
    @Authorize(action = "ledger.create", resource = "")
    suspend fun post(
        body: BranchPaymentRequest?,
        @Context requestContext: ContainerRequestContext,
    ): BranchPaymentReceipt = book(body, requestContext, requireSynthetic = false)

    @POST
    @Path("/synthetic")
    @RolesAllowed(Roles.API)
    @Authorize(action = "ledger.create", resource = "")
    suspend fun postSynthetic(
        body: BranchPaymentRequest?,
        @Context requestContext: ContainerRequestContext,
    ): BranchPaymentReceipt = book(body, requestContext, requireSynthetic = true)

    private suspend fun book(
        body: BranchPaymentRequest?,
        requestContext: ContainerRequestContext,
        requireSynthetic: Boolean,
    ): BranchPaymentReceipt {
        val request = requireNotNull(body) { "request body is required" }
        val token = identity.principal as? JsonWebToken
        if (token?.getClaim<String>("azp") != "openbank-transaction" ||
            token.getClaim<String>("preferred_username") != "service-account-openbank-transaction"
        ) {
            throw ForbiddenException("Branch payments require the Transaction service")
        }
        request.validate()
        val synthetic = taint.tainted || requestContext.getProperty(SYNTHETIC_TAINT_PROPERTY) == true
        if (requireSynthetic &&
            !synthetic
        ) {
            throw ForbiddenException("Synthetic branch payment requires a trusted origin")
        }
        val digest = request.digest(synthetic)
        return Panache.withTransaction {
            CoroutineScope(Dispatchers.Unconfined).async { bookLocked(request, digest, synthetic) }.asUni()
        }.awaitSuspending()
    }

    private suspend fun bookLocked(r: BranchPaymentRequest, digest: String, synthetic: Boolean): BranchPaymentReceipt {
        val session = Panache.getSession().awaitSuspending()
        session.createNativeQuery<Any>(
            "INSERT INTO ledger_branch_payments(operation_key,request_digest,bank_id) VALUES (:k,:d,:b) " +
                "ON CONFLICT(operation_key) DO NOTHING",
        ).setParameter("k", r.idempotencyKey).setParameter("d", digest).setParameter("b", r.bankId)
            .executeUpdate().awaitSuspending()
        val row = session.createNativeQuery(
            "SELECT request_digest,first_journal_id,second_journal_id FROM ledger_branch_payments " +
                "WHERE operation_key=:k FOR UPDATE",
            jakarta.persistence.Tuple::class.java,
        ).setParameter("k", r.idempotencyKey).singleResult.awaitSuspending()
        checkConflict(row.get(0) == digest) { "Branch payment key is bound to different facts" }
        val prior = listOfNotNull(row.get(1) as? UUID, row.get(2) as? UUID)
        if (prior.isNotEmpty()) return receipt(r, prior)
        for (book in listOfNotNull(r.sourceBookId, r.targetBookId).distinct()) {
            val banks = session.createNativeQuery<UUID>(
                "SELECT bank_id FROM ledger_branch_books WHERE establishment_id=:b",
                UUID::class.java,
            ).setParameter("b", book).resultList.awaitSuspending()
            checkConflict(banks.singleOrNull() == r.bankId) { "Branch book belongs to a different or unknown bank" }
        }
        val commands = r.commands(synthetic)
        val ids = commands.map { ledger.postJournal(it).id }
        session.createNativeQuery<Any>(
            "UPDATE ledger_branch_payments SET first_journal_id=:a,second_journal_id=:b WHERE operation_key=:k",
        ).setParameter("a", ids.first()).setParameter("b", ids.getOrNull(1)).setParameter("k", r.idempotencyKey)
            .executeUpdate().awaitSuspending()
        return receipt(r, ids)
    }

    private suspend fun receipt(r: BranchPaymentRequest, ids: List<UUID>): BranchPaymentReceipt {
        check(ids.all { ledger.getJournal(GetJournalQuery(it)).status.name == "POSTED" }) {
            "Branch payment journal is not posted"
        }
        return BranchPaymentReceipt(r.transactionId, r.bankId, ids)
    }
}
