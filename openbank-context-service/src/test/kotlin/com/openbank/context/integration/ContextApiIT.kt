// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.context.infrastructure.AssignmentAdministrationService
import com.openbank.context.infrastructure.ContextAuditCommitment
import com.openbank.context.infrastructure.ContextReadAuditEntity
import com.openbank.context.infrastructure.MakerCheckerViolation
import com.openbank.context.infrastructure.ProposeAssignmentRequest
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasEntry
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import jakarta.enterprise.inject.Any as AnyQualifier

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class ContextApiIT {
    @Inject
    @AnyQualifier
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var assignmentAdministration: AssignmentAdministrationService

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `maker checker assignment grants access and revocation removes it immediately`() {
        val reference = "CMP-ACCESS-${UUID.randomUUID()}"
        val caseId = "case-access-${UUID.randomUUID()}"
        seedNode("complaint:$reference", "COMPLAINT", "Complaint $reference")
        val request = ProposeAssignmentRequest(ACTOR, caseId, PURPOSE, null, Instant.now().plusSeconds(3600))
        val proposal = onVertxContext { assignmentAdministration.propose(request, "maker-1") }
        assertThat(proposal.status).isEqualTo("PENDING")

        assertThatThrownBy {
            onVertxContext { assignmentAdministration.decide(proposal.id, true, "maker-1") }
        }.isInstanceOf(MakerCheckerViolation::class.java)

        val approved = onVertxContext { assignmentAdministration.decide(proposal.id, true, "checker-2") }
        val assignmentId = requireNotNull(approved.assignmentId)
        assertThat(onVertxContext { assignmentAdministration.active(50) }.map { it.id }).contains(assignmentId)

        given().header("X-Investigation-Case-Id", caseId).header("X-Investigation-Purpose", PURPOSE)
            .`when`().get("/api/v1/context/complaints/$reference").then().statusCode(200)

        onVertxContext { assignmentAdministration.revoke(assignmentId, "admin-3") }
        assertThat(onVertxContext { assignmentAdministration.active(50) }.map { it.id }).doesNotContain(assignmentId)
        given().header("X-Investigation-Case-Id", caseId).header("X-Investigation-Purpose", PURPOSE)
            .`when`().get("/api/v1/context/complaints/$reference").then().statusCode(403)
        assertThat(count("context_assignment_change_audit", "case_id = ?", caseId)).isEqualTo(3)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    @Suppress("LongMethod")
    fun `complaint event is projected idempotently and becomes authorized graph evidence`() {
        val complaintId = UUID.randomUUID()
        val reference = "CMP-E2E-${UUID.randomUUID()}"
        val accountId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        val disputeId = UUID.randomUUID()
        val version = NOW.epochSecond * 1_000_000_000 + NOW.nano
        val payload = complaintEvent(complaintId, reference, accountId, transactionId, disputeId, version)
        val source = connector.source<String>("dispute-events-in")
        val payments = connector.source<String>("domestic-payment-events-in")
        val transactions = connector.source<String>("transaction-events-in")
        val ledger = connector.source<String>("ledger-events-in")
        val clearing = connector.source<String>("clearing-events-in")
        val sepaReturns = connector.source<String>("sepa-payment-events-in")
        val reversalId = UUID.randomUUID()
        listOf(source, payments, transactions, ledger, clearing, sepaReturns)
            .forEach { it.runOnVertxContext(true) }

        repeat(2) { source.send(payload) }
        source.send(complaintEvent(complaintId, reference, accountId, transactionId, disputeId, version - 1))
        val clearingItemId = sendRailEvidence(clearing, sepaReturns, transactionId, reversalId)
        sendPaymentLifecycle(payments, transactionId)
        val bookingTransactionId = UUID.randomUUID()
        val journalId = UUID.randomUUID()
        val reversalJournalId = UUID.randomUUID()
        ledger.send(ledgerPostedEvent(journalId, bookingTransactionId))
        ledger.send(ledgerPostedEvent(reversalJournalId, reversalId))
        transactions.send(transactionInitiatedEvent(bookingTransactionId, transactionId))
        transactions.send(transactionReversalEvent(reversalId, bookingTransactionId))
        assertProjectionState(
            reference,
            transactionId,
            bookingTransactionId,
            journalId,
            clearingItemId,
            reversalId,
            complaintId,
        )
        awaitCount("context_projection_events", "aggregate_ref", "booking-transaction:$reversalId", 1)
        awaitCount("context_projection_events", "aggregate_ref", "ledger-booking:$reversalJournalId", 1)
        val unrelatedComplaint = seedUnrelatedComplaint(transactionId)

        seedAssignment(CASE, PURPOSE)
        val response = given()
            .header("X-Investigation-Case-Id", CASE)
            .header("X-Investigation-Purpose", PURPOSE)
            .`when`().get("/api/v1/context/complaints/$reference")
            .then().statusCode(200)
            .body("nodes.size()", equalTo(15))
            .body("nodes.key", org.hamcrest.Matchers.hasItem("reversal-transaction:$reversalId"))
            .body("nodes.key", org.hamcrest.Matchers.hasItem("booking-transaction:$reversalId"))
            .body("edges.size()", equalTo(14))
            .body(
                "edges.relation",
                org.hamcrest.Matchers.hasItems(
                    "CREATED",
                    "SUBMITTED_TO",
                    "RETURNED_BY",
                    "REVERSED_BY",
                    "BOOKING_REQUESTED",
                    "BOOKED_AS",
                    "SETTLED",
                ),
            )
            .extract().asString()
        assertThat(response).doesNotContain(unrelatedComplaint)
    }

    @Test
    fun `older SEPA return without reversal ID retains return evidence without inventing an ID`() {
        val paymentId = UUID.randomUUID()
        val reversalId = UUID.randomUUID()
        val payload = sepaReturnedEvent(paymentId, reversalId)
            .replace("\"reversalTransactionId\":\"$reversalId\",", "")
        val source = connector.source<String>("sepa-payment-events-in")
        source.runOnVertxContext(true)

        source.send(payload)

        awaitCount("context_projection_events", "aggregate_ref", "return-evidence:sepa:$paymentId:4", 1)
        assertThat(count("context_nodes", "node_key = ?", "reversal-transaction:$reversalId")).isZero()
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `complaint lens reads bounded evidence and records the authorized access`() {
        val reference = UUID.randomUUID().toString()
        val root = "complaint:$reference"
        val payment = "payment:${UUID.randomUUID()}"
        seedAssignment(CASE, PURPOSE)
        seedNode(root, "COMPLAINT", "Complaint $reference")
        seedNode(payment, "PAYMENT", "Payment evidence")
        seedEdge(root, payment, "TRACES")

        given()
            .header("X-Investigation-Case-Id", CASE)
            .header("X-Investigation-Purpose", PURPOSE)
            .`when`().get("/api/v1/context/complaints/$reference")
            .then().statusCode(200)
            .body("root", equalTo(root))
            .body("nodes.size()", equalTo(2))
            .body("edges.size()", equalTo(1))

        assertThat(auditDecisions(root)).containsExactly("ALLOWED")
        assertThat(auditDecisions(root, null)).isEmpty()
        assertThat(auditDecisions(root, "another-bank")).isEmpty()
        assertThat(auditCommitments(root)).hasSize(1)
        assertThat(auditCommitments(root).single()).matches("[0-9a-f]{64}")
        assertThat(storedAuditCommitment(root)).isEqualTo(auditCommitments(root).single())
        assertThat(auditCommitments(root, null)).isEmpty()
        assertThat(auditCommitments(root, "another-bank")).isEmpty()
        assertThat(disclosureRows(root)).hasSize(1)
        assertThat(disclosureRows(root).single().first).isEqualTo(3)
        assertThat(disclosureRows(root).single().second).contains("node:test:", "evidence:")
        assertThat(disclosureRows(root, null)).isEmpty()
        assertThat(disclosureRows(root, "another-bank")).isEmpty()
        assertAuditEvidenceImmutable(root)
    }

    private fun assertAuditEvidenceImmutable(root: String) {
        assertThatThrownBy {
            withAuditScope("openbank-cz") { connection ->
                connection.prepareStatement("DELETE FROM context_read_audit WHERE root_ref = ?").use {
                    it.setString(1, root)
                    it.executeUpdate()
                }
            }
        }.hasMessageContaining("context audit records are append-only")
        assertThatThrownBy {
            withAuditScope("openbank-cz") { connection ->
                connection.prepareStatement(
                    "DELETE FROM context_audit_commitment_outbox WHERE audit_id IN " +
                        "(SELECT audit_id FROM context_read_audit WHERE root_ref = ?)",
                ).use {
                    it.setString(1, root)
                    it.executeUpdate()
                }
            }
        }.hasMessageContaining("context audit records are append-only")
        assertThatThrownBy {
            withAuditScope("openbank-cz") { connection ->
                connection.prepareStatement(
                    "DELETE FROM context_disclosure_audit WHERE decision_audit_id IN " +
                        "(SELECT audit_id FROM context_read_audit WHERE root_ref = ?)",
                ).use {
                    it.setString(1, root)
                    it.executeUpdate()
                }
            }
        }.hasMessageContaining("context audit records are append-only")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `ICT incident events project current aggregate service impact without customer identifiers`() {
        val incidentId = UUID.randomUUID()
        val source = connector.source<String>("ict-incident-events-in")
        source.runOnVertxContext(true)
        val version = NOW.epochSecond * 1_000_000_000 + NOW.nano
        source.send(incidentEvent(incidentId, version, listOf("ledger-service", "payment-service")))
        source.send(incidentEvent(incidentId, version + 1, listOf("ledger-service"), status = "CONTAINED"))

        awaitCount("context_projection_events", "aggregate_ref", "incident:$incidentId", 2)
        assertThat(count("context_edges", "from_key = ?", "incident:$incidentId")).isEqualTo(1)
        seedAssignment(CASE, "INCIDENT_IMPACT")

        val response = given()
            .header("X-Investigation-Case-Id", CASE)
            .header("X-Investigation-Purpose", "INCIDENT_IMPACT")
            .`when`().get("/api/v1/context/incidents/$incidentId/impact")
            .then().statusCode(200)
            .body("affectedByType", hasEntry("SERVICE", 1))
            .body("total", equalTo(1))
            .extract().asString()

        assertThat(response).doesNotContain("ledger-service")
        assertThat(disclosureRows("incident:$incidentId"))
            .containsExactly(1 to "[\"incident:$incidentId:ICT_INCIDENT_STATUS_CHANGED:${version + 1}\"]")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `incident lens exposes aggregate counts without affected identifiers`() {
        val reference = UUID.randomUUID().toString()
        val root = "incident:$reference"
        seedAssignment(CASE, "INCIDENT_IMPACT")
        seedNode(root, "INCIDENT", "Incident $reference", namespace = "INCIDENT")
        repeat(2) {
            val payment = "payment:${UUID.randomUUID()}"
            seedNode(payment, "PAYMENT", "Payment", namespace = "INCIDENT")
            seedEdge(root, payment, "AFFECTED", namespace = "INCIDENT")
        }

        val response = given()
            .header("X-Investigation-Case-Id", CASE)
            .header("X-Investigation-Purpose", "INCIDENT_IMPACT")
            .`when`().get("/api/v1/context/incidents/$reference/impact")
            .then().statusCode(200)
            .body("affectedByType", hasEntry("PAYMENT", 2))
            .body("total", equalTo(2))
            .body("drilldownAvailable", equalTo(false))
            .body("projectionStatus", equalTo("AVAILABLE"))
            .extract().asString()

        assertThat(response).doesNotContain("payment:")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `authorized missing incident is explicitly unknown and audited`() {
        val reference = UUID.randomUUID().toString()
        seedAssignment(CASE, "INCIDENT_IMPACT")
        given()
            .header("X-Investigation-Case-Id", CASE)
            .header("X-Investigation-Purpose", "INCIDENT_IMPACT")
            .`when`().get("/api/v1/context/incidents/$reference/impact")
            .then().statusCode(200)
            .body("projectionStatus", equalTo("MISSING"))
            .body("total", equalTo(0))
            .body("affectedByType.size()", equalTo(0))
        assertThat(auditDecisions("incident:$reference")).containsExactly("ALLOWED")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `missing assignment is denied and durably audited before graph access`() {
        val reference = UUID.randomUUID().toString()
        val root = "complaint:$reference"
        seedNode(root, "COMPLAINT", "Complaint $reference")

        given()
            .header("X-Investigation-Case-Id", "unassigned-${UUID.randomUUID()}")
            .header("X-Investigation-Purpose", PURPOSE)
            .`when`().get("/api/v1/context/complaints/$reference")
            .then().statusCode(403)

        assertThat(auditDecisions(root)).containsExactly("DENIED")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `an assignment for another purpose cannot be replayed against the complaint lens`() {
        val reference = UUID.randomUUID().toString()
        seedAssignment(CASE, "INCIDENT_IMPACT")
        seedNode("complaint:$reference", "COMPLAINT", "Complaint $reference")

        given()
            .header("X-Investigation-Case-Id", CASE)
            .header("X-Investigation-Purpose", "INCIDENT_IMPACT")
            .`when`().get("/api/v1/context/complaints/$reference")
            .then().statusCode(403)

        assertThat(auditDecisions("complaint:$reference")).containsExactly("DENIED")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `missing investigation headers are rejected as bad requests`() {
        given()
            .`when`().get("/api/v1/context/complaints/${UUID.randomUUID()}")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `invalid as of timestamp is rejected as a bad request`() {
        given()
            .header("X-Investigation-Case-Id", CASE)
            .header("X-Investigation-Purpose", PURPOSE)
            .queryParam("asOf", "not-a-timestamp")
            .`when`().get("/api/v1/context/complaints/${UUID.randomUUID()}")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_VIEWER"])
    fun `an unprivileged role cannot reach the lens`() {
        given()
            .header("X-Investigation-Case-Id", CASE)
            .header("X-Investigation-Purpose", PURPOSE)
            .`when`().get("/api/v1/context/complaints/${UUID.randomUUID()}")
            .then().statusCode(403)
    }

    private fun seedAssignment(caseId: String, purpose: String) = execute(
        """INSERT INTO context_case_assignments
            (assignment_id, bank_scope, principal_id, case_id, purpose, valid_from, valid_to, created_at)
            VALUES (?, 'openbank-cz', ?, ?, ?, ?, ?, ?)
            ON CONFLICT (bank_scope, principal_id, case_id, purpose, valid_from) DO NOTHING
        """.trimIndent(),
        UUID.randomUUID(),
        ACTOR,
        caseId,
        purpose,
        NOW.minusSeconds(3600),
        NOW.plusSeconds(3600),
        NOW,
    )

    private fun seedNode(key: String, type: String, label: String, namespace: String = "COMPLAINT") = execute(
        """INSERT INTO context_nodes
            (node_row_id, node_key, bank_scope, projection_generation, namespace, node_type, source_system, source_ref, display_label,
             classification, valid_from, recorded_at, source_version)
            VALUES (?, ?, 'openbank-cz', 1, ?, ?, 'test', ?, ?, 'INTERNAL', ?, ?, 1)
        """.trimIndent(),
        UUID.randomUUID(),
        key,
        namespace,
        type,
        key,
        label,
        NOW.minusSeconds(60),
        NOW,
    )

    private fun seedEdge(from: String, to: String, relation: String, namespace: String = "COMPLAINT") = execute(
        """INSERT INTO context_edges
            (edge_id, bank_scope, projection_generation, namespace, from_key, to_key, relation_type, source_system, evidence_ref,
             valid_from, recorded_at, source_version)
            VALUES (?, 'openbank-cz', 1, ?, ?, ?, ?, 'test', ?, ?, ?, 1)
        """.trimIndent(),
        UUID.randomUUID(), namespace, from, to, relation, "evidence:${UUID.randomUUID()}", NOW.minusSeconds(60), NOW,
    )

    private fun auditDecisions(root: String, bankScope: String? = "openbank-cz"): List<String> =
        withAuditScope(bankScope) { connection ->
            connection.prepareStatement(
                "SELECT decision FROM context_read_audit WHERE principal_id = ? AND root_ref = ? ORDER BY occurred_at",
            ).use { statement ->
                statement.setString(1, ACTOR)
                statement.setString(2, root)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }

    private fun auditCommitments(root: String, bankScope: String? = "openbank-cz"): List<String> =
        withAuditScope(bankScope) { connection ->
            connection.prepareStatement(
                """SELECT o.commitment FROM context_audit_commitment_outbox o
                   JOIN context_read_audit a ON a.audit_id = o.audit_id
                   WHERE a.principal_id = ? AND a.root_ref = ? AND o.status = 'PENDING'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, ACTOR)
                statement.setString(2, root)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }

    private fun disclosureRows(root: String, bankScope: String? = "openbank-cz"): List<Pair<Int, String>> =
        withAuditScope(bankScope) { connection ->
            connection.prepareStatement(
                """SELECT d.evidence_count, d.evidence_refs_json FROM context_disclosure_audit d
                   JOIN context_read_audit a ON a.audit_id = d.decision_audit_id
                   WHERE a.principal_id = ? AND a.root_ref = ? AND a.decision = 'ALLOWED'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, ACTOR)
                statement.setString(2, root)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getInt(1) to rows.getString(2)) }
                }
            }
        }

    private fun storedAuditCommitment(root: String): String = withAuditScope("openbank-cz") { connection ->
        connection.prepareStatement(
            "SELECT * FROM context_read_audit WHERE principal_id = ? AND root_ref = ?",
        ).use { statement ->
            statement.setString(1, ACTOR)
            statement.setString(2, root)
            statement.executeQuery().use { rows ->
                check(rows.next())
                ContextAuditCommitment.of(
                    ContextReadAuditEntity().apply {
                        id = rows.getObject("audit_id", UUID::class.java)
                        bankScope = rows.getString("bank_scope")
                        principalId = rows.getString("principal_id")
                        caseId = rows.getString("case_id")
                        purpose = rows.getString("purpose")
                        action = rows.getString("action")
                        rootRef = rows.getString("root_ref")
                        decision = rows.getString("decision")
                        policyVersion = rows.getString("policy_version")
                        reasonCode = rows.getString("reason_code")
                        occurredAt = rows.getObject("occurred_at", OffsetDateTime::class.java).toInstant()
                        effectiveAt = rows.getObject("effective_at", OffsetDateTime::class.java)?.toInstant()
                        knownAt = rows.getObject("known_at", OffsetDateTime::class.java)?.toInstant()
                    },
                )
            }
        }
    }

    private fun <T> withAuditScope(scope: String?, action: (Connection) -> T): T = connection().use { connection ->
        connection.createStatement().use { statement ->
            statement.execute(
                """DO $$ BEGIN
                   IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'context_read_audit_test') THEN
                     CREATE ROLE context_read_audit_test NOLOGIN;
                   END IF;
                   END $$
                """.trimIndent(),
            )
            statement.execute("GRANT SELECT, DELETE ON context_read_audit TO context_read_audit_test")
            statement.execute("GRANT SELECT, DELETE ON context_audit_commitment_outbox TO context_read_audit_test")
            statement.execute("GRANT SELECT, DELETE ON context_disclosure_audit TO context_read_audit_test")
        }
        connection.autoCommit = false
        connection.createStatement().use { it.execute("SET LOCAL ROLE context_read_audit_test") }
        if (scope != null) {
            connection.prepareStatement("SELECT set_config('openbank.bank_scope', ?, true)").use {
                it.setString(1, scope)
                it.executeQuery().close()
            }
        }
        action(connection)
    }

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun count(table: String, predicate: String, value: Any): Int = connection().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM $table WHERE $predicate").use { statement ->
            statement.setObject(1, value)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun stringValue(table: String, column: String, keyColumn: String, key: Any): String =
        connection().use { connection ->
            connection.prepareStatement("SELECT $column FROM $table WHERE $keyColumn = ?").use { statement ->
                statement.setObject(1, key)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "No $table row for $keyColumn=$key" }
                    rows.getString(1)
                }
            }
        }

    private fun awaitCount(table: String, column: String, value: Any, expected: Int) {
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            if (count(table, "$column = ?", value) == expected) return
            Thread.sleep(25)
        }
        assertThat(count(table, "$column = ?", value)).isEqualTo(expected)
    }

    private fun complaintEvent(
        complaintId: UUID,
        reference: String,
        accountId: UUID,
        transactionId: UUID,
        disputeId: UUID,
        sourceVersion: Long,
    ) = """{"schemaVersion":1,"sourceVersion":$sourceVersion,"aggregateRevision":$sourceVersion,""" +
        """"eventType":"complaint.received",""" +
        """"sourceService":"dispute-service","complaintId":"$complaintId",""" +
        """"reference":"$reference","status":"RECEIVED",""" +
        """"occurredAt":"$NOW","accountId":"$accountId","transactionId":"$transactionId",""" +
        """"disputeId":"$disputeId"}"""

    private fun incidentEvent(
        incidentId: UUID,
        sourceVersion: Long,
        services: List<String>,
        status: String = "OPEN",
    ): String {
        val affectedServices = services.joinToString(",") { "\"$it\"" }
        return """{"schemaVersion":1,"sourceVersion":$sourceVersion,"aggregateRevision":$sourceVersion,""" +
            """"eventType":"ICT_INCIDENT_STATUS_CHANGED",""" +
            """"sourceService":"security-scanner","occurredAt":"$NOW","incident":{"id":"$incidentId",""" +
            """"severity":"P1_CRITICAL","status":"$status","affectedServices":[$affectedServices],""" +
            """"detectedAt":"$NOW","updatedAt":"$NOW"}}"""
    }

    private fun domesticPaymentEvent(paymentId: UUID, revision: Long, status: String): String {
        val eventType = if (revision == 1L) "DOMESTIC_PAYMENT_CREATED" else "DOMESTIC_PAYMENT_STATUS_CHANGED"
        val statusField = if (revision == 1L) "\"status\":\"$status\"" else "\"newStatus\":\"$status\""
        return """{"eventType":"$eventType","sourceService":"domestic-payment",""" +
            """"paymentId":"$paymentId","aggregateRevision":$revision,$statusField,""" +
            """"occurredAt":"${NOW.plusSeconds(revision)}"}"""
    }

    private fun sendPaymentLifecycle(source: InMemorySource<String>, paymentId: UUID) {
        source.send(domesticPaymentEvent(paymentId, 1, "RECEIVED"))
        source.send(domesticPaymentEvent(paymentId, 2, "SENT_TO_CLEARING"))
        source.send(domesticPaymentEvent(paymentId, 3, "RETURNED"))
        source.send(domesticPaymentEvent(paymentId, 3, "RETURNED"))
    }

    private fun sendRailEvidence(
        clearing: InMemorySource<String>,
        sepaReturns: InMemorySource<String>,
        paymentId: UUID,
        reversalId: UUID,
    ): UUID = UUID.randomUUID().also { itemId ->
        clearing.send(clearingItemSettledEvent(itemId, UUID.randomUUID(), paymentId))
        sepaReturns.send(sepaReturnedEvent(paymentId, reversalId))
    }

    private fun seedUnrelatedComplaint(paymentId: UUID): String =
        "complaint:CMP-OTHER-${UUID.randomUUID()}".also { complaint ->
            seedNode(complaint, "COMPLAINT", "Unrelated complaint")
            seedEdge(complaint, "transaction:$paymentId", "CONCERNS_TRANSACTION")
            seedEdge("transaction:$paymentId", complaint, "CREATED")
        }

    private fun assertProjectionState(
        reference: String,
        paymentId: UUID,
        bookingTransactionId: UUID,
        journalId: UUID,
        clearingItemId: UUID,
        reversalId: UUID,
        complaintId: UUID,
    ) {
        awaitCount("context_projection_events", "aggregate_ref", "complaint:$reference", 2)
        awaitCount("context_projection_events", "aggregate_ref", "transaction:$paymentId", 3)
        awaitCount("context_projection_events", "aggregate_ref", "booking-transaction:$bookingTransactionId", 1)
        awaitCount("context_projection_events", "aggregate_ref", "ledger-booking:$journalId", 1)
        awaitCount("context_projection_events", "aggregate_ref", "clearing-item:$clearingItemId", 1)
        awaitCount("context_projection_events", "aggregate_ref", "return-evidence:sepa:$paymentId:4", 1)
        assertThat(count("context_nodes", "node_key = ?", "reversal-transaction:$reversalId")).isEqualTo(1)
        assertThat(count("context_nodes", "node_key LIKE ?", "%$complaintId%")).isZero()
        assertThat(count("context_nodes", "node_key = ?", "complaint:$reference")).isEqualTo(1)
        assertThat(count("context_edges", "from_key = ?", "complaint:$reference")).isEqualTo(3)
        assertThat(stringValue("context_nodes", "source_system", "node_key", "transaction:$paymentId"))
            .isEqualTo("domestic-payment")
        assertThat(
            stringValue("context_nodes", "source_system", "node_key", "booking-transaction:$bookingTransactionId"),
        ).isEqualTo("transaction-service")
    }

    private fun transactionInitiatedEvent(transactionId: UUID, paymentId: UUID): String =
        """{"eventType":"TransactionInitiated","sourceService":"transaction-service",""" +
            """"aggregateId":"$transactionId","version":0,"originatingPaymentId":"$paymentId",""" +
            """"occurredAt":"${NOW.minusSeconds(2)}"}"""

    private fun transactionReversalEvent(reversalId: UUID, originalId: UUID): String =
        """{"eventType":"TransactionInitiated","sourceService":"transaction-service",""" +
            """"aggregateId":"$reversalId","version":0,"type":"REVERSAL","reversalOf":"$originalId",""" +
            """"occurredAt":"${NOW.minusSeconds(1)}"}"""

    private fun ledgerPostedEvent(journalId: UUID, transactionId: UUID): String =
        """{"eventType":"JournalPosted","sourceService":"ledger-service","aggregateId":"$journalId",""" +
            """"version":0,"transactionId":"$transactionId","entryDate":"2026-09-13",""" +
            """"occurredAt":"${NOW.minusSeconds(1)}"}"""

    private fun clearingItemSettledEvent(itemId: UUID, batchId: UUID, paymentId: UUID): String =
        """{"eventType":"openbank.clearing.item.cleared","sourceService":"clearing-service",""" +
            """"itemId":"$itemId","batchId":"$batchId","paymentId":"$paymentId","version":2,""" +
            """"status":"SETTLED","occurredAt":"${NOW.minusSeconds(2)}"}"""

    private fun sepaReturnedEvent(paymentId: UUID, reversalId: UUID): String =
        """{"eventType":"sepa.payment.returned","sourceService":"sepa-payment","paymentId":"$paymentId",""" +
            """"version":4,"returnReasonCode":"AC04","reversalPerformed":true,""" +
            """"reversalTransactionId":"$reversalId",""" +
            """"occurredAt":"${NOW.minusSeconds(1)}"}"""

    private fun execute(sql: String, vararg values: Any) = connection().use { connection ->
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value ->
                statement.setObject(index + 1, if (value is Instant) value.atOffset(ZoneOffset.UTC) else value)
            }
            statement.executeUpdate()
        }
    }

    private fun connection() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.username", String::class.java),
        ConfigProvider.getConfig().getValue("quarkus.datasource.password", String::class.java),
    )

    private companion object {
        const val ACTOR = "context-investigator"
        const val CASE = "case-context-it"
        const val PURPOSE = "PAYMENT_COMPLAINT"

        // Lifecycle fixtures add seconds per revision; keep every event before the default asOf query.
        val NOW: Instant = Instant.now().minusSeconds(60)
    }
}

class ContextMessagingTestResource : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> = InMemoryConnector.switchIncomingChannelsToInMemory(
        "aml-case-evidence-in",
        "delegation-history-in",
        "dispute-events-in",
        "ict-incident-events-in",
        "domestic-payment-events-in",
        "transaction-events-in",
        "ledger-events-in",
        "clearing-events-in",
        "sepa-payment-events-in",
    ) + mapOf("openbank.context.require-strict-revisions" to "true")

    override fun stop() = InMemoryConnector.clear()
}
