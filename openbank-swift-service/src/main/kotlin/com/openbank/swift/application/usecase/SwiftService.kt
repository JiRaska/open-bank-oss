// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.swift.application.port.`in`.SendSwiftCommand
import com.openbank.swift.application.port.`in`.SwiftUseCase
import com.openbank.swift.application.port.out.SchemeGatewayPort
import com.openbank.swift.application.port.out.SchemeGatewayUnavailableException
import com.openbank.swift.application.port.out.SettlementPort
import com.openbank.swift.application.port.out.SettlementUnavailableException
import com.openbank.swift.application.port.out.SwiftRepository
import com.openbank.swift.application.port.out.SwiftSettlementRedrivePort
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftStatus
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
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
    private val redrive: SwiftSettlementRedrivePort,
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

    /**
     * The `swift.message.status-changed` envelope, built in ONE place so every transition that
     * publishes one publishes the same shape. All four call sites — the scheme verdict, the
     * settlement, and the two operator transitions below — go through here.
     *
     * The keys are exactly the ones `docs/asyncapi/openbank-events.yaml`'s
     * `SwiftMessageEventPayload` declares (plus `sourceService`, the attribution field
     * `AuditConsumer` reads). `ackRef` and `rejectionReason` are deliberately NOT on the wire:
     * an earlier revision of that document declared them and the producer never emitted them,
     * and #8718 is fixed by adding the missing OCCURRENCES of an already-published event, not by
     * widening its payload. `status` alone carries the transition — `ACKNOWLEDGED` and
     * `REJECTED` are both already in the document's declared enum, so no consumer sees a value
     * the contract did not already allow.
     */
    private fun statusChangedEvent(message: SwiftMessage) = OutboxMessage(
        aggregateId = message.id,
        eventType = SWIFT_MESSAGE_STATUS_CHANGED,
        payload = objectMapper.writeValueAsString(
            mapOf(
                "sourceService" to SOURCE_SERVICE,
                "swiftMessageId" to message.id.toString(),
                "paymentSagaRef" to message.transactionReference,
                "status" to message.status.name,
                "messageType" to message.messageType.name,
                "amount" to message.amount.amount,
                "currency" to message.currency,
                "occurredAt" to message.updatedAt.toString(),
            ),
        ),
    )

    override suspend fun send(cmd: SendSwiftCommand): SwiftMessage {
        repo.findByIdempotencyKey(cmd.idempotencyKey)?.let { return it }
        val now = Instant.now(clock)
        val msg = SwiftMessage(
            id = UUID.randomUUID(), idempotencyKey = cmd.idempotencyKey,
            messageType = cmd.messageType, senderBic = cmd.senderBic, receiverBic = cmd.receiverBic,
            transactionReference = cmd.transactionReference, relatedReference = cmd.relatedReference,
            valueDate = cmd.valueDate, amount = cmd.amount,
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
            val persisted = if (outcome.accepted) {
                repo.acceptForBooking(updated, statusChangedEvent(updated))
            } else {
                repo.saveWithOutbox(updated, statusChangedEvent(updated))
            }
            // ADR-0108: book the funds in transaction-service after scheme ACSC.
            if (persisted.status == SwiftStatus.SENT) settleDetailed(persisted).message else persisted
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: SchemeGatewayUnavailableException) {
            log.warnf(ex, "Scheme gateway unavailable for SWIFT message %s; holding in VALIDATED", message.id)
            message
        } catch (ex: Exception) {
            log.warnf(ex, "Unexpected error during scheme submission for SWIFT message %s; holding", message.id)
            message
        }
    }

    /**
     * #13686 re-drive entry: settle an already scheme-accepted MT103 again, under the SAME
     * `swift-settlement-<messageId>` key and with the request built from the stored message alone (the 32A
     * value date, never today's date). It never calls the scheme gateway. The caller
     * ([SwiftSettlementRedriveService]) holds the durable claim and has checked the open booking clock;
     * this refuses anything that is not SENT so a stale caller cannot complete another state.
     */
    suspend fun settleAccepted(message: SwiftMessage): SettleAttempt {
        if (message.status != SwiftStatus.SENT || message.messageType != SwiftMessageType.MT103) {
            return SettleAttempt(message, permanent = false)
        }
        return settleDetailed(message)
    }

    /** One settlement attempt: the resulting message, and whether no retry can ever change the answer. */
    data class SettleAttempt(val message: SwiftMessage, val permanent: Boolean, val bookingProven: Boolean = false)

    /**
     * ADR-0108: call transaction-service to book the funds and advance to COMPLETED.
     * Fails closed: settlement unavailable → message stays SENT; the #13686 re-drive sweep retries it.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun settleDetailed(message: SwiftMessage): SettleAttempt = try {
        val outcome = settlementPort.settle(message)
        if (outcome.settled && outcome.transactionId != null) {
            val completed = message.copy(status = SwiftStatus.COMPLETED, updatedAt = Instant.now(clock))
            val recorded = try {
                repo.completeBooking(completed, outcome.transactionId, statusChangedEvent(completed))
            } catch (ex: ResourceConflictException) {
                log.debug("Normal booking completion conflicted; checking the strict late-proof boundary", ex)
                // Only the separate boundary may retain proof after an operator status/version change.
                // It rechecks the immutable request and durable stop under a short row lock.
                repo.recordLateBookingProof(message, outcome.transactionId, Instant.now(clock))
            }
            SettleAttempt(recorded, permanent = false, bookingProven = true)
        } else {
            log.warnf(
                "Settlement unproven for SWIFT message %s; message stays SENT",
                message.id,
            )
            SettleAttempt(message, outcome.permanent)
        }
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: SettlementUnavailableException) {
        log.warnf(ex, "transaction-service unavailable for SWIFT message %s; holding in SENT", message.id)
        SettleAttempt(message, permanent = false)
    } catch (ex: Exception) {
        log.warnf(ex, "Unexpected error during settlement for SWIFT message %s; holding in SENT", message.id)
        SettleAttempt(message, permanent = false)
    }

    override suspend fun getById(id: UUID) = repo.findById(id)
    override suspend fun listAll() = repo.listAllMessages()
    override suspend fun listByStatus(status: SwiftStatus) = repo.findByStatus(status)

    /**
     * #8718: an operator acknowledgement is a status transition like any other, so it writes its
     * outbox row in the SAME transaction as the state change — `saveWithOutbox`, never `save`.
     *
     * It published nothing before, which made the service announce every SCHEME decision and no
     * OPERATOR one. `audit-service` subscribes to `openbank.payments.swift.event`, so the audit
     * trail held what the counterparty decided and nothing about what a human here decided —
     * the wrong way round on a money-path service, since a scheme verdict is reproducible from
     * the counterparty's own records and an operator's is not.
     */
    override suspend fun acknowledge(id: UUID, ackRef: String): SwiftMessage {
        val msg = repo.findById(id) ?: error("SWIFT message not found: $id")
        val now = Instant.now(clock)
        redrive.stopForOperator(id, "operator-ack", now)
        val acknowledged = msg.copy(
            status = SwiftStatus.ACKNOWLEDGED,
            ackReceivedAt = now,
            updatedAt = now,
        )
        return repo.saveWithOutbox(acknowledged, statusChangedEvent(acknowledged))
    }

    /** #8718: see [acknowledge] — an operator rejection publishes on the same terms. */
    override suspend fun reject(id: UUID, reason: String): SwiftMessage {
        val msg = repo.findById(id) ?: error("SWIFT message not found: $id")
        redrive.stopForOperator(id, "operator-reject", Instant.now(clock))
        val rejected = msg.copy(
            status = SwiftStatus.REJECTED,
            rejectionReason = reason,
            updatedAt = Instant.now(clock),
        )
        return repo.saveWithOutbox(rejected, statusChangedEvent(rejected))
    }
}

/**
 * #13686: an operator decision takes the message out of the settlement re-drive for good, and is refused
 * while a re-drive holds a live claim (an in-flight transaction-service call). The booking clock is NOT
 * closed: it can only close on a proven booking, so the unsettled alert keeps naming an unbooked debit.
 *
 * The stop commits in its OWN transaction, before the status write. That fails safe: if the status write then
 * fails, the message is stopped but keeps its old status, so the only effect is that the sweep no longer
 * books it (the alert keeps firing); it can never leave a message re-drivable that an operator decided on.
 * A re-drive that stalled past its lease can still complete after the stop: that booking is under the same
 * `swift-settlement-<messageId>` key, its proof is recorded, and it is counted as a late completion.
 */
private suspend fun SwiftSettlementRedrivePort.stopForOperator(id: UUID, reason: String, now: Instant) {
    if (!stop(id, reason, now)) {
        throw ResourceConflictException("SWIFT message $id is being re-driven for settlement; retry shortly")
    }
}
