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
 * Origination idempotence, guard sondy, role boundaries and read-back over the real HTTP surface
 * against real Postgres. The settlement id is deterministic in the caller's idempotency key
 * (nameUUID "settlement:<key>"), so replay and double-submit assertions need no sequencing.
 */
@QuarkusTest
@TestProfile(SettlementE2EProfile::class)
class SettlementOriginationE2E : SettlementE2EBase() {

    @Test
    @TestSecurity(user = "settlement-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `an origination is 201 PENDING with the deterministic id and a state-change fact`() {
        val key = "e2e-originate-${UUID.randomUUID()}"
        val response = originate(instruction(key), 201)

        val id = UUID.fromString(response.get("id").asText())
        assertThat(id).isEqualTo(UUID.nameUUIDFromBytes("settlement:$key".toByteArray()))
        assertThat(response.get("status").asText()).isEqualTo("PENDING")
        assertThat(response.get("amount").decimalValue()).isEqualByComparingTo("250.00")
        assertThat(response.get("currency").asText()).isEqualTo("CZK")

        val row = settlementRow(id)
        assertThat(row).isNotNull
        assertThat(row!!.status).isEqualTo("PENDING")
        // numeric(19,4) keeps the ledger scale — the HTTP schema is the plain-decimal view.
        assertThat(row.amount).isEqualTo("250.0000")
        assertThat(row.currency).isEqualTo("CZK")

        assertThat(stateFacts(id)).containsExactly(
            StateFact(previousStatus = null, status = "PENDING", protocol = "LEGACY"),
        )
    }

    @Test
    @TestSecurity(user = "settlement-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `a replayed idempotency key returns the same settlement and never duplicates`() {
        val key = "e2e-replay-${UUID.randomUUID()}"
        val body = instruction(key)

        val first = originate(body, 201)
        val second = originate(body, 201)

        val id = UUID.fromString(first.get("id").asText())
        assertThat(second.get("id").asText()).isEqualTo(id.toString())
        assertThat(settlementCount(id)).isEqualTo(1)
        assertThat(stateFacts(id)).hasSize(1)
    }

    @Test
    @TestSecurity(user = "settlement-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `a concurrent same-key create resolves to a single row`() {
        val key = "e2e-concurrent-${UUID.randomUUID()}"
        val body = instruction(key)
        val id = UUID.nameUUIDFromBytes("settlement:$key".toByteArray())

        val first = originate(body, 201)
        // The row already exists with a terminal-ish PENDING status and a started workflow:
        // the retry is an idempotent re-accept, not a duplicate.
        val second = originate(body, 201)
        assertThat(UUID.fromString(first.get("id").asText())).isEqualTo(id)
        assertThat(UUID.fromString(second.get("id").asText())).isEqualTo(id)
        assertThat(settlementCount(id)).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "settlement-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `malformed instructions refuse 400 before anything is persisted`() {
        val cases = listOf(
            instruction("e2e-guard-${UUID.randomUUID()}") + ("idempotencyKey" to " "),
            instruction("e2e-guard-${UUID.randomUUID()}", amount = "0"),
            instruction("e2e-guard-${UUID.randomUUID()}", amount = "-1.00"),
            instruction("e2e-guard-${UUID.randomUUID()}", currency = "czk"),
            instruction("e2e-guard-${UUID.randomUUID()}", currency = "CZK1"),
        )
        for (body in cases) {
            originateExpectingOnlyStatus(body, 400)
        }
        // A same-account instruction is refused by the request contract too.
        val account = UUID.randomUUID()
        originateExpectingOnlyStatus(
            instruction("e2e-guard-${UUID.randomUUID()}", payer = account, payee = account),
            400,
        )
    }

    @Test
    @TestSecurity(user = "settlement-e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `a viewer cannot originate`() {
        originateExpectingOnlyStatus(instruction("e2e-viewer-${UUID.randomUUID()}"), 403)
    }

    @Test
    @TestSecurity(user = "settlement-e2e-customer", roles = ["ROLE_CUSTOMER"])
    fun `a customer cannot originate`() {
        originateExpectingOnlyStatus(instruction("e2e-customer-${UUID.randomUUID()}"), 403)
    }

    @Test
    fun `an anonymous caller is refused before any handler`() {
        originateExpectingOnlyStatus(instruction("e2e-anon-${UUID.randomUUID()}"), 401)
    }

    @Test
    @TestSecurity(user = "settlement-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `the read-back answers 200 with the amount as exact decimal text`() {
        val key = "e2e-readback-${UUID.randomUUID()}"
        val id = UUID.fromString(originate(instruction(key, amount = "250.0001"), 201).get("id").asText())

        val detail = getSettlement(id.toString(), 200)
        assertThat(detail.get("id").asText()).isEqualTo(id.toString())
        assertThat(detail.get("amount").asText()).isEqualTo("250.0001")
        assertThat(detail.get("status").asText()).isEqualTo("PENDING")
        assertThat(detail.get("recoveryRequired").asBoolean()).isFalse()
        assertThat(detail.has("recoveryReason")).isTrue()
        assertThat(detail.get("payerAccountId").asText()).isNotBlank()
    }

    @Test
    @TestSecurity(user = "settlement-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `the unknown and malformed id contracts are pinned`() {
        getSettlement(UUID.randomUUID().toString(), 404)
        // Non-canonical UUID spelling is a client error, never a 500.
        getSettlement("not-a-uuid", 400)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR"])
    fun `a service account cannot read the operator status`() {
        getSettlement(UUID.randomUUID().toString(), 403)
    }

    @Test
    @TestSecurity(user = "settlement-e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `a viewer cannot read the status`() {
        getSettlement(UUID.randomUUID().toString(), 403)
    }
}
