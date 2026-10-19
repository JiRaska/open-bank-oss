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
import com.openbank.treasury.domain.reference.ReferenceBackdatedException
import com.openbank.treasury.domain.reference.ReferenceNotEffectiveException
import com.openbank.treasury.domain.reference.ReferenceNotFoundException
import com.openbank.treasury.domain.reference.SeniorApprovalRequiredException
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
    fun referenceNotFound(e: ReferenceNotFoundException): Response =
        error(Response.Status.NOT_FOUND.statusCode, "NOT_FOUND", e.message)

    /** ADR-0344 D3: a deal names reference data not ACTIVE on its trade date; each missing ref listed. */
    @ServerExceptionMapper
    fun referenceNotEffective(e: ReferenceNotEffectiveException): Response = Response.status(UNPROCESSABLE).entity(
        mapOf(
            "error" to "REFERENCE_NOT_EFFECTIVE",
            "message" to e.message,
            "missingReferences" to e.missing.map { it.toString() },
        ),
    ).build()

    @ServerExceptionMapper
    fun referenceBackdated(e: ReferenceBackdatedException): Response =
        error(UNPROCESSABLE, "REFERENCE_BACKDATED", e.message)

    @ServerExceptionMapper
    fun seniorRequired(e: SeniorApprovalRequiredException): Response =
        error(Response.Status.FORBIDDEN.statusCode, "SENIOR_APPROVAL_REQUIRED", e.message)

    /** A concurrent writer committed first (row_version moved under the lock-free path): a conflict, 409. */
    @ServerExceptionMapper
    fun optimisticLock(e: jakarta.persistence.OptimisticLockException): Response =
        error(Response.Status.CONFLICT.statusCode, "INVALID_STATE", e.message)

    @ServerExceptionMapper
    fun staleState(e: org.hibernate.StaleObjectStateException): Response =
        error(Response.Status.CONFLICT.statusCode, "INVALID_STATE", e.message)

    @ServerExceptionMapper
    fun cashFlowNotFound(e: com.openbank.treasury.application.usecase.CashFlowNotFoundException): Response =
        error(Response.Status.NOT_FOUND.statusCode, "NOT_FOUND", e.message)

    @ServerExceptionMapper
    fun scheduleEngine(e: com.openbank.treasury.domain.cashflow.ScheduleEngineUnavailableException): Response =
        error(UNPROCESSABLE, "SCHEDULE_ENGINE_UNAVAILABLE", e.message)

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
    fun batchNotFound(e: com.openbank.treasury.application.port.out.ObservationBatchNotFoundException): Response =
        error(Response.Status.NOT_FOUND.statusCode, "NOT_FOUND", e.message)

    @ServerExceptionMapper
    fun dealHasReleasedPayment(e: com.openbank.treasury.domain.payment.DealHasReleasedPaymentException): Response =
        error(Response.Status.CONFLICT.statusCode, "PAYMENT_RELEASED", e.message)

    @ServerExceptionMapper
    fun paymentStale(e: com.openbank.treasury.domain.payment.PaymentStaleException): Response =
        error(Response.Status.CONFLICT.statusCode, "PAYMENT_STALE", e.message)

    @ServerExceptionMapper
    fun ssiChanged(e: com.openbank.treasury.domain.payment.SsiChangedException): Response =
        error(Response.Status.CONFLICT.statusCode, "SSI_CHANGED", e.message)

    @ServerExceptionMapper
    fun flowInPayment(e: com.openbank.treasury.domain.cashflow.FlowAlreadyInPaymentException): Response =
        error(Response.Status.CONFLICT.statusCode, "FLOW_ALREADY_IN_PAYMENT", e.message)

    @ServerExceptionMapper
    fun flowConflict(e: com.openbank.treasury.application.port.out.CashFlowConflictException): Response =
        error(Response.Status.CONFLICT.statusCode, "FLOW_CHANGED", e.message)

    @ServerExceptionMapper
    fun paymentNotFound(e: com.openbank.treasury.application.port.out.PaymentNotFoundException): Response =
        error(Response.Status.NOT_FOUND.statusCode, "NOT_FOUND", e.message)

    @ServerExceptionMapper
    fun fundingExceeded(e: com.openbank.treasury.domain.payment.FundingExceededException): Response =
        error(UNPROCESSABLE, "FUNDING_EXCEEDED", e.message)

    @ServerExceptionMapper
    fun notPermitted(e: ActorNotPermittedException): Response =
        error(Response.Status.FORBIDDEN.statusCode, "ACTOR_NOT_PERMITTED", e.message)

    @ServerExceptionMapper
    fun correctionNotFound(e: com.openbank.treasury.application.usecase.CorrectionNotFoundException): Response =
        error(Response.Status.NOT_FOUND.statusCode, "NOT_FOUND", e.message)

    @ServerExceptionMapper
    fun correctionLimit(e: com.openbank.treasury.application.port.out.CorrectionLimitBreachedException): Response =
        error(UNPROCESSABLE, "LIMIT_BREACHED", e.message)

    @ServerExceptionMapper
    fun correctionStale(e: com.openbank.treasury.domain.model.CorrectionStaleException): Response =
        error(Response.Status.CONFLICT.statusCode, "CORRECTION_STALE", e.message)

    @ServerExceptionMapper
    fun correctionBlocked(e: com.openbank.treasury.domain.model.CorrectionBlockedByPaymentException): Response =
        Response.status(Response.Status.CONFLICT.statusCode).entity(
            mapOf(
                "error" to "PAYMENT_RELEASED",
                "message" to e.message,
                "paymentId" to e.paymentId,
                "flowId" to e.flowId,
            ),
        ).build()

    private fun error(status: Int, code: String, message: String?): Response =
        Response.status(status).entity(mapOf("error" to code, "message" to message)).build()

    private companion object {
        const val UNPROCESSABLE = 422
    }
}
