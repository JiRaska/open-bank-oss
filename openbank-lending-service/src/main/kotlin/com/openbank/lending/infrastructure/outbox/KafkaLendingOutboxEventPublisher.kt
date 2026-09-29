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

@ApplicationScoped
class KafkaLendingOutboxEventPublisher(
    @Channel("lending-events-out") private val emitter: MutinyEmitter<String>,
    private val ledger: LedgerPostingPort,
    private val mapper: ObjectMapper,
) : OutboxEventPublisher {
    override suspend fun publish(entry: OutboxEntry) {
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
        emitter.sendMessage(Message.of(entry.payload).addMetadata(meta)).awaitSuspending()
    }
}
