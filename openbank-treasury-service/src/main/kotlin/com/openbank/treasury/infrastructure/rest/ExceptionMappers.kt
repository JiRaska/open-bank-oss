// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.openbank.treasury.application.port.out.DealNotFoundException
import com.openbank.treasury.application.port.out.LedgerUnavailableException
import com.openbank.treasury.application.port.out.NostroAccountNotFoundException
import com.openbank.treasury.application.port.out.PortfolioSnapshotMissingException
import com.openbank.treasury.application.port.out.StatementNotFoundException
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.FourEyesViolationException
import com.openbank.treasury.domain.model.LimitBreachedException
import com.openbank.treasury.domain.model.ProductLimitBreachedException
import com.openbank.treasury.domain.model.QuoteUnavailableException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

/**
 * Only the types this service owns. `IllegalArgumentException` is 400 fleet-wide through
 * libs-runtime and must not be re-mapped here (ADR-0049, #526). `IllegalStateException` is the
 * aggregate's `check()` on a lifecycle rule — a conflict with the current state, so 409.
 *
 * Four-eyes and limit breaches are 422: the request is well-formed and the deal is in the right
 * state, but a business rule refuses it. A non-human principal attempting a person's step is 403.
 */
// TooManyFunctions: one mapper per owned exception type is the RESTEasy idiom; splitting the class only scatters them.
@Suppress("TooManyFunctions")
class ExceptionMappers {

    @ServerExceptionMapper
    fun notFound(e: DealNotFoundException): Response =
        error(Response.Status.NOT_FOUND.statusCode, "NOT_FOUND", e.message)

    @ServerExceptionMapper
    fun statementNotFound(e: StatementNotFoundException): Response =
        error(Response.Status.NOT_FOUND.statusCode, "NOT_FOUND", e.message)

    @ServerExceptionMapper
    fun nostroAccountNotFound(e: NostroAccountNotFoundException): Response =
        error(Response.Status.NOT_FOUND.statusCode, "NOT_FOUND", e.message)

    @ServerExceptionMapper
    fun ledgerUnavailable(e: LedgerUnavailableException): Response =
        error(Response.Status.BAD_GATEWAY.statusCode, "LEDGER_UNAVAILABLE", e.message)

    /** ADR-0337 amendment D2: no snapshot for the date is a 409, never an empty list. */
    @ServerExceptionMapper
    fun portfolioMissing(e: PortfolioSnapshotMissingException): Response =
        error(Response.Status.CONFLICT.statusCode, "PORTFOLIO_SNAPSHOT_MISSING", e.message)

    @ServerExceptionMapper
    fun conflict(e: IllegalStateException): Response =
        error(Response.Status.CONFLICT.statusCode, "INVALID_STATE", e.message)

    /** ADR-0315 D9: no quote can be priced (no curve set / curve, risk engine unreachable or refusing). */
    @ServerExceptionMapper
    fun quoteUnavailable(e: QuoteUnavailableException): Response =
        error(Response.Status.SERVICE_UNAVAILABLE.statusCode, "QUOTE_UNAVAILABLE", e.message)

    @ServerExceptionMapper
    fun fourEyes(e: FourEyesViolationException): Response = error(UNPROCESSABLE, "FOUR_EYES_VIOLATION", e.message)

    @ServerExceptionMapper
    fun limit(e: LimitBreachedException): Response = error(UNPROCESSABLE, "LIMIT_BREACHED", e.message)

    /**
     * ADR-0315 D4: outside the product mandate, at submit or at approval. `breaches` lists each rule
     * broken with the limit and the deal's figure, so a client need not parse [message].
     */
    @ServerExceptionMapper
    fun productLimit(e: ProductLimitBreachedException): Response = Response.status(UNPROCESSABLE).entity(
        mapOf(
            "error" to "PRODUCT_LIMIT_BREACHED",
            "message" to e.message,
            "breaches" to
                e.check.breaches.map { mapOf("rule" to it.rule.name, "limit" to it.limit, "actual" to it.actual) },
        ),
    ).build()

    @ServerExceptionMapper
    fun notPermitted(e: ActorNotPermittedException): Response =
        error(Response.Status.FORBIDDEN.statusCode, "ACTOR_NOT_PERMITTED", e.message)

    private fun error(status: Int, code: String, message: String?): Response =
        Response.status(status).entity(mapOf("error" to code, "message" to message)).build()

    private companion object {
        const val UNPROCESSABLE = 422
    }
}
