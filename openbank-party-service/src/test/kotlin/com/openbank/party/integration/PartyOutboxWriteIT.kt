// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.party.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #4007: `party_outbox` had a dispatcher, a backlog gauge and `dispatch-enabled: true`, and
 * NOTHING ever wrote to it — every party lifecycle event went out through a direct
 * `@Channel("party-events-out")` emitter, outside any transaction, so the state change and the
 * event could diverge in either direction (emit fails after commit: the event is lost with no
 * record; emit succeeds and the transaction rolls back: a consumer acts on something that did not
 * happen).
 *
 * Only a real-DB integration test can prove the fix. A unit test that mocks the repository cannot
 * tell which publisher a use case called — that is exactly why the defect survived a fully green
 * suite for the life of the service. And the repository cannot be called directly either: a
 * `Panache.withTransaction` reactive repo invoked from a bare `@QuarkusTest` thread throws
 * "No current Vertx context found"; only a real HTTP request carries a Vert.x context. So this
 * drives the REST endpoints with RestAssured and reads the row back over plain JDBC —
 * the `ConsentRevocationOutboxIT` / `LendingOutboxWriteIT` pattern.
 *
 * The dispatcher is switched off for the duration so it cannot mark a row SENT (or drain it)
 * before the assertion observes it — the claim under test is that the row is WRITTEN in the
 * state-change transaction, not what happens to it afterwards.
 */
@QuarkusTest
@QuarkusTestResource(PartyOutboxWriteIT.DispatcherOffResource::class)
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class PartyOutboxWriteIT {

    class DispatcherOffResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "false")
        override fun stop() = Unit
    }

    @Inject
    lateinit var dataSource: DataSource

    private fun createParty(email: String): UUID {
        val body = """
            {"partyType":"INDIVIDUAL","legalName":"Outbox Probe","tradingName":null,
             "dateOfBirth":"1990-01-01","nationality":"CZ","taxId":null,"registrationNumber":null,
             "email":"$email","phone":null,"address":null}
        """.trimIndent()
        val id = Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(body)
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }
        assertThat(id).isNotBlank()
        return UUID.fromString(id)
    }

    // conn.use closes the connection, cascading to its statement/result-set — kept flat to stay
    // within detekt's NestedBlockDepth.
    private fun outboxRows(aggregateId: UUID): List<Triple<String, String, String>> =
        dataSource.connection.use { conn ->
            val ps = conn.prepareStatement(
                "SELECT event_type, payload, status FROM party_outbox WHERE aggregate_id = ? ORDER BY created_at",
            )
            ps.setObject(1, aggregateId)
            val rs = ps.executeQuery()
            val rows = mutableListOf<Triple<String, String, String>>()
            while (rs.next()) {
                rows += Triple(rs.getString("event_type"), rs.getString("payload"), rs.getString("status"))
            }
            rows
        }

    private fun partyStatus(id: UUID): String? = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement("SELECT kyc_status FROM parties WHERE party_id = ?")
        ps.setObject(1, id)
        val rs = ps.executeQuery()
        if (rs.next()) rs.getString("kyc_status") else null
    }

    @Test
    @TestSecurity(user = "outbox-it", roles = ["ROLE_ADMIN"])
    fun `creating a party writes PARTY_CREATED to the outbox in the same transaction as the row`() {
        val id = createParty("outbox-create-${UUID.randomUUID()}@example.cz")

        val rows = outboxRows(id)
        assertThat(rows).describedAs("party_outbox rows for party %s", id).hasSize(1)
        val (eventType, payload, status) = rows.single()
        assertThat(eventType).isEqualTo("PARTY_CREATED")
        assertThat(status).isEqualTo("PENDING")
        // The payload is the same flat envelope consumers already parse (PartyEvents) — the
        // outbox changed WHERE the event is written, not WHAT is on the wire.
        assertThat(payload).contains("\"eventType\":\"PARTY_CREATED\"")
        assertThat(payload).contains("\"partyId\":\"$id\"")
        assertThat(payload).contains("\"partyType\":\"INDIVIDUAL\"")
        assertThat(payload).doesNotContain("\"aggregateId\"")
    }

    @Test
    @TestSecurity(user = "outbox-it", roles = ["ROLE_ADMIN", "ROLE_KYC"])
    fun `a KYC status change writes KYC_STATUS_CHANGED alongside the updated row`() {
        val id = createParty("outbox-kyc-${UUID.randomUUID()}@example.cz")

        Given {
            contentType("application/json")
            body("""{"kycStatus":"APPROVED"}""")
        } When {
            put("/api/v1/parties/$id/kyc-status")
        } Then {
            statusCode(200)
        }

        // Both halves of the guarantee, asserted against the DB rather than against a mock: the
        // state change landed AND its event landed, in the same commit.
        assertThat(partyStatus(id)).describedAs("parties.kyc_status after the update").isEqualTo("APPROVED")

        val types = outboxRows(id).map { it.first }
        assertThat(types).containsExactly("PARTY_CREATED", "KYC_STATUS_CHANGED")
    }
}
