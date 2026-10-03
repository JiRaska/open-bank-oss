// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.infrastructure.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.AllowancePostingCommand
import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.PostingKind
import com.openbank.libs.domain.money.Money
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.common.header.internals.RecordHeaders
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class KafkaLendingOutboxEventPublisher(
    @Channel("lending-events-out") private val emitter: MutinyEmitter<String>,
    @Channel("lending-graph-references-out") private val graphEmitter: MutinyEmitter<String>,
    private val ledger: LedgerPostingPort,
    private val mapper: ObjectMapper,
) : OutboxEventPublisher {
    override suspend fun publish(entry: OutboxEntry) {
        val destination = when (entry.eventType) {
            "lending.graph.guarantee.approved" -> {
                validateGraphReference(entry.payload)
                graphEmitter
            }
            else -> {
                check(!entry.eventType.startsWith("lending.graph.")) { "Unknown lending graph event type" }
                emitter
            }
        }
        if (entry.eventType == "lending.allowance.posting") {
            val command = mapper.readValue(entry.payload, AllowancePostingCommand::class.java)
            ledger.post(
                LedgerPosting(
                    reference = command.reference,
                    partyId = command.partyId,
                    amount = Money.of(command.amount, command.currency),
                    kind = PostingKind.PROVISIONING,
                    accountingDate = command.accountingDate,
                ),
            ).awaitSuspending()
            if (command.eventPayload != null) {
                publish(entry.copy(eventType = "loan.provisioned", payload = command.eventPayload))
            }
            return
        }
        val kafkaHeaders = RecordHeaders()
        OutboxKafkaHeaders.headersFor(entry).forEach { (k, v) -> kafkaHeaders.add(k, v.toByteArray()) }
        val meta = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(OutboxKafkaHeaders.partitionKey(entry))
            .withHeaders(kafkaHeaders)
            .build()
        destination.sendMessage(Message.of(entry.payload).addMetadata(meta)).awaitSuspending()
    }

    private fun validateGraphReference(payload: String) {
        check(payload.toByteArray(Charsets.UTF_8).size <= MAX_GRAPH_REFERENCE_BYTES) {
            "Lending graph reference exceeds payload budget"
        }
        val node = mapper.readTree(payload)
        check(node.isObject && node.fieldNames().asSequence().toSet() == GRAPH_REFERENCE_FIELDS) {
            "Invalid lending graph reference schema"
        }
        val validValues = node.path("schemaVersion").isIntegralNumber &&
            node.path("schemaVersion").intValue() == 1 &&
            node.path("eventType").textValue() == "lending.graph.guarantee.approved" &&
            node.path("revision").isIntegralNumber &&
            node.path("revision").longValue() > 0 &&
            node.path("bankScope").textValue()?.matches(BANK_SCOPE_PATTERN) == true
        check(validValues) { "Invalid lending graph reference values" }
        UUID.fromString(node.path("guaranteeId").textValue())
        UUID.fromString(node.path("loanId").textValue())
        Instant.parse(node.path("occurredAt").textValue())
    }

    private companion object {
        const val MAX_GRAPH_REFERENCE_BYTES = 512
        val GRAPH_REFERENCE_FIELDS = setOf(
            "schemaVersion",
            "eventType",
            "guaranteeId",
            "loanId",
            "revision",
            "bankScope",
            "occurredAt",
        )
        val BANK_SCOPE_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,63}")
    }
}
