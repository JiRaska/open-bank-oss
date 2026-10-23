// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.swift.application.usecase.InstitutionTransferRefusedException
import com.openbank.swift.application.usecase.InstitutionTransferService
import com.openbank.swift.domain.institution.ReleasedPayment
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import org.jboss.logging.Logger

/**
 * Consumes `treasury.payment.released.v1` from `openbank.treasury.payments` (contract 9.1) on the
 * `treasury-payments-in` channel. Only treasury may write that topic (KafkaUser ACL), so this is
 * the rail's single entry for bank-own transfers — there is deliberately no REST equivalent.
 *
 * Nothing is ever acked silently. Unparseable JSON and a refused release throw straight to the
 * channel's dead-letter queue (`failure-strategy: dead-letter-queue`, explicit per-service topic):
 * both are deterministic, so retrying them is pointless. A transient failure (scheme or database
 * unreachable) is retried by [EventRetry] and then dead-lettered too; replaying it later is safe
 * because the service is idempotent on the release's `idempotencyKey`.
 *
 * Unknown fields are ignored (additive evolution of 9.1); a field of the wrong JSON type reads as
 * absent and is refused by validation, except optional `ssiVersion`, whose wrong type is flagged.
 */
@ApplicationScoped
class TreasuryPaymentReleasedConsumer(
    private val service: InstitutionTransferService,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(TreasuryPaymentReleasedConsumer::class.java)

    /**
     * Acks only after [handle] returns; nacks on ANY failure, logged with its cause, so the
     * connector's failure-strategy (dead-letter-queue) parks the record. Explicit rather than
     * left to the framework's exception-to-nack mapping so the log names why a record was parked.
     */
    @Incoming("treasury-payments-in")
    @Suppress("TooGenericExceptionCaught")
    suspend fun consume(message: Message<String>) {
        try {
            handle(message.payload)
        } catch (e: Exception) {
            log.warnf(e, "treasury.payment.released.v1 not processed; nacking for the dead-letter queue")
            message.nack(e).await()
            return
        }
        message.ack().await()
    }

    suspend fun handle(payload: String) {
        val node = try {
            objectMapper.readTree(payload)?.takeIf { it.isObject }
        } catch (_: com.fasterxml.jackson.core.JacksonException) {
            null
        } ?: run {
            log.error("Unparseable treasury.payment.released.v1; dead-lettering")
            throw InstitutionTransferRefusedException("payload is not a JSON object")
        }
        val release = parse(node)
        EventRetry.withRetry(
            log,
            "institution transfer",
            release.paymentId,
            isRetryable = EventRetry.RETRY_UNLESS_DETERMINISTIC,
        ) { service.onReleased(release) }
    }

    companion object {
        fun parse(n: JsonNode): ReleasedPayment {
            val debtor = n.path("debtor")
            val creditor = n.path("creditor")
            val ssi = n.path("ssiVersion")
            return ReleasedPayment(
                paymentId = n.text("paymentId"),
                idempotencyKey = n.text("idempotencyKey"),
                rail = n.text("rail"),
                currency = n.text("currency"),
                amount = n.text("amount"),
                valueDate = n.text("valueDate"),
                debtorBic = debtor.text("bic"),
                debtorAccountNumber = debtor.text("accountNumber"),
                debtorGlAccountingClass = debtor.text("glAccountingClass"),
                creditorBic = creditor.text("bic"),
                creditorAccountNumber = creditor.text("accountNumber"),
                creditorName = creditor.text("name"),
                intermediaryBic = n.text("intermediaryBic"),
                remittanceInformation = n.text("remittanceInformation"),
                dealIds = n.textList("dealIds"),
                flowIds = n.textList("flowIds"),
                releasedBy = n.text("releasedBy"),
                releasedAt = n.text("releasedAt"),
                synthetic = n.path("synthetic").takeIf { it.isBoolean }?.booleanValue(),
                ssiVersion = ssi.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue(),
                ssiVersionMalformed =
                !ssi.isMissingNode && !ssi.isNull && !(ssi.isIntegralNumber && ssi.canConvertToLong()),
            )
        }

        private fun JsonNode.text(field: String): String? = path(field).takeIf { it.isTextual }?.textValue()

        private fun JsonNode.textList(field: String): List<String>? {
            val arr = path(field).takeIf { it.isArray } ?: return null
            return arr.map { it.takeIf(JsonNode::isTextual)?.textValue() ?: return null }
        }
    }
}
