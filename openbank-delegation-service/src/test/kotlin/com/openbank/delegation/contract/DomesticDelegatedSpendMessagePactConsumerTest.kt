// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.annotations.Pact
import au.com.dius.pact.core.model.messaging.Message
import au.com.dius.pact.core.model.messaging.MessagePact
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-owned message contract for the two domestic outcomes which settle a delegated spend
 * reservation. The provider's always-on folder replay serializes its real domain events, so a
 * renamed correlation field fails before a reservation can become permanently stranded.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = "openbank-domestic-payment",
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V3,
)
class DomesticDelegatedSpendMessagePactConsumerTest {

    @Pact(consumer = "openbank-delegation-service", provider = "openbank-domestic-payment")
    fun paymentStatusChanged(builder: MessagePactBuilder): MessagePact = builder
        .given("a delegated domestic payment has changed status")
        .expectsToReceive("a delegated domestic payment status changed event")
        .withContent(
            newJsonBody { body ->
                body.stringValue("eventType", "DOMESTIC_PAYMENT_STATUS_CHANGED")
                body.uuid("paymentId")
                body.stringType("previousStatus", "SENT_TO_CLEARING")
                body.stringValue("newStatus", "SETTLED")
                body.uuid("delegationId")
                body.uuid("reservationId")
                body.stringValue("sourceService", "domestic-payment")
                body.stringType("occurredAt", "2026-09-01T08:00:00Z")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "paymentStatusChanged")
    fun `status event supplies the reservation correlation pair`(messages: List<Message>) {
        assertThat(String(messages.single().contentsAsBytes())).contains("reservationId", "delegationId")
    }

    @Pact(consumer = "openbank-delegation-service", provider = "openbank-domestic-payment")
    fun finalizedAbsent(builder: MessagePactBuilder): MessagePact = builder
        .given("a delegated spend reservation is pending without a domestic payment")
        .expectsToReceive("a delegated spend reservation finalized absent event")
        .withContent(
            newJsonBody { body ->
                body.stringValue("eventType", "DELEGATED_SPEND_FINALIZED_ABSENT")
                body.uuid("reservationId")
                body.uuid("delegationId")
                body.uuid("grantorPartyId")
                body.uuid("granteePartyId")
                body.stringValue("resourceType", "ACCOUNT")
                body.uuid("resourceId")
                body.decimalType("amount", 125.50)
                body.stringType("currency", "CZK")
                // Deliberately non-secret low-entropy example; the regex carries the 64-hex shape.
                body.stringMatcher(
                    "idempotencyKeyHash",
                    "[0-9a-f]{64}",
                    "abababababababababababababababababababababababababababababababab",
                )
                body.stringValue("operationType", "DOMESTIC_PAYMENT")
                body.stringValue("reservationState", "RESERVED")
                body.integerType("reservationVersion", 1)
                body.integerType("version", 1)
                body.stringValue("sourceService", "domestic-payment")
                body.stringType("finalizedAt", "2026-09-01T08:30:00Z")
                body.stringType("occurredAt", "2026-09-01T08:30:00Z")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "finalizedAbsent")
    fun `finalized absence event carries the immutable reservation tuple`(messages: List<Message>) {
        assertThat(String(messages.single().contentsAsBytes())).contains("idempotencyKeyHash", "reservationVersion")
    }
}
