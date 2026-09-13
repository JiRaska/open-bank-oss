// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.libs.spend.SpendCategory
import com.openbank.transaction.application.port.`in`.GetTransactionQuery
import com.openbank.transaction.application.port.`in`.TransactionUseCase
import com.openbank.transaction.domain.model.CounterpartyKey
import com.openbank.transaction.infrastructure.persistence.repository.TransactionCategoryOverrideRepository
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * The customer's own categorisation of their spending.
 *
 * Its own resource rather than three more methods on [TransactionResource]: that class is on the
 * money path — it initiates, reverses and merge-sweeps — and categorisation is display metadata
 * that must never grow into it. Keeping them apart also keeps each class inside the
 * `TooManyFunctions` threshold honestly, instead of suppressing the rule that noticed.
 *
 * The customer names a TRANSACTION and the server generalises it to that transaction's
 * counterparty. Two reasons the counterparty key is not accepted from the caller:
 *
 *  - a key the caller composes is a key the caller can forge, and a forged key writes a row scoped
 *    to a counterparty the customer never dealt with;
 *  - the normalisation that produces the key has to match the merchant catalogue's exactly, and a
 *    second implementation on the client would drift the first time either changed.
 *
 * `accountId` is required and checked against the transaction rather than inferred from it. An
 * internal transfer touches two accounts and only the caller knows which statement it is looking
 * at; guessing would file the customer's category against the other side's account.
 */
@Path("/api/v1/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Transactions", description = "Customer-set spend categories")
class TransactionCategoryResource(
    private val transactionUseCase: TransactionUseCase,
    private val categoryOverrides: TransactionCategoryOverrideRepository,
) {
    @PUT
    @Path("/{transactionId}/category")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "transaction.categorise", resource = "#transactionId")
    @Operation(summary = "Set the customer's own category for this transaction's counterparty")
    suspend fun setCategory(
        @PathParam("transactionId") transactionId: UUID,
        @QueryParam("accountId") accountId: UUID?,
        request: SetCategoryRequest,
    ): Response {
        requireNotNull(accountId) { "query parameter 'accountId' is required" }
        val category = request.category.uppercase()
        if (!SpendCategory.isKnown(category)) {
            return problem(
                Response.Status.BAD_REQUEST.statusCode,
                "Bad Request",
                "unknown category '${request.category}'",
            )
        }
        val tx = transactionUseCase.getTransaction(GetTransactionQuery(transactionId))
        if (accountId != tx.sourceAccountId && accountId != tx.targetAccountId) {
            return problem(
                Response.Status.BAD_REQUEST.statusCode,
                "Bad Request",
                "transaction does not belong to that account",
            )
        }
        val key = CounterpartyKey.of(tx.counterpartyName, tx.description)
            ?: return problem(
                UNPROCESSABLE_ENTITY,
                "Unprocessable Entity",
                "this transaction names no counterparty that a category could be attached to",
            )
        categoryOverrides.upsert(accountId, key, category)
        return Response.ok(CategoryOverrideResponse(counterpartyKey = key, category = category)).build()
    }

    @DELETE
    @Path("/{transactionId}/category")
    @RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "transaction.categorise", resource = "#transactionId")
    @Operation(summary = "Remove the customer's category for this transaction's counterparty")
    suspend fun clearCategory(
        @PathParam("transactionId") transactionId: UUID,
        @QueryParam("accountId") accountId: UUID?,
    ): Response {
        requireNotNull(accountId) { "query parameter 'accountId' is required" }
        val tx = transactionUseCase.getTransaction(GetTransactionQuery(transactionId))
        if (accountId != tx.sourceAccountId && accountId != tx.targetAccountId) {
            return problem(
                Response.Status.BAD_REQUEST.statusCode,
                "Bad Request",
                "transaction does not belong to that account",
            )
        }
        val key = CounterpartyKey.of(tx.counterpartyName, tx.description)
        // Nothing to remove is the state the caller asked for, so it is a success, not a 404.
        if (key != null) categoryOverrides.remove(accountId, key)
        return Response.noContent().build()
    }

    @GET
    @Path("/category-overrides")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "transaction.list", resource = "")
    @Operation(summary = "Every category the customer has set on this account")
    suspend fun listCategoryOverrides(@QueryParam("accountId") accountId: UUID?): Response {
        requireNotNull(accountId) { "query parameter 'accountId' is required" }
        val rows = categoryOverrides.listFor(accountId).map {
            CategoryOverrideResponse(counterpartyKey = it.id.counterpartyKey.orEmpty(), category = it.category)
        }
        return Response.ok(mapOf("data" to rows)).build()
    }

    private fun problem(status: Int, title: String, detail: String): Response =
        Response.status(status).entity(mapOf("title" to title, "detail" to detail)).build()

    private companion object {
        /** Not in this JAX-RS version's `Response.Status`, so it is spelled out rather than guessed at. */
        const val UNPROCESSABLE_ENTITY = 422
    }
}

data class SetCategoryRequest(val category: String)

data class CategoryOverrideResponse(val counterpartyKey: String, val category: String)
