// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.specification.RequestSpecification
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.time.Instant
import java.util.Optional
import java.util.UUID
import jakarta.enterprise.inject.Any as CdiAny

/**
 * #12378 over real HTTP, a real Postgres (V7) and the real channel wiring (in-memory connector):
 * a mandate is recorded per contract and cancelled only through that contract under SCA, and a
 * domestic-payment status event lands on the payout instruction it belongs to.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PaymentAdaptersIT {

    @Inject
    @CdiAny
    lateinit var connector: InMemoryConnector

    private val funding = "/api/v1/pension/funding/contracts"

    private fun spec(party: UUID?): RequestSpecification = given().contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .apply { if (party != null) header("X-Customer-Party-Id", party.toString()) }

    private fun seededContract(party: UUID): String {
        val id = UUID.randomUUID()
        jdbc { c ->
            c.prepareStatement(
                "INSERT INTO pension_contracts (id, contract_id, participant_party_id, product_line, jurisdiction, " +
                    "pack_version, provider_entity_id, provider_type, participant_birth_date, status, " +
                    "contribution_amount, contribution_currency, contribution_frequency, start_date, created_at, " +
                    "updated_at) VALUES (1000000000000 + (random() * 1000000000)::bigint, ?, ?, 'DPS', 'CZ', 1, ?, " +
                    "'PENSION_COMPANY', DATE '1985-05-05', 'ACTIVE', 1700, 'CZK', 'QUARTERLY', DATE '2025-01-01', " +
                    "now(), now())",
            ).use { st ->
                st.setObject(1, id)
                st.setObject(2, party)
                st.setObject(3, UUID.randomUUID())
                st.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO pension_strategy_elections (id, contract_id, strategy_code, effective_from, elected_at) " +
                    "VALUES (1000000000000 + (random() * 1000000000)::bigint, ?, 'BALANCED', DATE '2025-01-01', now())",
            ).use { st ->
                st.setObject(1, id)
                st.executeUpdate()
            }
        }
        return id.toString()
    }

    private fun mandate(owner: UUID, id: String, first: String = "2026-11-01"): String = spec(owner)
        .body(
            """{"kind":"STANDING_ORDER","debtorIban":"CZ6508000000192000145399","amount":1700,"currency":"CZK",""" +
                """"firstCollection":"$first","scaChallengeId":"sca-${UUID.randomUUID()}"}""",
        )
        .`when`().post("$funding/$id/mandates").then().statusCode(201)
        .body("status", equalTo("ACTIVE"))
        .extract().path("id")

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a mandate is cancelled only by its own contract, under a fresh SCA challenge`() {
        val owner = UUID.randomUUID()
        val id = seededContract(owner)
        val mandateId = mandate(owner, id)
        assertThat(text("SELECT status FROM pension_payment_mandates WHERE id = '$mandateId'")).isEqualTo("ACTIVE")

        // No SCA challenge over the mandate document: refused before any lookup, nothing recorded.
        spec(owner).body(
            """{"kind":"STANDING_ORDER","debtorIban":"CZ6508000000192000145399","amount":1700,"currency":"CZK",""" +
                """"firstCollection":"2027-01-01"}""",
        ).`when`().post("$funding/$id/mandates").then().statusCode(403)
        assertThat(text("SELECT count(*) FROM pension_payment_mandates WHERE contract_id = '$id'")).isEqualTo("1")

        // Someone else's account as the debtor: refused, nothing recorded.
        spec(owner).body(
            """{"kind":"STANDING_ORDER","debtorIban":"CZ5508000000001234567899","amount":1700,"currency":"CZK",""" +
                """"firstCollection":"2027-01-01","scaChallengeId":"sca-${UUID.randomUUID()}"}""",
        ).`when`().post("$funding/$id/mandates").then().statusCode(403)
        assertThat(text("SELECT count(*) FROM pension_payment_mandates WHERE contract_id = '$id'")).isEqualTo("1")

        // No challenge: refused before anything else.
        spec(owner).body("{}").`when`().post("$funding/$id/mandates/$mandateId/cancel").then().statusCode(403)

        // Another participant's contract naming this mandate: 404 (their contract is not visible).
        val stranger = UUID.randomUUID()
        spec(stranger).body("""{"scaChallengeId":"c-${UUID.randomUUID()}"}""")
            .`when`().post("$funding/$id/mandates/$mandateId/cancel").then().statusCode(404)
        // The stranger's OWN contract naming this mandate: also 404, the mandate is not theirs.
        val strangersContract = seededContract(stranger)
        spec(stranger).body("""{"scaChallengeId":"c-${UUID.randomUUID()}"}""")
            .`when`().post("$funding/$strangersContract/mandates/$mandateId/cancel").then().statusCode(404)
        assertThat(text("SELECT status FROM pension_payment_mandates WHERE id = '$mandateId'")).isEqualTo("ACTIVE")

        val challenge = "c-${UUID.randomUUID()}"
        spec(owner).body("""{"scaChallengeId":"$challenge"}""")
            .`when`().post("$funding/$id/mandates/$mandateId/cancel").then().statusCode(200)
            .body("status", equalTo("CANCELLED"))
        assertThat(text("SELECT status FROM pension_payment_mandates WHERE id = '$mandateId'")).isEqualTo("CANCELLED")

        // A consumed challenge is refused (single use), even for a new idempotency key.
        val second = mandate(owner, id, "2026-12-01")
        spec(owner).body("""{"scaChallengeId":"$challenge"}""")
            .`when`().post("$funding/$id/mandates/$second/cancel").then().statusCode(403)
        assertThat(text("SELECT status FROM pension_payment_mandates WHERE id = '$second'")).isEqualTo("ACTIVE")
    }

    @Test
    fun `a domestic-payment status change settles or rejects the payout instruction it belongs to`() {
        val contract = seededContract(UUID.randomUUID())
        val settled = UUID.randomUUID()
        val returned = UUID.randomUUID()
        instruction(contract, settled)
        instruction(contract, returned)

        val source = connector.source<ConsumerRecord<String, String>>("domestic-payment-events-in")
        source.send(event(settled, "SENT_TO_CLEARING", "domestic.payment.status-changed"))
        source.send(event(settled, "SETTLED", "domestic.payment.created")) // wrong ce-type: ignored
        source.send(event(returned, "SETTLED", "domestic.payment.status-changed"))
        source.send(event(returned, "RETURNED", "domestic.payment.status-changed"))
        source.send(event(settled, "SETTLED", "domestic.payment.status-changed"))
        source.send(event(UUID.randomUUID(), "SETTLED", "domestic.payment.status-changed")) // not ours

        await { status(settled) == "SETTLED" && status(returned) == "REJECTED" }
        assertThat(status(settled)).isEqualTo("SETTLED")
        assertThat(status(returned)).isEqualTo("REJECTED")
    }

    private fun instruction(contract: String, paymentRef: UUID) = jdbc { c ->
        c.prepareStatement(
            "INSERT INTO pension_payment_instructions (id, idempotency_key, contract_id, purpose, amount, currency, " +
                "creditor_iban, status, payment_ref, created_at, updated_at) VALUES " +
                "(1000000000000 + (random() * 1000000000)::bigint, ?, ?::uuid, 'PAYOUT', 100.00, 'CZK', " +
                "'CZ6508000000192000145399', 'SENT', ?, now(), now())",
        ).use { st ->
            st.setString(1, "pension-payout-it-$paymentRef")
            st.setString(2, contract)
            st.setString(3, paymentRef.toString())
            st.executeUpdate()
        }
    }

    private fun event(paymentId: UUID, status: String, type: String): ConsumerRecord<String, String> {
        val headers = RecordHeaders(
            listOf(RecordHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE, type.toByteArray(StandardCharsets.UTF_8))),
        )
        val at = Instant.parse("2026-10-10T12:00:00Z")
        val settlementTime = if (status == "SETTLED") ",\"settledAt\":\"$at\"" else ""
        val body = """{"paymentId":"$paymentId","previousStatus":"SENT_TO_CLEARING","newStatus":"$status",""" +
            """"occurredAt":"$at"$settlementTime}"""
        return ConsumerRecord(
            "openbank.domestic.payment.events", 0, 0L, 0L, TimestampType.CREATE_TIME, -1, -1,
            paymentId.toString(), body, headers, Optional.empty(),
        )
    }

    private fun status(paymentRef: UUID) =
        text("SELECT status FROM pension_payment_instructions WHERE payment_ref = '$paymentRef'")

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    private fun text(sql: String): String? = jdbc { c ->
        c.createStatement().use { st -> st.executeQuery(sql).use { r -> if (r.next()) r.getString(1) else null } }
    }

    private companion object {
        const val AWAIT_MS = 10_000L
        const val POLL_MS = 100L
    }
}
