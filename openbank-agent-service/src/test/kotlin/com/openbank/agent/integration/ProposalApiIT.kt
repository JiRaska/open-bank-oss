// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.integration

import com.openbank.agent.application.ProposalService
import com.openbank.agent.domain.proposal.ProposalState
import com.openbank.agent.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * First integration test for agent-service (ADR-0011 L2): boots the full Quarkus app against an
 * isolated PostgreSQL (Flyway migrates, Agroal datasource, CDI wiring all exercised) and drives the
 * proposal review surface end-to-end through HTTP. The proposal store is empty on a fresh container,
 * so listing pending proposals returns an empty array — a deterministic boot+query smoke that also
 * locks the RBAC boundary on the maker-checker endpoint (ADR-0031).
 */
@QuarkusTest
// restrictToAnnotatedClass=true (explicit, not just relying on the framework default): without it,
// PostgresTestResource's injected `agent.model.openai.api-key=test-not-used` placeholder can leak
// into other @QuarkusTest classes sharing the same test JVM whose own @TestProfile expects an empty
// key (e.g. OpenAiCompatibleModelProviderBootTest) — surfaced by the Quarkus 3.37.2 BOM bump, which
// let this un-isolated resource's config win over a sibling class's TestProfile override.
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
class ProposalApiIT {

    @Inject
    lateinit var service: ProposalService

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `GET proposals returns an empty list on a fresh store`() {
        given()
            .`when`().get("/api/v1/proposals?state=pending")
            .then()
            .statusCode(200)
            .body("size()", `is`(0))
    }

    @Test
    fun `GET proposals without an authenticated role is rejected`() {
        given()
            .`when`().get("/api/v1/proposals?state=pending")
            .then()
            .statusCode(anyOf(equalTo(401), equalTo(403)))
    }

    @Test
    fun `POST decision without an authenticated role is rejected`() {
        given()
            .contentType("application/json")
            .body(mapOf("approve" to true))
            .`when`().post("/api/v1/proposals/00000000-0000-4000-8000-000000000001/decision")
            .then()
            .statusCode(anyOf(equalTo(401), equalTo(403)))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `GET proposals with agentId only returns that agent's own proposals`() {
        service.create(
            title = "finops proposal",
            rationale = "r",
            suggestedAction = "a",
            proposedBy = "finops-agent-itest",
            modelId = null,
            correlationId = null,
        )
        service.create(
            title = "devops proposal",
            rationale = "r",
            suggestedAction = "a",
            proposedBy = "devops-agent-itest",
            modelId = null,
            correlationId = null,
        )

        given()
            .`when`().get("/api/v1/proposals?state=all&agentId=finops-agent-itest")
            .then()
            .statusCode(200)
            .body("size()", `is`(1))
            .body("[0].proposedBy", `is`("finops-agent-itest"))
            .body("[0].title", `is`("finops proposal"))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `decision without legacy reviewer audits the authenticated principal`() {
        val proposal = service.create(
            title = "spoofing guard",
            rationale = "r",
            suggestedAction = "a",
            proposedBy = "maker-agent-itest",
            modelId = null,
            correlationId = null,
        )

        given()
            .contentType("application/json")
            .body(mapOf("approve" to true, "reason" to "verified"))
            .`when`().post("/api/v1/proposals/${proposal.id}/decision")
            .then()
            .statusCode(200)
            .body("decidedBy", `is`("operator"))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `forged reviewer cannot bypass separation of duties`() {
        val proposal = service.create(
            title = "self approval guard",
            rationale = "r",
            suggestedAction = "a",
            proposedBy = "operator",
            modelId = null,
            correlationId = null,
        )

        given()
            .contentType("application/json")
            .body(mapOf("approve" to true, "decidedBy" to "different-reviewer"))
            .`when`().post("/api/v1/proposals/${proposal.id}/decision")
            .then()
            .statusCode(409)
    }

    @Test
    fun `proposal creation rolls back when durable audit enqueue fails`() {
        val proposedBy = "audit-rollback-${UUID.randomUUID()}"

        try {
            withFailingAuditOutboxInsert {
                assertThatThrownBy {
                    service.create(
                        title = "audit rollback",
                        rationale = "r",
                        suggestedAction = "a",
                        proposedBy = proposedBy,
                        modelId = null,
                        correlationId = null,
                    )
                }.hasStackTraceContaining("injected audit outbox failure")
            }
            assertThat(count("SELECT count(*) FROM agent_proposal WHERE proposed_by = ?", proposedBy)).isZero()
            assertThat(
                count(
                    "SELECT count(*) FROM agent_audit_outbox " +
                        "WHERE payload::jsonb ->> 'actorId' = ? " +
                        "AND payload::jsonb ->> 'eventType' = 'agent.proposal.created'",
                    proposedBy,
                ),
            ).isZero()
        } finally {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "DELETE FROM agent_audit_outbox WHERE payload::jsonb ->> 'actorId' = ?",
                ).use { statement ->
                    statement.setString(1, proposedBy)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM agent_proposal WHERE proposed_by = ?").use { statement ->
                    statement.setString(1, proposedBy)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `proposal decision rolls back when durable audit enqueue fails`() {
        val proposal = service.create(
            title = "decision audit rollback",
            rationale = "r",
            suggestedAction = "a",
            proposedBy = "maker-${UUID.randomUUID()}",
            modelId = null,
            correlationId = null,
        )

        try {
            withFailingAuditOutboxInsert {
                assertThatThrownBy {
                    service.decide(
                        proposal.id,
                        approve = true,
                        decidedBy = "reviewer-${UUID.randomUUID()}",
                        reason = "r",
                    )
                }.hasStackTraceContaining("injected audit outbox failure")
            }
            assertThat(service.get(proposal.id)?.state).isEqualTo(ProposalState.PROPOSED)
            assertThat(
                count(
                    "SELECT count(*) FROM agent_audit_outbox " +
                        "WHERE payload::jsonb ->> 'aggregateId' = ? " +
                        "AND payload::jsonb ->> 'eventType' = 'agent.proposal.decided'",
                    proposal.id.toString(),
                ),
            ).isZero()
        } finally {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "DELETE FROM agent_audit_outbox WHERE payload::jsonb ->> 'aggregateId' = ?",
                ).use { statement ->
                    statement.setString(1, proposal.id.toString())
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM agent_proposal WHERE id = ?").use { statement ->
                    statement.setObject(1, proposal.id)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun withFailingAuditOutboxInsert(block: () -> Unit) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """CREATE OR REPLACE FUNCTION fail_agent_audit_outbox_insert() RETURNS trigger AS $$
                       BEGIN RAISE EXCEPTION 'injected audit outbox failure'; END;
                       $$ LANGUAGE plpgsql""",
                )
                statement.execute(
                    """CREATE TRIGGER fail_agent_audit_outbox_insert
                       BEFORE INSERT ON agent_audit_outbox
                       FOR EACH ROW EXECUTE FUNCTION fail_agent_audit_outbox_insert()""",
                )
            }
        }
        try {
            block()
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER IF EXISTS fail_agent_audit_outbox_insert ON agent_audit_outbox")
                    statement.execute("DROP FUNCTION IF EXISTS fail_agent_audit_outbox_insert()")
                }
            }
        }
    }

    private fun count(sql: String, value: String): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, value)
            statement.executeQuery().use { result ->
                result.next()
                result.getLong(1)
            }
        }
    }
}
