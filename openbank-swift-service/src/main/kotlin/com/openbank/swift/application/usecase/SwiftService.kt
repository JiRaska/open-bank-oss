// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.swift.application.port.`in`.SendSwiftCommand
import com.openbank.swift.application.port.`in`.SwiftUseCase
import com.openbank.swift.application.port.out.SchemeGatewayPort
import com.openbank.swift.application.port.out.SchemeGatewayUnavailableException
import com.openbank.swift.application.port.out.SettlementPort
import com.openbank.swift.application.port.out.SettlementUnavailableException
import com.openbank.swift.application.port.out.SwiftRepository
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftStatus
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class SwiftService(
    private val repo: SwiftRepository,
    private val schemeGatewayPort: SchemeGatewayPort,
    private val settlementPort: SettlementPort,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.swift.scheme-submission.enabled", defaultValue = "false")
    private val schemeSubmissionEnabled: Boolean,
) : SwiftUseCase {

    companion object {
        private const val SWIFT_MESSAGE_STATUS_CHANGED = "swift.message.status-changed"

        /**
         * Producing service, read by `AuditConsumer.resolveSourceService` as the strongest
         * (EVENT-sourced) attribution (#3994/#5256). Matches the fleet's audit convention — the
         * module directory without the `openbank-` prefix — the same spelling `EventAttribution`
         * (`TopicAttribution`) already maps `openbank.payments.swift.event` (the real outgoing
         * Kafka topic for the `swift-events-out` channel both outbox writes below publish on) to.
         * Audit-service subscribes to that topic today (`application.yaml`'s consumed-topics
         * list), so this is a live attribution upgrade, not a forward-looking one.
         */
        private const val SOURCE_SERVICE = "swift-service"
    }

    private val log = Logger.getLogger(SwiftService::class.java)

    override suspend fun send(cmd: SendSwiftCommand): SwiftMessage {
        repo.findByIdempotencyKey(cmd.idempotencyKey)?.let { return it }
        val now = Instant.now(clock)
        val msg = SwiftMessage(
            id = UUID.randomUUID(), idempotencyKey = cmd.idempotencyKey,
            messageType = cmd.messageType, senderBic = cmd.senderBic, receiverBic = cmd.receiverBic,
            transactionReference = cmd.transactionReference, relatedReference = cmd.relatedReference,
            valueDate = cmd.valueDate, currency = cmd.currency, amountMinorUnits = cmd.amountMinorUnits,
            orderingCustomerAccount = cmd.orderingCustomerAccount,
            orderingCustomerAccountId = cmd.orderingCustomerAccountId,
            orderingCustomerName = cmd.orderingCustomerName,
            beneficiaryAccount = cmd.beneficiaryAccount, beneficiaryName = cmd.beneficiaryName,
            remittanceInfo = cmd.remittanceInfo, chargeCode = cmd.chargeCode, priority = cmd.priority,
            status = SwiftStatus.PENDING, rawMt = null, ackReceivedAt = null, rejectionReason = null,
            createdAt = now, updatedAt = now,
        )
        val errors = msg.validate()
        require(errors.isEmpty()) { "SWIFT validation failed: ${errors.joinToString("; ")}" }
        val validated = msg.copy(status = SwiftStatus.VALIDATED, updatedAt = Instant.now(clock))
        val saved = repo.save(validated)
        return submitToScheme(saved)
    }

    /**
     * ADR-0104 D4 + ADR-0108: build a real `pacs.008` from the validated MT103 fields and submit
     * it to the scheme gateway. `ACSC` → SENT → settlement booked → COMPLETED;
     * `RJCT` → REJECTED. Fails closed: gateway unreachable → payment stays VALIDATED. No-op
     * unless the pilot flag is on or the message type is not MT103.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun submitToScheme(message: SwiftMessage): SwiftMessage {
        if (!schemeSubmissionEnabled || message.messageType != SwiftMessageType.MT103) return message
        if (message.status != SwiftStatus.VALIDATED) return message

        return try {
            val outcome = schemeGatewayPort.submit(message)
            val now = Instant.now(clock)
            val updated = if (outcome.accepted) {
                message.copy(status = SwiftStatus.SENT, rawMt = outcome.rawMt, updatedAt = now)
            } else {
                message.copy(
                    status = SwiftStatus.REJECTED,
                    rawMt = outcome.rawMt,
                    rejectionReason = "scheme reject (pacs.002): ${outcome.reasonCode ?: "unspecified"}",
                    updatedAt = now,
                )
            }
            val outbox = OutboxMessage(
                aggregateId = updated.id,
                eventType = SWIFT_MESSAGE_STATUS_CHANGED,
                payload = objectMapper.writeValueAsString(
                    mapOf(
                        "sourceService" to SOURCE_SERVICE,
                        "swiftMessageId" to updated.id.toString(),
                        "paymentSagaRef" to updated.transactionReference,
                        "status" to updated.status.name,
                        "messageType" to updated.messageType.name,
                        "amount" to BigDecimal(updated.amountMinorUnits).movePointLeft(2),
                        "currency" to updated.currency,
                        "occurredAt" to updated.updatedAt.toString(),
                    ),
                ),
            )
            val persisted = repo.saveWithOutbox(updated, outbox)
            // ADR-0108: book the funds in transaction-service after scheme ACSC.
            if (persisted.status == SwiftStatus.SENT) settle(persisted) else persisted
        } catch (ex: SchemeGatewayUnavailableException) {
            log.warnf(ex, "Scheme gateway unavailable for SWIFT message %s; holding in VALIDATED", message.id)
            message
        } catch (ex: Exception) {
            log.warnf(ex, "Unexpected error during scheme submission for SWIFT message %s; holding", message.id)
            message
        }
    }

    /**
     * ADR-0108: call transaction-service to book the funds and advance to COMPLETED.
     * Fails closed: settlement unavailable → message stays SENT (will be retried externally).
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun settle(message: SwiftMessage): SwiftMessage = try {
        val outcome = settlementPort.settle(message)
        if (outcome.settled) {
            val completed = message.copy(status = SwiftStatus.COMPLETED, updatedAt = Instant.now(clock))
            val outbox = OutboxMessage(
                aggregateId = completed.id,
                eventType = SWIFT_MESSAGE_STATUS_CHANGED,
                payload = objectMapper.writeValueAsString(
                    mapOf(
                        "sourceService" to SOURCE_SERVICE,
                        "swiftMessageId" to completed.id.toString(),
                        "paymentSagaRef" to completed.transactionReference,
                        "status" to completed.status.name,
                        "messageType" to completed.messageType.name,
                        "amount" to BigDecimal(completed.amountMinorUnits).movePointLeft(2),
                        "currency" to completed.currency,
                        "occurredAt" to completed.updatedAt.toString(),
                    ),
                ),
            )
            repo.saveWithOutbox(completed, outbox)
        } else {
            log.warnf(
                "Settlement skipped for SWIFT message %s (no account UUID); message stays SENT",
                message.id,
            )
            message
        }
    } catch (ex: SettlementUnavailableException) {
        log.warnf(ex, "transaction-service unavailable for SWIFT message %s; holding in SENT", message.id)
        message
    } catch (ex: Exception) {
        log.warnf(ex, "Unexpected error during settlement for SWIFT message %s; holding in SENT", message.id)
        message
    }

    override suspend fun getById(id: UUID) = repo.findById(id)
    override suspend fun listAll() = repo.listAllMessages()
    override suspend fun listByStatus(status: SwiftStatus) = repo.findByStatus(status)

    override suspend fun acknowledge(id: UUID, ackRef: String): SwiftMessage {
        val msg = repo.findById(id) ?: error("SWIFT message not found: $id")
        return repo.save(
            msg.copy(
                status = SwiftStatus.ACKNOWLEDGED,
                ackReceivedAt = Instant.now(clock),
                updatedAt = Instant.now(clock),
            ),
        )
    }

    override suspend fun reject(id: UUID, reason: String): SwiftMessage {
        val msg = repo.findById(id) ?: error("SWIFT message not found: $id")
        return repo.save(
            msg.copy(
                status = SwiftStatus.REJECTED,
                rejectionReason = reason,
                updatedAt = Instant.now(clock),
            ),
        )
    }
}
