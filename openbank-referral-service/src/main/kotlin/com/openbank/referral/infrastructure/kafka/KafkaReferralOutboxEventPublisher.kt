// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.referral.infrastructure.kafka

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxEventPublisher
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.referral.domain.ReferralEvent
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.apache.kafka.common.header.internals.RecordHeaders
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Relays the referral outbox to the three channels `asyncapi.yaml` declares — `Qualified`,
 * `RewardRequested` and `RewardOutcome` are separate topics, unlike the fleet's more common
 * single-outgoing-channel outbox (mirrors `KafkaDelegationOutboxEventPublisher`'s per-event-type
 * routing, not `KafkaDocumentOutboxEventPublisher`'s single channel).
 */
@ApplicationScoped
class KafkaReferralOutboxEventPublisher(
    @Channel("referral-qualified-out") private val qualifiedEmitter: MutinyEmitter<String>,
    @Channel("referral-reward-requested-out") private val rewardRequestedEmitter: MutinyEmitter<String>,
    @Channel("referral-reward-outcome-out") private val rewardOutcomeEmitter: MutinyEmitter<String>,
) : OutboxEventPublisher {

    override suspend fun publish(entry: OutboxEntry) {
        val kafkaHeaders = RecordHeaders()
        OutboxKafkaHeaders.headersFor(entry).forEach { (k, v) -> kafkaHeaders.add(k, v.toByteArray()) }
        val meta = OutgoingKafkaRecordMetadata.builder<String>()
            .withKey(OutboxKafkaHeaders.partitionKey(entry))
            .withHeaders(kafkaHeaders)
            .build()
        emitterFor(entry).sendMessage(Message.of(entry.payload).addMetadata(meta)).awaitSuspending()
    }

    private fun emitterFor(entry: OutboxEntry): MutinyEmitter<String> = when (entry.eventType) {
        ReferralEvent.Qualified::class.simpleName -> qualifiedEmitter
        ReferralEvent.RewardRequested::class.simpleName -> rewardRequestedEmitter
        ReferralEvent.RewardOutcome::class.simpleName -> rewardOutcomeEmitter
        else -> error("no referral outbox channel is wired for event type ${entry.eventType}")
    }
}
