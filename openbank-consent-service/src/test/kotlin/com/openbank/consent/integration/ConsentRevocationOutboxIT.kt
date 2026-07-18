// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.integration

import com.openbank.consent.it.ConsentPostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-0126 §D3: a consent revocation/rejection MUST be written to the transactional outbox in the
 * SAME transaction as the status change, not fired-and-forgotten to a direct Kafka emitter (a
 * dual-write that silently drops the event on a crash between the DB commit and the send).
 *
 * Drives create -> revoke / reject through the real REST endpoints (a direct CDI call into a
 * @WithTransaction repository from the bare test thread fails with "No current Vertx context
 * found"; only an HTTP request carries one — see LendingOutboxWriteIT) and asserts the outbox row
 * lands via a plain JDBC read. The scheduled dispatcher is disabled so it cannot mark the row SENT
 * before the assertion observes it.
 */
@QuarkusTest
@QuarkusTestResource(ConsentRevocationOutboxIT.InMemoryKafkaResource::class)
@QuarkusTestResource(ConsentPostgresRedisTestResource::class)
class ConsentRevocationOutboxIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("consent-events-out").toMutableMap()
            props["openbank.outbox.dispatch-enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dataSource: DataSource

    private fun createConsent(partyId: UUID): UUID {
        val body = """
            {"partyId":"$partyId","granteeId":"tpp-it","granteeType":"TPP","granteeName":"IT TPP",
            "scopes":["ACCOUNTS_READ"],"accountIbans":["CZ6508000000192000145399"],
            "validTo":"${OffsetDateTime.now().plusDays(30)}","redirectUri":null,"tppTransactionId":null}
        """.trimIndent()
        val id = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/consents")
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
    private fun outboxRow(aggregateId: UUID): Pair<String, String>? = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement("SELECT event_type, payload FROM consent_outbox WHERE aggregate_id = ?")
        ps.setObject(1, aggregateId)
        val rs = ps.executeQuery()
        if (rs.next()) rs.getString("event_type") to rs.getString("payload") else null
    }

    private fun consentStatus(id: UUID): String? = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement("SELECT status FROM consents WHERE id = ?")
        ps.setObject(1, id)
        val rs = ps.executeQuery()
        if (rs.next()) rs.getString("status") else null
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `revoke writes ConsentRevoked to the outbox in the same transaction as the status change`() {
        val partyId = UUID.randomUUID()
        val id = createConsent(partyId)

        Given {
            contentType("application/json")
            queryParam("partyId", partyId.toString())
            body("""{"reason":"customer request"}""")
        } When {
            delete("/api/v1/consents/$id")
        } Then {
            statusCode(200)
        }

        assertThat(consentStatus(id)).describedAs("consents.status after revoke").isEqualTo("REVOKED")

        val row = outboxRow(id)
        assertThat(row).describedAs("a consent_outbox row for revoked consent $id").isNotNull()
        assertThat(row!!.first).isEqualTo("ConsentRevoked")
        assertThat(row.second).contains("customer request")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `reject writes ConsentRejected to the outbox in the same transaction as the status change`() {
        val id = createConsent(UUID.randomUUID())

        Given {
            contentType("application/json")
            queryParam("reason", "declined")
        } When {
            post("/api/v1/consents/$id/reject")
        } Then {
            statusCode(200)
        }

        assertThat(consentStatus(id)).describedAs("consents.status after reject").isEqualTo("REJECTED")

        val row = outboxRow(id)
        assertThat(row).describedAs("a consent_outbox row for rejected consent $id").isNotNull()
        assertThat(row!!.first).isEqualTo("ConsentRejected")
    }
}
