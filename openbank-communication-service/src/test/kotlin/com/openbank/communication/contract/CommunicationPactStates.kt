// SPDX-License-Identifier: AGPL-3.0-only
package com.openbank.communication.contract

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

object CommunicationPactStates {
    const val PUBLISHED = "customer copilot has published mobile copy"
    const val UNPUBLISHED = "customer copilot has no published style"

    fun published(dataSource: DataSource) {
        unpublished(dataSource)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """insert into style_version
                    (id, persona_id, version, status, tone, formality, form_of_address,
                     preferred_terms, forbidden_terms, maker, created_at, published_at, ui_messages)
                    select ?, id, 2, 'PUBLISHED', 'warm', 'informal', 'tykani',
                           '{}', '[]', 'pact-maker', ?, ?, ? from persona where key = 'customer-copilot'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setTimestamp(2, Timestamp.from(Instant.now()))
                statement.setTimestamp(3, Timestamp.from(Instant.now()))
                statement.setString(4, """{"cs.status.loading":"Hledám."}""")
                check(statement.executeUpdate() == 1)
            }
        }
    }

    fun unpublished(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "delete from style_version where persona_id = (select id from persona where key = 'customer-copilot')",
            ).use { it.executeUpdate() }
        }
    }
}
