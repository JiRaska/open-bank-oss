// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.contract

import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.annotations.Pact
import au.com.dius.pact.core.model.messaging.Message
import au.com.dius.pact.core.model.messaging.MessagePact
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven MESSAGE contract for the `NotificationRequest` event notification-service
 * consumes from account-service ([com.openbank.notification.application.NotificationConsumer],
 * ADR-0063 P2 Batch C). The producer is account-service's
 * [com.openbank.account.infrastructure.messaging.KafkaNotificationRequestPublisher].
 *
 * The consumer reads {partyId, channel, template, recipient, variables} from the Kafka message.
 * account-service verifies this via AccountEventPactProviderVerificationTest.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = "openbank-account-service",
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V3,
)
class NotificationRequestMessagePactConsumerTest {

    @Pact(consumer = "openbank-notification-service", provider = "openbank-account-service")
    fun transactionCompletedNotificationPact(builder: MessagePactBuilder): MessagePact = builder
        .given("account-service has posted an incoming credit")
        .expectsToReceive("a TRANSACTION_COMPLETED notification request")
        .withContent(
            newJsonBody { o ->
                o.uuid("partyId")
                o.stringType("channel", "PUSH")
                o.stringType("template", "TRANSACTION_COMPLETED")
                o.stringType("recipient")
                o.`object`("variables") { v ->
                    v.stringType("amount", "50.00")
                    v.stringType("currency", "CZK")
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "transactionCompletedNotificationPact")
    fun `the notification request carries the fields NotificationConsumer needs`(messages: List<Message>) {
        val node = ObjectMapper().readTree(messages.first().contentsAsBytes())

        assertThat(UUID.fromString(node.path("partyId").asText())).isNotNull()
        assertThat(node.path("channel").asText()).isEqualTo("PUSH")
        assertThat(node.path("template").asText()).isEqualTo("TRANSACTION_COMPLETED")
        assertThat(node.path("recipient").asText()).isNotBlank()
        assertThat(node.path("variables").path("amount").asText()).isNotBlank()
        assertThat(node.path("variables").path("currency").asText()).isNotBlank()
    }
}
