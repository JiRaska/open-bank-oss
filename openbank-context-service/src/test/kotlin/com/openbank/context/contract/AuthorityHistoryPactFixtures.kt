// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.contract

import org.eclipse.microprofile.config.ConfigProvider
import java.sql.DriverManager
import java.util.UUID

class AuthorityHistoryPactFixtures {
    fun seed() {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """INSERT INTO context_case_assignments
                        (assignment_id, bank_scope, principal_id, case_id, purpose, root_ref,
                         valid_from, valid_to, created_at)
                        VALUES ('${UUID.randomUUID()}', 'openbank-cz', 'pact-operator', 'history-case',
                        'AUTHORIZATION_REVIEW', 'delegation:44444444-4444-4444-4444-444444444444',
                        now() - interval '1 hour', now() + interval '1 hour', now())
                    """.trimIndent(),
                )
            }
        }
    }
}
