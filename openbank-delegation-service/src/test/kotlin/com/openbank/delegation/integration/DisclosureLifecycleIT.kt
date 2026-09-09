// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/** Proves the disclosure request and its snapshot command commit atomically through the real HTTP path. */
@QuarkusTest
@QuarkusTestResource(DisclosureLifecycleIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class DisclosureLifecycleIT {
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("disclosure-snapshot-events-in") +
                InMemoryConnector.switchOutgoingChannelsToInMemory(
                    "delegation-events-out",
                    "spend-reservation-state-out",
                    "approval-group-revisions-out",
                )

        override fun stop() = InMemoryConnector.clear()
    }

    private val mapper = ObjectMapper()

    @Test
    @TestSecurity(user = "customer", roles = ["ROLE_API"])
    fun `preparing an eligible disclosure atomically persists request and snapshot command`() {
        val grantor = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        val grantId = seedDocumentGrant(grantor, documentId)
        val requestId = UUID.randomUUID()
        val before = Instant.now()

        val response = RestAssured.given()
            .contentType(ContentType.JSON)
            .body("{}")
            .header("X-Request-ID", requestId.toString())
            .header("X-Customer-Party-Id", grantor.toString())
            .post("/api/v1/disclosures/delegations/$grantId")
            .then().log().ifValidationFails().statusCode(202).extract()

        val disclosureId = UUID.fromString(response.path("id"))
        assertThat(response.path<String>("status")).isEqualTo("REQUESTED")
        jdbc().use { connection ->
            connection.prepareStatement(
                """
                SELECT d.request_id, d.source_document_id, d.status, o.event_type, o.payload
                FROM delegation_disclosures d
                JOIN delegation_outbox o ON o.aggregate_id = d.id
                WHERE d.id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, disclosureId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject("request_id", UUID::class.java)).isEqualTo(requestId)
                    assertThat(rows.getObject("source_document_id", UUID::class.java)).isEqualTo(documentId)
                    assertThat(rows.getString("status")).isEqualTo("REQUESTED")
                    assertThat(rows.getString("event_type")).isEqualTo("DisclosureSnapshotRequested")
                    val event = mapper.readTree(rows.getString("payload"))
                    assertThat(event.get("requestId").asText()).isEqualTo(requestId.toString())
                    assertThat(event.get("sourceDocumentId").asText()).isEqualTo(documentId.toString())
                    assertThat(event.get("expectedPartyRef").asText()).isEqualTo(grantor.toString())
                    assertThat(Instant.parse(event.get("occurredAt").asText())).isBetween(before, Instant.now())
                    assertThat(rows.next()).isFalse()
                }
            }
        }
    }

    private fun seedDocumentGrant(grantor: UUID, documentId: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO delegation_grants
                    (id, grantor_party_id, grantee_party_id, resource_type, resource_id, approval_policy,
                     valid_from, valid_to, status, created_at, updated_at)
                VALUES (?, ?, ?, 'DOCUMENT', ?, 'SOLO', now() - interval '1 day',
                        now() + interval '30 days', 'ACTIVE', now(), now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, grantor)
                statement.setObject(3, UUID.randomUUID())
                statement.setObject(4, documentId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO delegation_capabilities (grant_id, capability) VALUES (?, 'OBJECT_READ')",
            ).use { statement ->
                statement.setObject(1, id)
                statement.executeUpdate()
            }
        }
        return id
    }

    private fun jdbc() = ConfigProvider.getConfig().let { config ->
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }
}
