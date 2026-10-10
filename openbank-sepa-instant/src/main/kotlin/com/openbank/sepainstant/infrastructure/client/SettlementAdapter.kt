// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

import com.fasterxml.jackson.databind.ObjectMapper
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
 * A 201 response confirms settlement only when its transaction is COMPLETED and matches
 * the request. A 409 is a concurrent-state conflict, not proof of an idempotent booking.
 */
@ApplicationScoped
class SettlementAdapter(@RestClient private val client: TransactionServiceClient) : SettlementPort {

    private val log = Logger.getLogger(SettlementAdapter::class.java)

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var objectMapper: ObjectMapper

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
            amount = payment.amount.amount,
            currencyCode = payment.currency,
            description = "SCT Inst settlement ${payment.endToEndId}",
            valueDate = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE),
            rail = "SEPA_INST",
            instructionType = "ONE_OFF",
        )
        return client.initiateTransaction(idempotencyKey, request)
            .map { response -> mapResponse(payment, request, response) }
            .onFailure().transform { ex ->
                SettlementUnavailableException(
                    "transaction-service unreachable for payment ${payment.paymentId}",
                    ex,
                )
            }
    }

    private fun mapResponse(
        payment: SctInstPayment,
        request: InitiateSettlementRequest,
        response: Response,
    ): SettlementOutcome = try {
        if (response.status != Response.Status.CREATED.statusCode) {
            throw SettlementUnavailableException(
                "Unexpected HTTP ${response.status} from transaction-service for payment ${payment.paymentId}",
            )
        }
        val tx = objectMapper.readTree(response.readEntity(String::class.java))
        val id = runCatching { UUID.fromString(tx.path("id").asText()) }.getOrNull()
            ?: throw SettlementUnavailableException("Settlement response has no transaction id")
        val amountNode = tx.path("amount")
        if (!amountNode.isNumber) {
            throw SettlementUnavailableException("Settlement response has no numeric amount")
        }
        val amount = amountNode.decimalValue()
        // The provider resolves valueDate to a business day; a completed booking may legitimately
        // have a later valueDate than this request, including on idempotent cross-day replay.
        val matchesRequest = listOf(
            tx.path("type").asText() == request.type,
            tx.path("sourceAccountId").asText() == request.sourceAccountId.toString(),
            amount.compareTo(request.amount) == 0,
            tx.path("currencyCode").asText() == request.currencyCode,
            tx.path("rail").asText() == request.rail,
            tx.path("instructionType").asText() == request.instructionType,
        ).all { it }
        if (!matchesRequest) {
            throw SettlementUnavailableException("Settlement response does not match payment ${payment.paymentId}")
        }
        when (tx.path("status").asText()) {
            "COMPLETED" -> {
                log.infof("Settled instant payment %s → transaction %s", payment.paymentId, id)
                SettlementOutcome(settled = true, transactionId = id)
            }
            "PENDING", "PROCESSING" -> SettlementOutcome(settled = false, transactionId = id)
            else -> throw SettlementUnavailableException(
                "Settlement is not completed for payment ${payment.paymentId}",
            )
        }
    } finally {
        response.close()
    }
}
