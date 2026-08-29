// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.contract

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
 * Consumer-driven MESSAGE contract for the `AccountCreated` event balance-service consumes to
 * initialize a zero balance (ADR-0267 §3, [com.openbank.balance.infrastructure.kafka.BalanceInitConsumer]).
 *
 * This is the first async (message) pact in the repo — it asserts the event carries the three fields
 * the consumer actually reads (`eventType`, `aggregateId`, `currency`); the producer (account-service)
 * verifies it via `AccountEventPactProviderVerificationTest` (`@PactFolder("../pacts")` — always
 * runs, no Pact Broker involved). The generated pact is committed to
 * `pacts/openbank-balance-service-openbank-account-service.json` (git-pact, ADR-0063) — a new
 * consumer/provider pair, so it does not collide with the existing balance→ledger REST pact.
 *
 * IMPORTANT — regenerate on change: if this test's `@Pact` methods change (new interaction,
 * different matcher, renamed field), re-run this test (`./gradlew :openbank-balance-service:test
 * --tests "*AccountCreatedMessagePactConsumerTest*"`) and commit the updated
 * `pacts/openbank-balance-service-openbank-account-service.json` in the same PR — an un-regenerated
 * pact file silently verifies the OLD contract on the provider side. `pact-drift-check.yml`
 * (ADR-0063 Phase 2, issue #468) enforces this: it regenerates every consumer pact and fails on
 * `git diff -- pacts/`. Note what that gate can and cannot see — its only assertion is the diff,
 * so a module it does not regenerate does not read as *unchecked*, it reads as *passing*. Its
 * scope is therefore DERIVED, by `.github/scripts/derive-pact-drift-scope.sh`, from the
 * `@Pact(consumer = .., provider = ..)` annotations themselves; a consumer test in a new module
 * needs no workflow edit, and a pact nothing regenerates fails the derivation instead of going
 * quietly green.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = "openbank-account-service",
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V3,
)
class AccountCreatedMessagePactConsumerTest {

    @Pact(consumer = "openbank-balance-service", provider = "openbank-account-service")
    fun accountCreatedPact(builder: MessagePactBuilder): MessagePact = builder
        .given("an account has been created")
        .expectsToReceive("an AccountCreated event")
        .withContent(
            newJsonBody { o ->
                // The consumer filters on eventType (exact) and reads aggregateId + currency.
                o.stringValue("eventType", "AccountCreated")
                o.uuid("aggregateId")
                o.stringType("currency", "CZK")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "accountCreatedPact")
    fun `the AccountCreated event carries the fields BalanceInitConsumer needs`(messages: List<Message>) {
        val node = ObjectMapper().readTree(messages.first().contentsAsBytes())

        // Mirrors BalanceInitConsumer.consume: filter by eventType, then read aggregateId + currency.
        assertThat(node["eventType"].asText()).isEqualTo("AccountCreated")
        assertThat(UUID.fromString(node["aggregateId"].asText())).isNotNull()
        assertThat(node["currency"].asText()).isNotBlank()
    }
}
