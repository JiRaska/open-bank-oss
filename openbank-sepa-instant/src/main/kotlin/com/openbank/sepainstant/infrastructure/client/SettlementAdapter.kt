// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

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
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Adapts [SettlementPort] to the transaction-service REST contract (ADR-0108).
 * Self-injection pattern mirrors [AmlCaseAdapter]: fault-tolerance annotations on an
 * `open` method called via the CDI proxy.
 *
 * HTTP 201 → settled; 409 → idempotent hit, treated as settled; anything else →
 * [SettlementUnavailableException] so the caller holds the payment PROCESSING.
 */
@ApplicationScoped
class SettlementAdapter(@RestClient private val client: TransactionServiceClient) : SettlementPort {

    private val log = Logger.getLogger(SettlementAdapter::class.java)

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
        val request = InitiateSettlementRequest(
            idempotencyKey = idempotencyKey,
            type = "DEBIT",
            sourceAccountId = payment.debtorAccountId,
            amount = payment.amount,
            currencyCode = payment.currency,
            description = "SCT Inst settlement ${payment.endToEndId}",
            valueDate = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE),
            rail = "SEPA_INST",
            instructionType = "ONE_OFF",
        )
        return client.initiateTransaction(idempotencyKey, request)
            .map { response -> mapResponse(payment, response) }
            .onFailure().transform { ex ->
                SettlementUnavailableException(
                    "transaction-service unreachable for payment ${payment.paymentId}",
                    ex,
                )
            }
    }

    @Suppress("MagicNumber")
    private fun mapResponse(payment: SctInstPayment, response: Response): SettlementOutcome = when (response.status) {
        Response.Status.CREATED.statusCode -> {
            val location = response.location?.toString()
            val txId = location
                ?.substringAfterLast("/")
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            log.infof("Settled instant payment %s → transaction %s", payment.paymentId, txId)
            SettlementOutcome(settled = true, transactionId = txId)
        }
        Response.Status.CONFLICT.statusCode -> {
            log.infof("Idempotent settlement hit for instant payment %s", payment.paymentId)
            SettlementOutcome(settled = true, transactionId = null)
        }
        else -> throw SettlementUnavailableException(
            "Unexpected HTTP ${response.status} from transaction-service for payment ${payment.paymentId}",
        )
    }
}
