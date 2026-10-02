// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.contract

import io.quarkus.test.junit.QuarkusTestProfile
import org.eclipse.microprofile.config.ConfigProvider
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID

class IncidentImpactPactProfile : QuarkusTestProfile {
    override fun getConfigOverrides() = mapOf("openbank.context.max-edges" to "2")
}

/** The third edge exceeds the real query limit; no response or repository is mocked. */
class IncidentImpactPactFixtures {
    fun seed(services: Int?) {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM context_edges WHERE from_key = 'incident:incident-1'")
                statement.executeUpdate("DELETE FROM context_nodes WHERE source_system = 'pact-fixture'")
                statement.executeUpdate("DELETE FROM context_case_assignments WHERE principal_id = 'pact-operator'")
                statement.executeUpdate(
                    """INSERT INTO context_case_assignments
                        (assignment_id, bank_scope, principal_id, case_id, purpose, root_ref, valid_from, valid_to, created_at)
                        VALUES ('${UUID.randomUUID()}', 'openbank-cz', 'pact-operator', 'case-1', 'INCIDENT_IMPACT',
                        'incident:incident-1', now() - interval '1 hour', now() + interval '1 hour', now())
                    """.trimIndent(),
                )
                seedProjection(statement, services)
            }
            connection.commit()
        }
    }

    private fun seedProjection(statement: Statement, services: Int?) {
        if (services != null) {
            (0..services).forEach { index ->
                val key = if (index == 0) "incident:incident-1" else "service:pact-$index"
                val type = if (index == 0) "INCIDENT" else "SERVICE"
                statement.executeUpdate(
                    """INSERT INTO context_nodes
                        (node_row_id, node_key, bank_scope, projection_generation, namespace, node_type,
                        source_system, source_ref, display_label, classification, valid_from, recorded_at, source_version)
                        VALUES ('${UUID.randomUUID()}', '$key', 'openbank-cz', 1, 'INCIDENT', '$type',
                        'pact-fixture', '$key', '$key', 'INTERNAL', now() - interval '1 hour', now(), 1)
                    """.trimIndent(),
                )
                if (index > 0) {
                    statement.executeUpdate(
                        """INSERT INTO context_edges
                            (edge_id, bank_scope, projection_generation, namespace, from_key, to_key,
                            relation_type, source_system, evidence_ref, valid_from, recorded_at, source_version)
                            VALUES ('${UUID.randomUUID()}', 'openbank-cz', 1, 'INCIDENT', 'incident:incident-1',
                            '$key', 'AFFECTS', 'pact-fixture', 'evidence:$index', now() - interval '1 hour', now(), 1)
                        """.trimIndent(),
                    )
                }
            }
        }
    }
}
