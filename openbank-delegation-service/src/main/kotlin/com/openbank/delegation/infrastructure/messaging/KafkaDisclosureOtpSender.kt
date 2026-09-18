// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DisclosureOtpSender
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import java.util.UUID

@ApplicationScoped
class KafkaDisclosureOtpSender(
    private val objectMapper: ObjectMapper,
    @Channel("notification-requests-out") private val emitter: MutinyEmitter<String>,
) : DisclosureOtpSender {
    override suspend fun send(partyId: UUID, recipient: String, otp: String, correlationId: UUID) {
        val request = mapOf(
            "partyId" to partyId.toString(),
            "channel" to "EMAIL",
            "template" to "OTP_CODE",
            "recipient" to recipient,
            "variables" to mapOf("code" to otp),
            "correlationId" to correlationId.toString(),
            "deduplicationKey" to correlationId.toString(),
        )
        val metadata = OutgoingKafkaRecordMetadata.builder<String>().withKey(correlationId.toString()).build()
        emitter.sendMessage(
            Message.of(objectMapper.writeValueAsString(request)).addMetadata(metadata),
        ).awaitSuspending()
    }
}
