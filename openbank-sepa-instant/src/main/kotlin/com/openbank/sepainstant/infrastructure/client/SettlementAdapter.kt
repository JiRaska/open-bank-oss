// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

import com.openbank.clients.transaction.TransactionCallOutcome
import com.openbank.clients.transaction.TransactionClient
import com.openbank.clients.transaction.model.InitiateTransactionRequest
import com.openbank.clients.transaction.model.TransactionResponse
import com.openbank.sepainstant.application.port.out.SettlementOutcome
import com.openbank.sepainstant.application.port.out.SettlementPort
import com.openbank.sepainstant.application.port.out.SettlementUnavailableException
import com.openbank.sepainstant.domain.model.SctInstPayment
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate

/**
 * Adapts [SettlementPort] to the transaction-service REST contract (ADR-0108).
 * Self-injection pattern mirrors [AmlCaseAdapter]: fault-tolerance annotations on an
 * `open` method called via the CDI proxy.
 *
 * A 201 response confirms settlement only when its transaction is COMPLETED (enforced by the
 * shared [TransactionClient]; PENDING/PROCESSING is thrown as uncertain and retried) and matches the request. A 409 is a concurrent-state conflict, not proof of an idempotent booking.
 */
@ApplicationScoped
class SettlementAdapter(@RestClient private val client: TransactionServiceClient) : SettlementPort {

    private val log = Logger.getLogger(SettlementAdapter::class.java)
    private val transactions = TransactionClient(client)

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var self: SettlementAdapter

    override fun settle(payment: SctInstPayment): Uni<SettlementOutcome> = self.settleWithResilience(payment)

    @Suppress("MagicNumber")
    @Retry(maxRetries = 2, delay = 300, jitter = 150)
    @Timeout(5_000)
    open fun settleWithResilience(payment: SctInstPayment): Uni<SettlementOutcome> {
        val idempotencyKey = "sct-inst-settlement-${payment.id}"
        val request = InitiateTransactionRequest(
            idempotencyKey = idempotencyKey,
            type = "DEBIT",
            sourceAccountId = payment.debtorAccountId,
            amount = payment.amount.amount,
            currencyCode = payment.currency,
            description = "SCT Inst settlement ${payment.endToEndId}",
            valueDate = LocalDate.now(clock),
            rail = "SEPA_INST",
            instructionType = "ONE_OFF",
        )
        return transactions.initiate(request)
            .map { outcome -> mapOutcome(payment, request, outcome) }
            .onFailure().transform { ex ->
                SettlementUnavailableException(
                    "transaction-service unreachable for payment ${payment.paymentId}",
                    ex,
                )
            }
    }

    // Each throw is a distinct reason a 2xx is NOT proof of this payment's booking; collapsing them into
    // one would lose the reason in the SettlementUnavailableException message.
    @Suppress("ThrowsCount")
    private fun mapOutcome(
        payment: SctInstPayment,
        request: InitiateTransactionRequest,
        outcome: TransactionCallOutcome<TransactionResponse>,
    ): SettlementOutcome {
        // Only 201 maps to a result (200/202 stay "unexpected", as before); 409 and 400/422 are
        // "not proof of booking" and all of them end as SettlementUnavailableException. The facade has
        // already required a readable COMPLETED transaction (anything else was thrown as
        // TransactionCompletionUncertainException), so the status is not re-interpreted here.
        val booked = outcome as? TransactionCallOutcome.HttpSuccess
        if (booked == null || booked.status != Response.Status.CREATED.statusCode) {
            throw SettlementUnavailableException(
                "Unexpected answer from transaction-service for payment ${payment.paymentId}: $outcome",
            )
        }
        // This adapter verifies the booking against the request, so it needs the body: unreadable = unproven
        // (the idempotent retry replays the same booking), which is what it always did.
        val tx = booked.body
            ?: throw SettlementUnavailableException("Settlement response has no transaction id", booked.bodyError)
        val id = tx.id ?: throw SettlementUnavailableException("Settlement response has no transaction id")
        val amount = tx.amount ?: throw SettlementUnavailableException("Settlement response has no numeric amount")
        // The provider resolves valueDate to a business day; a completed booking may legitimately
        // have a later valueDate than this request, including on idempotent cross-day replay.
        val matchesRequest = listOf(
            tx.type == request.type,
            tx.sourceAccountId == request.sourceAccountId,
            amount.compareTo(request.amount) == 0,
            tx.currencyCode == request.currencyCode,
            tx.rail == request.rail,
            tx.instructionType == request.instructionType,
        ).all { it }
        if (!matchesRequest) {
            throw SettlementUnavailableException("Settlement response does not match payment ${payment.paymentId}")
        }
        log.infof("Settled instant payment %s → transaction %s", payment.paymentId, id)
        return SettlementOutcome(settled = true, transactionId = id)
    }
}
