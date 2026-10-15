// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.integration

import com.openbank.agent.it.TraceContractProfile
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The observable distributed shape of an agent proposal decision.
 *
 * The spans are the service's OWN — Quarkus' OpenTelemetry auto-instrumentation produces them
 * for the real HTTP request and the real Postgres write; `TraceContractRecorder` only adds an
 * in-memory `SpanProcessor` so a contract can read what an OTLP collector would receive.
 * Nothing asserted here is created by the test, so every assertion can fail.
 *
 * `requiresSameTrace` is the load-bearing one: it says the compare-and-set UPDATE of
 * `agent_proposal` happened *inside* the decision request's trace. A decision persisted
 * outside the request context (a retry on another thread, a detached connection) still
 * answers 200 and reports the new state, but lands in traces of its own — a governed
 * human-over-ai approval changed state without an observable approver.
 */
@QuarkusTest
// Keep this resource's model-key placeholder out of other profiles (ModelGatewayRoutingOverrideTest).
@QuarkusTestResource(com.openbank.agent.it.PostgresTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(TraceContractProfile::class)
class AgentProposalDecisionTraceContractIT {

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Inject
    lateinit var dataSource: DataSource

    /**
     * Seeds a PROPOSED row the decision can flip. Seeding over JDBC keeps the seed itself out
     * of the recorder, so `hasNoErrorSpan()` speaks only about the request under contract; the
     * author differs from the deciding principal because segregation of duties (# the service
     * requires decidedBy != proposedBy) makes self-approval a 409.
     */
    private fun seedProposedProposal(id: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """insert into agent_proposal
                    (id,title,rationale,suggested_action,proposed_by,proposed_at,state,model_id,
                     correlation_id,metadata)
                    values (?,?,?,?,?,?,?,?,?,?::jsonb)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, "trace contract proposal")
                statement.setString(3, "r")
                statement.setString(4, "review")
                statement.setString(5, "agent-trace-author")
                statement.setTimestamp(6, Timestamp.from(Instant.now()))
                statement.setString(7, "PROPOSED")
                statement.setString(8, "trace-model")
                statement.setString(9, "trace-correlation")
                statement.setObject(10, "{}", Types.OTHER)
                statement.executeUpdate()
            }
            if (!connection.autoCommit) connection.commit()
        }
    }

    @Test
    @TestSecurity(user = "operator-approver", roles = ["ROLE_OPERATOR"])
    fun `deciding a proposal keeps the request and its decision UPDATE in one trace`() {
        val proposalId = UUID.randomUUID()
        seedProposedProposal(proposalId)

        Given {
            contentType("application/json")
            body("""{"approve":true,"reason":"trace-contract"}""")
        } When {
            post("/api/v1/proposals/$proposalId/decision")
        } Then {
            statusCode(200)
        }

        // JDBC (not Hibernate) instrumentation names client spans with the schema-qualified
        // table, matching what the module's PostgresTestResource creates.
        exporter.contract()
            .requiresSpan("POST /api/v1/proposals/{id}/decision")
            .requiresAttribute("POST /api/v1/proposals/{id}/decision", "http.response.status_code")
            .requiresAttribute("UPDATE openbank_agent_it.agent_proposal", "db.statement")
            .requiresSameTrace(
                "POST /api/v1/proposals/{id}/decision",
                "UPDATE openbank_agent_it.agent_proposal",
            )
            .hasNoErrorSpan()
            .verifiedAs("agent-proposal-decision")
    }
}
