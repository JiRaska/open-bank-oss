// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.context.application.ContextReadAudit
import com.openbank.context.application.ContextReadAuditPort
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
@TestProfile(ContextAuditExportProfile::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_audit_relay_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class ContextAuditCommitmentRelayIT {
    @Inject lateinit var audit: ContextReadAuditPort

    @Inject lateinit var mapper: ObjectMapper

    @Inject
    @Connector("smallrye-in-memory")
    lateinit var connector: InMemoryConnector

    @Test
    fun `real scheduler relays committed read audit only as a digest and marks the row sent`() {
        val caseId = "case-${UUID.randomUUID()}"
        val root = "complaint:${UUID.randomUUID()}"
        onEventLoop {
            audit.record(
                ContextReadAudit(
                    principalId = "synthetic-investigator",
                    caseId = caseId,
                    purpose = "PAYMENT_COMPLAINT",
                    action = "context.complaint.read",
                    rootRef = root,
                    decision = "ALLOWED",
                    policyVersion = "policy-v1",
                    reasonCode = "POLICY_ALLOWED",
                    occurredAt = Instant.parse("2026-09-18T12:00:00.123456789Z"),
                ),
            )
        }

        awaitScheduledPublication()
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
        assertThat(message.payload).doesNotContain(caseId, root, "synthetic-investigator")
        assertThat(node.path("commitment").asText()).matches("[0-9a-f]{64}")
        val id = UUID.fromString(node.path("eventId").asText())
        assertThat(message.getMetadata(OutgoingKafkaRecordMetadata::class.java).orElseThrow().key)
            .isEqualTo(id.toString())
        assertEventuallySent(id)
    }

    private fun assertEventuallySent(id: UUID) {
        DriverManager.getConnection(
            ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
            ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
            ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement(
                "SELECT status, attempt_count FROM context_audit_commitment_outbox WHERE audit_id = ?",
            ).use { statement ->
                statement.setObject(1, id)
                awaitSentStatus(statement)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("status")).isEqualTo("SENT")
                    assertThat(rows.getInt("attempt_count")).isEqualTo(1)
                }
            }
        }
    }

    private fun awaitSentStatus(statement: java.sql.PreparedStatement) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        var sent = false
        while (!sent && System.nanoTime() < deadline) {
            sent = statement.executeQuery().use { rows -> rows.next() && rows.getString("status") == "SENT" }
            if (!sent) Thread.sleep(50)
        }
    }

    private fun awaitScheduledPublication() {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        while (connector.sink<String>("context-audit-commitments-out").received().isEmpty() &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(100)
        }
    }

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }
}

class ContextAuditExportProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "openbank.context.audit-export.enabled" to "true",
        "quarkus.scheduler.enabled" to "true",
    )
}
