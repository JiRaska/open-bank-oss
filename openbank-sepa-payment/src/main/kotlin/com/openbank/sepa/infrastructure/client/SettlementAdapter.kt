// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.client

import com.openbank.clients.transaction.TransactionCallOutcome
import com.openbank.clients.transaction.TransactionClient
import com.openbank.clients.transaction.TransactionCompletionFailure
import com.openbank.clients.transaction.TransactionCompletionUncertainException
import com.openbank.clients.transaction.TransactionHttpException
import com.openbank.clients.transaction.model.InitiateTransactionRequest
import com.openbank.clients.transaction.model.TransactionResponse
import com.openbank.libs.domain.payment.PaymentRail
import com.openbank.sepa.application.port.out.SettlementOutcome
import com.openbank.sepa.application.port.out.SettlementPort
import com.openbank.sepa.application.port.out.SettlementUnavailableException
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.infrastructure.observability.SepaSettlementMetrics
import com.openbank.sepa.infrastructure.observability.SepaSettlementMetrics.Outcome
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Books the debit leg of an accepted SEPA payment in transaction-service (ADR-0108) through the shared
 * [TransactionClient] facade (ADR-0319), the only place that interprets the HTTP answer.
 *
 * Settlement is confirmed ONLY by HTTP 201 carrying a COMPLETED transaction (the facade throws on
 * anything else) that matches the request ([SettlementResponseCheck], #13217). Every other answer is
 * NOT a booking and ends as [SettlementUnavailableException], so the payment is held in PROCESSING:
 *  - 409 is a concurrent-state conflict on the provider, never proof of an earlier booking (#13225);
 *  - 400/422 refused the request; 200/202 are not the provider's answer to a create;
 *  - an unreadable or non-final 2xx is completion-UNCERTAIN and is retried with the SAME
 *    `sepa-settlement-<paymentId>` key, so the provider replays the existing booking, never books twice.
 *
 * Definitive refusals ([SettlementUnavailableException]) abort the retry; transport failures and
 * uncertain completions retry within the budget below.
 */
@ApplicationScoped
class SettlementAdapter(
    @RestClient private val client: TransactionServiceClient,
    private val clock: Clock,
    private val metrics: SepaSettlementMetrics,
) : SettlementPort {

    @Inject
    lateinit var self: SettlementAdapter

    private val log = Logger.getLogger(SettlementAdapter::class.java)
    private val transactions = TransactionClient(client)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun settle(payment: SepaPayment): SettlementOutcome {
        try {
            return self.settleWithResilience(payment)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: SettlementUnavailableException) {
            throw ex
        } catch (ex: Exception) {
            throw SettlementUnavailableException("transaction-service unavailable for payment ${payment.id}", ex)
        }
    }

    @Suppress("MagicNumber")
    @Retry(
        maxRetries = 2,
        delay = 300,
        jitter = 150,
        retryOn = [Exception::class],
        abortOn = [SettlementUnavailableException::class, CancellationException::class],
    )
    @Timeout(8_000)
    open suspend fun settleWithResilience(payment: SepaPayment): SettlementOutcome {
        val request = requestFor(payment)
        // This method is the interceptor's actual provider-call attempt. Count here, never again
        // in the outer settle wrapper, and preserve every exception/retry decision unchanged.
        val outcome = initiate(request)
        val id = try {
            SettlementResponseCheck.confirm(payment.id.toString(), request, outcome)
        } catch (ex: SettlementUnavailableException) {
            val classification = when (outcome) {
                TransactionCallOutcome.Conflict -> Outcome.CONFLICT
                is TransactionCallOutcome.Rejected -> Outcome.UNEXPECTED_STATUS
                is TransactionCallOutcome.HttpSuccess -> if (outcome.status == 201) {
                    Outcome.MISMATCH
                } else {
                    Outcome.UNEXPECTED_STATUS
                }
            }
            metrics.unsettled(classification)
            throw ex
        }
        log.infof("Settlement booked for SEPA payment %s → transaction %s", payment.id, id)
        return SettlementOutcome(settled = true, transactionId = id)
    }

    // Classify the actual provider attempt and propagate its original failure to the existing retry policy.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun initiate(request: InitiateTransactionRequest): TransactionCallOutcome<TransactionResponse> =
        try {
            transactions.initiate(request).awaitSuspending()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            metrics.unsettled(providerFailure(ex))
            throw ex
        }

    private fun providerFailure(failure: Exception): Outcome = when (failure) {
        is TransactionCompletionUncertainException -> if (failure.completionFailure ==
            TransactionCompletionFailure.FAILED
        ) {
            Outcome.FAILED_REPLAY
        } else {
            Outcome.UNEXPECTED_STATUS
        }
        is TransactionHttpException -> Outcome.UNEXPECTED_STATUS
        else -> Outcome.TRANSPORT_ERROR
    }

    private fun requestFor(payment: SepaPayment) = InitiateTransactionRequest(
        idempotencyKey = "sepa-settlement-${payment.id}",
        type = "TRANSFER",
        sourceAccountId = payment.debtorAccountId,
        amount = payment.amount.amount,
        currencyCode = payment.currency,
        description = "SEPA ${payment.type.name}: ${payment.remittanceInfo ?: payment.endToEndId}",
        valueDate = LocalDate.now(clock),
        rail = when (payment.type) {
            SepaPaymentType.SCT -> PaymentRail.SEPA_CT.name
            SepaPaymentType.SCT_INST -> PaymentRail.SEPA_INST.name
        },
        // An InstructionType (ADR-0103), orthogonal to the rail. This used to send the SEPA scheme name
        // (`SCT` / `SCT_INST`), which transaction-service's strict enum parsing answers with 400, so a
        // settlement could never book; a customer-initiated credit transfer is a one-off instruction.
        instructionType = "ONE_OFF",
    )
}

/**
 * The response-vs-request binding sepa-instant's `SettlementAdapter` applies (#13217): a COMPLETED
 * transaction proves a booking, but only of THIS payment if it is the one that was asked for. Returns
 * the booked transaction id or throws [SettlementUnavailableException] naming only the reason (outcome
 * bodies may carry customer data, so none of it reaches the message).
 */
internal object SettlementResponseCheck {
    private const val HTTP_CREATED = 201
    private const val HTTP_CONFLICT = 409

    @Suppress("ThrowsCount", "CyclomaticComplexMethod")
    fun confirm(
        paymentRef: String,
        request: InitiateTransactionRequest,
        outcome: TransactionCallOutcome<TransactionResponse>,
    ): UUID {
        val booked = when (outcome) {
            is TransactionCallOutcome.HttpSuccess -> outcome
            TransactionCallOutcome.Conflict -> throw SettlementUnavailableException(
                "transaction-service answered HTTP $HTTP_CONFLICT (a concurrent-state conflict, not a booking) " +
                    "for payment $paymentRef",
            )
            is TransactionCallOutcome.Rejected -> throw SettlementUnavailableException(
                "transaction-service refused settlement of payment $paymentRef with HTTP ${outcome.status}",
            )
        }
        if (booked.status != HTTP_CREATED) {
            throw SettlementUnavailableException(
                "Unexpected HTTP ${booked.status} from transaction-service for payment $paymentRef",
            )
        }
        val tx = booked.body
            ?: throw SettlementUnavailableException("Settlement response has no transaction", booked.bodyError)
        val id = tx.id ?: throw SettlementUnavailableException("Settlement response has no transaction id")
        val amount = tx.amount ?: throw SettlementUnavailableException("Settlement response has no numeric amount")
        // valueDate is deliberately not compared: the provider resolves it to a business day, so a completed
        // booking may legitimately carry a later date, including on a cross-day idempotent replay.
        val matchesRequest = tx.status == "COMPLETED" &&
            tx.type == request.type &&
            tx.sourceAccountId == request.sourceAccountId &&
            amount.compareTo(request.amount) == 0 &&
            tx.currencyCode == request.currencyCode &&
            // A historical response under the same key must still match; never rebook with a new key.
            tx.rail == request.rail &&
            tx.instructionType == request.instructionType
        if (!matchesRequest) {
            throw SettlementUnavailableException("Settlement response does not match payment $paymentRef")
        }
        return id
    }
}
