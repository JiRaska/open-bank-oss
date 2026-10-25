// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The outbox wire fact: an originated settlement's `SETTLEMENT_STATE_CHANGED` reaches Redpanda
 * with the full envelope (event id, schema version, source service, actor kind, both statuses,
 * protocol, both accounts and the exact-decimal amount) — the broker end of the durable fact the
 * origination journey commits in Postgres.
 */
@QuarkusTest
@TestProfile(SettlementE2EProfile::class)
class SettlementEventE2E : SettlementE2EBase() {

    @Test
    @TestSecurity(user = "settlement-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `an origination dispatches SETTLEMENT_STATE_CHANGED to the broker with the full envelope`() {
        val key = "e2e-wire-${UUID.randomUUID()}"
        val payer = UUID.randomUUID()
        val payee = UUID.randomUUID()
        val id = UUID.fromString(
            originate(instruction(key, payer = payer, payee = payee, amount = "1250.50"), 201).get("id").asText(),
        )

        val event = awaitWireEvent(id)
        assertThat(event.get("eventType").asText()).isEqualTo("SETTLEMENT_STATE_CHANGED")
        assertThat(event.get("schemaVersion").asInt()).isEqualTo(1)
        assertThat(event.get("aggregateType").asText()).isEqualTo("SETTLEMENT")
        assertThat(event.get("aggregateId").asText()).isEqualTo(id.toString())
        assertThat(event.get("sourceService").asText()).isEqualTo("settlement-service")
        assertThat(event.get("actorType").asText()).isEqualTo("SERVICE")
        assertThat(event.get("previousStatus").isNull).isTrue()
        assertThat(event.get("status").asText()).isEqualTo("PENDING")
        assertThat(event.get("protocol").asText()).isEqualTo("LEGACY")
        assertThat(event.get("payerAccountId").asText()).isEqualTo(payer.toString())
        assertThat(event.get("payeeAccountId").asText()).isEqualTo(payee.toString())
        assertThat(event.get("amount").asText()).isEqualTo("1250.5000")
        assertThat(event.get("currency").asText()).isEqualTo("CZK")
        assertThat(event.get("eventId").asText()).isNotBlank()
        assertThat(event.get("occurredAt").asText()).isNotBlank()
    }

    @Test
    @TestSecurity(user = "settlement-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `a replayed origination emits exactly one wire fact`() {
        val key = "e2e-wire-replay-${UUID.randomUUID()}"
        val body = instruction(key)
        originate(body, 201)
        originate(body, 201)

        val id = UUID.nameUUIDFromBytes("settlement:$key".toByteArray())
        val event = awaitWireEvent(id)
        assertThat(event.get("status").asText()).isEqualTo("PENDING")
        // The durable side agrees: one outbox row, ever.
        assertThat(stateFacts(id)).hasSize(1)
    }
}
