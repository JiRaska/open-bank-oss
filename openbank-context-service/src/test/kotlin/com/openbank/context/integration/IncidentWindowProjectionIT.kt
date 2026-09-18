// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import jakarta.enterprise.inject.Any as AnyQualifier

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class IncidentWindowProjectionIT {
    @Inject
    @AnyQualifier
    lateinit var connector: InMemoryConnector

    @Test
    fun `newer incident revision replaces source window and stale event cannot roll it back`() {
        val incidentId = UUID.randomUUID()
        val now = Instant.now().minusSeconds(60)
        val version = now.epochSecond * 1_000_000_000 + now.nano
        val source = connector.source<String>("ict-incident-events-in")
        source.runOnVertxContext(true)
        source.send(event(incidentId, version, "OPEN", now, null))
        source.send(event(incidentId, version + 1, "CONTAINED", now, now.plusSeconds(10)))
        awaitWindow(incidentId, version + 1)
        source.send(event(incidentId, version - 1, "OPEN", now, null))
        awaitProjectionCount(incidentId, 3)

        val window = window(incidentId)
        assertThat(window?.first).isEqualTo("CONTAINED")
        assertThat(window?.second).isEqualTo(now.plusSeconds(10))
        assertThat(window?.third).isEqualTo(version + 1)
        assertThat(window(incidentId, "another-bank")).isNull()
    }

    private fun event(id: UUID, revision: Long, status: String, detected: Instant, contained: Instant?): String =
        """{"schemaVersion":1,"sourceVersion":$revision,"aggregateRevision":$revision,""" +
            """"eventType":"ICT_INCIDENT_STATUS_CHANGED","sourceService":"security-scanner",""" +
            """"occurredAt":"$detected","incident":{"id":"$id","severity":"P1_CRITICAL",""" +
            """"status":"$status","affectedServices":["ledger-service"],"detectedAt":"$detected",""" +
            """"containedAt":${contained?.let { "\"$it\"" } ?: "null"},"resolvedAt":null}}"""

    private fun awaitWindow(id: UUID, revision: Long) {
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            if (window(id)?.third == revision) return
            Thread.sleep(25)
        }
        error("Incident window revision $revision was not projected")
    }

    private fun awaitProjectionCount(id: UUID, expected: Int) {
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            if (projectionCount(id) == expected) return
            Thread.sleep(25)
        }
        error("Incident $id projection count did not reach $expected")
    }

    @Suppress("NestedBlockDepth")
    private fun window(id: UUID, scope: String = "openbank-cz"): Triple<String, Instant?, Long>? =
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """DO $$ BEGIN
                       IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'context_incident_window_test') THEN
                         CREATE ROLE context_incident_window_test NOLOGIN;
                       END IF;
                       END $$
                    """.trimIndent(),
                )
                statement.execute("GRANT SELECT ON context_incident_windows TO context_incident_window_test")
            }
            connection.autoCommit = false
            connection.createStatement().use { it.execute("SET LOCAL ROLE context_incident_window_test") }
            connection.prepareStatement("SELECT set_config('openbank.bank_scope', ?, true)").use {
                it.setString(1, scope)
                it.executeQuery().close()
            }
            connection.prepareStatement(
                "SELECT status, contained_at, source_version FROM context_incident_windows WHERE incident_id = ?",
            ).use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        Triple(
                            rows.getString(1),
                            rows.getObject(2, OffsetDateTime::class.java)?.toInstant(),
                            rows.getLong(3),
                        )
                    }
                }
            }
        }

    private fun projectionCount(id: UUID): Int = connection().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM context_projection_events WHERE aggregate_ref = ?").use {
            it.setString(1, "incident:$id")
            it.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun connection() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    )
}
