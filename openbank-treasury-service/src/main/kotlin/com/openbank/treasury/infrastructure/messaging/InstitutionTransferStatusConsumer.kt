// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.messaging

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.treasury.application.usecase.InstitutionTransferStatus
import com.openbank.treasury.application.usecase.PaymentService
import com.openbank.treasury.domain.payment.RailStatus
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * Contract 9.2: `swift.institution-transfer.status.v1` from the institution-transfer rail moves a
 * RELEASED payment to SENT / SETTLED / FAILED ([PaymentService.onRailStatus], idempotent on
 * (paymentId, status)).
 *
 * Failure handling, the fleet's two cases:
 *  - **Poison pill** — unparseable, another event type, or a field outside its bound. It can never
 *    succeed: logged, counted and acked.
 *  - **Anything else** — an unknown payment, a key that is not the payment's own, a status that
 *    contradicts the payment's terminal outcome, or the database being down. The exception ESCAPES
 *    and SmallRye dead-letters the record (`failure-strategy: dead-letter-queue`, explicit nested
 *    topic `openbank.dlq.treasury.institution-transfer-status-in`) for a human to replay; a status
 *    about money is never dropped silently.
 */
@ApplicationScoped
class InstitutionTransferStatusConsumer(
    private val payments: PaymentService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val log = Logger.getLogger(InstitutionTransferStatusConsumer::class.java)

    @Incoming("institution-transfer-status-in")
    suspend fun consume(payload: String) {
        val status = parse(payload)
        if (status == null) {
            meterRegistry.counter("openbank.treasury.rail.status", "outcome", "poison").increment()
            log.warnf("Dropping unprocessable institution-transfer status (poison pill): %.300s", payload)
            return
        }
        val applied = payments.onRailStatus(status)
        meterRegistry.counter(
            "openbank.treasury.rail.status",
            "outcome",
            if (applied == null) "replay" else status.status.name.lowercase(),
        ).increment()
    }

    /** Bounded parse: the envelope may wrap the data (`data`) or be the data itself. */
    internal fun parse(payload: String): InstitutionTransferStatus? {
        if (payload.length > MAX_PAYLOAD) return null
        return runCatching {
            val root = objectMapper.readTree(payload)
            val type = root.text("type") ?: root.text("eventType")
            if (type != null && type != EVENT_TYPE) return null
            val d = root.get("data")?.takeIf { it.isObject } ?: root
            InstitutionTransferStatus(
                paymentId = UUID.fromString(d.text("paymentId")),
                idempotencyKey = d.text("idempotencyKey")!!.also { require(it.length <= MAX_KEY) },
                status = RailStatus.valueOf(d.text("status")!!).also { require(it.announced) },
                messageType = d.text("messageType")?.take(MAX_SHORT),
                schemeReference = d.text("schemeReference")?.take(MAX_SHORT),
                reasonCode = d.text("reasonCode")?.take(MAX_SHORT),
                reason = d.text("reason")?.take(MAX_REASON),
                at = d.text("at")?.let(Instant::parse),
                synthetic = d.get("synthetic")?.asBoolean() ?: false,
            )
        }.getOrNull()
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()

    companion object {
        const val EVENT_TYPE = "swift.institution-transfer.status.v1"
        const val MAX_PAYLOAD = 16_384
        const val MAX_KEY = 128
        const val MAX_SHORT = 140
        const val MAX_REASON = 400
    }
}
