// SPDX-License-Identifier: AGPL-3.0-only
package com.openbank.communication.contract

import java.util.UUID
import javax.sql.DataSource

/** Sets legitimate persisted publication states; verification still calls the real HTTP resource. */
internal object CommunicationPactStates {
    fun configure(dataSource: DataSource, published: Boolean) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            val personaId = connection.prepareStatement("select id from persona where key = ?").use { statement ->
                statement.setString(1, "customer-copilot")
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "customer persona must be seeded by Flyway" }
                    rows.getObject(1, UUID::class.java)
                }
            }
            connection.prepareStatement(
                """update style_version set status = 'RETIRED', retired_at = now()
                    where persona_id = ? and status = 'PUBLISHED'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, personaId)
                statement.executeUpdate()
            }
            if (published) {
                connection.prepareStatement(
                    """insert into style_version
                        (id, persona_id, version, status, tone, formality, form_of_address,
                         preferred_terms, forbidden_terms, maker, created_at, decided_by, decided_at,
                         published_at, ui_messages, base_published_version)
                        select ?, ?, coalesce(max(version), 0) + 1, 'PUBLISHED', 'warm', 'informal', 'tykani',
                         '{}', '[]', 'pact-maker', now(), 'pact-checker', now(), now(), ?, 0
                        from style_version where persona_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, personaId)
                    statement.setString(3, """{"cs.status.loading":"Hledam.","en.status.loading":"Checking."}""")
                    statement.setObject(4, personaId)
                    statement.executeUpdate()
                }
            }
            connection.commit()
        }
    }
}
