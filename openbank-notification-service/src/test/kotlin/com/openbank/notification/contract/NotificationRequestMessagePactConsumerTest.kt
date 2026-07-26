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
 *
 * MATCHER POLICY (issue #2425): `channel` and `template` are pinned BY VALUE; everything else is
 * type-matched. The split is deliberate — a `type` matcher is right for free-form payload and
 * wrong for a discriminator, because it makes the assertion vacuous exactly where the value is
 * the contract. Nothing in CI can see that: `pact-drift-check.yml` only asks whether the
 * committed pact matches what this test generates, and `check-pact-provider-replay.py` only asks
 * whether something replays it. Neither can tell that the replay is being asked nothing.
 *
 * IMPORTANT — regenerate on change: re-run
 * `./gradlew :openbank-notification-service:test --tests "*NotificationRequestMessagePactConsumerTest*"`
 * and commit the updated `pacts/openbank-notification-service-openbank-account-service.json` in
 * the same PR. `pact-drift-check.yml` enforces this.
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
                // stringValue, NOT stringType: `channel` and `template` are closed
                // vocabularies whose VALUE is the whole point of the message — channel decides
                // whether the customer gets a push, an SMS or an email, template decides which
                // message they receive. A type matcher accepts any string, so the contract
                // pinned the shape and nothing else: measured on #2425, changing the producer
                // to emit `"channel": "CARRIER_PIGEON"` left the provider replay GREEN, while
                // deleting the field correctly went red — the replay works, it simply was not
                // being asked anything about values.
                //
                // The producer (account-service's KafkaNotificationRequestPublisher) emits
                // these two as literals for this event, so an exact match is the honest
                // contract, not over-coupling. The sibling pact one file over
                // (openbank-balance-service-openbank-account-service.json) already treats its
                // `eventType` discriminator this way.
                o.stringValue("channel", "PUSH")
                o.stringValue("template", "TRANSACTION_COMPLETED")
                // Free-form per message — recipient is a party id / address and the amounts
                // vary, so pinning their VALUES would be exactly the coupling Pact exists to
                // avoid. Type matchers are right here.
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
