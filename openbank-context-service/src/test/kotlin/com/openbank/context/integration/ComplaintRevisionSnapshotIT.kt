// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.context.domain.ContextNamespace
import com.openbank.context.infrastructure.AssignmentAdministrationService
import com.openbank.context.infrastructure.ComplaintProjectionConsumer
import com.openbank.context.infrastructure.ContextGraphRepository
import com.openbank.context.infrastructure.ProposeAssignmentRequest
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_complaint_revisions_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class ComplaintRevisionSnapshotIT {
    @Inject
    lateinit var consumer: ComplaintProjectionConsumer

    @Inject
    lateinit var graph: ContextGraphRepository

    @Inject
    lateinit var assignments: AssignmentAdministrationService

    @Test
    fun `out of order complaint revisions remain immutable while current graph stays latest`() {
        val complaintId = UUID.randomUUID()
        val reference = "CMP-REVISION-${UUID.randomUUID()}"
        val account = UUID.randomUUID()
        val priorTransaction = UUID.randomUUID()
        val currentTransaction = UUID.randomUUID()
        val prior = event(complaintId, reference, 1, "complaint.received", "RECEIVED", account, priorTransaction)
        val current = event(complaintId, reference, 2, "complaint.resolved", "RESOLVED", account, currentTransaction)

        onVertx { consumer.consume(current) }
        onVertx { consumer.consume(prior) }
        onVertx { consumer.consume(prior) }
        assertThat(revisions(complaintId)).containsExactly(
            Triple(1L, "RECEIVED", priorTransaction),
            Triple(2L, "RESOLVED", currentTransaction),
        )
        assertThat(currentLabel(reference)).contains("RESOLVED")

        val priorView = onVertx {
            graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$reference", TIME.plusSeconds(1), 50, 50)
        }
        assertThat(requireNotNull(priorView).nodes.map { it.label }).anyMatch { it.contains("RECEIVED") }
        assertThat(priorView.edges.map { it.to }).contains("transaction:$priorTransaction")
            .doesNotContain("transaction:$currentTransaction")
        val currentView = onVertx {
            graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$reference", TIME.plusSeconds(2), 50, 50)
        }
        assertThat(requireNotNull(currentView).nodes.map { it.label }).anyMatch { it.contains("RESOLVED") }
        assertThat(currentView.edges.map { it.to }).contains("transaction:$currentTransaction")
            .doesNotContain("transaction:$priorTransaction")
        assertThat(
            onVertx {
                graph.neighborhood(ContextNamespace.COMPLAINT, "complaint:$reference", TIME, 50, 50)
            },
        ).isNull()

        val conflicting = prior.replace(priorTransaction.toString(), UUID.randomUUID().toString())
        assertThatThrownBy { onVertx { consumer.consume(conflicting) } }
            .hasStackTraceContaining("conflicting complaint revision")
        assertThat(revisions(complaintId)).hasSize(2)
    }

    @Test
    @TestSecurity(user = "complaint-history-reader", roles = ["ROLE_COMPLIANCE"])
    fun `historical API disclosure requires a live assignment and loses access on revocation`() {
        val reference = "CMP-HTTP-${UUID.randomUUID()}"
        val caseId = "case-${UUID.randomUUID()}"
        val complaintId = UUID.randomUUID()
        val account = UUID.randomUUID()
        onVertx {
            consumer.consume(
                event(complaintId, reference, 1, "complaint.received", "RECEIVED", account, UUID.randomUUID()),
            )
        }
        onVertx {
            consumer.consume(event(complaintId, reference, 2, "complaint.closed", "CLOSED", account, UUID.randomUUID()))
        }
        val proposal = onVertx {
            assignments.propose(
                ProposeAssignmentRequest(
                    "complaint-history-reader",
                    caseId,
                    "PAYMENT_COMPLAINT",
                    null,
                    Instant.now().plusSeconds(3600),
                    "complaint:$reference",
                ),
                "history-maker",
            )
        }
        val assignment = onVertx { assignments.decide(proposal.id, true, "history-checker") }
        given().header("X-Investigation-Case-Id", caseId)
            .header("X-Investigation-Purpose", "PAYMENT_COMPLAINT")
            .queryParam("asOf", TIME.plusSeconds(1).toString())
            .get("/api/v1/context/complaints/$reference").then().statusCode(200)
            .body("nodes.find { it.type == 'COMPLAINT' }.sourceVersion", equalTo(1))
            .body("nodes.find { it.type == 'COMPLAINT' }.label", equalTo("Complaint $reference · RECEIVED"))
        onVertx { assignments.revoke(requireNotNull(assignment.assignmentId), "history-admin") }
        given().header("X-Investigation-Case-Id", caseId)
            .header("X-Investigation-Purpose", "PAYMENT_COMPLAINT")
            .queryParam("asOf", TIME.plusSeconds(1).toString())
            .get("/api/v1/context/complaints/$reference").then().statusCode(403)
    }

    private fun event(
        complaintId: UUID,
        reference: String,
        revision: Long,
        type: String,
        status: String,
        account: UUID,
        transaction: UUID,
    ): String {
        val occurredAt = TIME.plusSeconds(revision)
        return """{"schemaVersion":1,"sourceVersion":$revision,"aggregateRevision":$revision,""" +
            """"eventType":"$type","sourceService":"dispute-service","complaintId":"$complaintId",""" +
            """"reference":"$reference","status":"$status","occurredAt":"$occurredAt",""" +
            """"accountId":"$account","transactionId":"$transaction"}"""
    }

    private fun revisions(id: UUID): List<Triple<Long, String, UUID>> = scopedConnection { connection ->
        connection.prepareStatement(
            """SELECT source_version, status, transaction_id FROM context_complaint_revisions
               WHERE complaint_id = ? ORDER BY source_version""",
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            Triple(
                                rows.getLong(1),
                                rows.getString(2),
                                rows.getObject(3, UUID::class.java),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun currentLabel(reference: String): String = scopedConnection { connection ->
        connection.prepareStatement(
            "SELECT display_label FROM context_nodes WHERE bank_scope = 'openbank-cz' AND node_key = ?",
        ).use { statement ->
            statement.setString(1, "complaint:$reference")
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }

    private fun <T> scopedConnection(action: (java.sql.Connection) -> T): T = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    ).use { connection ->
        connection.autoCommit = false
        connection.prepareStatement("SELECT set_config('openbank.bank_scope', ?, true)").use { statement ->
            statement.setString(1, "openbank-cz")
            statement.executeQuery().close()
        }
        action(connection)
    }

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private companion object {
        val TIME: Instant = Instant.parse("2026-09-01T12:00:00Z")
    }
}
