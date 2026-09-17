// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.contract

import org.eclipse.microprofile.config.ConfigProvider
import java.sql.DriverManager
import java.util.UUID

class AmlCasePactFixtures {
    fun seed() {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("SELECT set_config('openbank.bank_scope', 'openbank-cz', true)")
                statement.executeUpdate(
                    """INSERT INTO context_case_assignments
                        (assignment_id, bank_scope, principal_id, case_id, purpose, root_ref,
                         valid_from, valid_to, created_at)
                        VALUES ('${UUID.randomUUID()}', 'openbank-cz', 'pact-operator', '$CASE',
                        'AML_INVESTIGATION', 'aml-case:$CASE',
                        now() - interval '1 hour', now() + interval '1 hour', now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """INSERT INTO context_aml_case_evidence
                        (bank_scope, event_id, case_id, party_id, event_type, occurred_at,
                         recorded_at, evidence, content_hash)
                        VALUES ('openbank-cz', '$EVENT', '$CASE', '$PARTY', 'aml.case.created.v1',
                        '2026-02-01T00:00:00Z', '2026-02-02T00:00:00Z', '$EVIDENCE', '${"a".repeat(64)}')
                        ON CONFLICT (bank_scope, event_id) DO NOTHING
                    """.trimIndent(),
                )
            }
            connection.commit()
        }
    }

    private companion object {
        const val CASE = "66666666-6666-4666-8666-666666666666"
        const val EVENT = "77777777-7777-4777-8777-777777777777"
        const val PARTY = "88888888-8888-4888-8888-888888888888"
        val EVIDENCE = """{"eventId":"$EVENT","caseId":"$CASE","partyId":"$PARTY",
            "accountId":null,"transactionId":null,"eventType":"aml.case.created.v1",
            "status":"OPEN","previousStatus":null,"riskLevel":"LOW","screeningType":"MANUAL_INVESTIGATION",
            "occurredAt":"2026-02-01T00:00:00Z"}"""
    }
}
