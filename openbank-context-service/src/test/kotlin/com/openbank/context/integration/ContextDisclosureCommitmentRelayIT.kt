// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.context.application.ContextDisclosure
import com.openbank.context.application.ContextDisclosureAudit
import com.openbank.context.application.ContextReadAudit
import com.openbank.context.application.ContextReadAuditPort
import com.openbank.context.infrastructure.ContextDisclosureCommitmentRelay
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@QuarkusTest
@TestProfile(ContextDisclosureExportProfile::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_disclosure_relay_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class ContextDisclosureCommitmentRelayIT {
    @Inject lateinit var audit: ContextReadAuditPort

    @Inject lateinit var relay: ContextDisclosureCommitmentRelay

    @Inject lateinit var mapper: ObjectMapper

    @Inject
    @Connector("smallrye-in-memory")
    lateinit var connector: InMemoryConnector

    @Test
    fun `disclosure is exported as a minimized commitment after atomic local write`() {
        val decision = ContextReadAudit(
            principalId = "synthetic-investigator",
            caseId = "synthetic-case",
            purpose = "PAYMENT_COMPLAINT",
            action = "context.complaint.read",
            rootRef = "complaint:synthetic-root",
            decision = "ALLOWED",
            policyVersion = "policy-v1",
            reasonCode = "POLICY_ALLOWED",
            occurredAt = Instant.parse("2026-09-18T12:00:00Z"),
        )
        onEventLoop {
            audit.record(decision)
            audit.recordDisclosure(
                ContextDisclosureAudit(
                    decision.id,
                    "a".repeat(64),
                    ContextDisclosure(listOf("synthetic-evidence-ref"), 1, false, 1),
                    Instant.parse("2026-09-18T12:00:01.123456789Z"),
                ),
            )
            relay.dispatch()
            relay.dispatch()
        }

        val messages = connector.sink<String>("context-audit-commitments-out").received()
        assertThat(messages).hasSize(1)
        val message = messages.single() as Message<String>
        val node = mapper.readTree(message.payload)
        assertThat(node.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "schemaVersion",
            "eventId",
            "eventType",
            "aggregateType",
            "aggregateId",
            "sourceService",
            "occurredAt",
            "commitment",
        )
        assertThat(node.path("eventType").asText()).isEqualTo("CONTEXT_DISCLOSURE_COMMITTED")
        assertThat(node.path("aggregateType").asText()).isEqualTo("CONTEXT_DISCLOSURE")
        assertThat(message.payload).doesNotContain("synthetic-investigator", "synthetic-case", "synthetic-evidence-ref")
        val id = UUID.fromString(node.path("eventId").asText())
        assertThat(message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow().key)
            .isEqualTo(id.toString())
        assertStoredCommitment(id, node.path("commitment").asText())
    }

    private fun assertStoredCommitment(id: UUID, digest: String) {
        DriverManager.getConnection(
            ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
            ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
            ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement(
                "SELECT status, commitment, attempt_count FROM context_disclosure_commitment_outbox WHERE disclosure_id = ?",
            ).use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("status")).isEqualTo("SENT")
                    assertThat(rows.getString("commitment")).isEqualTo(digest)
                    assertThat(rows.getInt("attempt_count")).isEqualTo(1)
                }
            }
        }
    }

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }
}

class ContextDisclosureExportProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "openbank.context.disclosure-export.enabled" to "true",
        "quarkus.scheduler.enabled" to "false",
    )
}
