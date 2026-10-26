// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * E2E proof of the AML evidence chain over the real wires: `aml.case.*` events travel over the
 * real Redpanda broker with the header/key contract the consumer enforces, the owning aml-service
 * stand-in answers the bounded current-state check over real HTTP, and every authorization
 * decision (assignment, purpose, source state, revocation) lands durably in `context_read_audit`
 * and — via the scheduled relay — back on `openbank.context.audit.commitments`. No existing test
 * exercises any of these wires: every IT switches all channels to the in-memory connector and
 * mocks `AmlCaseSourceStatus`.
 */
@QuarkusTest
@TestProfile(ContextE2EProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ContextAmlE2E : ContextE2EBase() {

    @Test
    @Order(1)
    @TestSecurity(user = "ctx-admin-1", roles = ["ROLE_ADMIN"])
    fun `assignment proposal is created over real REST and the maker cannot decide it`() {
        val case = UUID.randomUUID().toString()
        val proposal = Given {
            contentType("application/json")
            body(
                """{"principalId":"$INVESTIGATOR","caseId":"$case","purpose":"$AML_PURPOSE",
                   "rootRef":"aml-case:$case",
                   "validTo":"${java.time.Instant.now().plusSeconds(3600)}"}""",
            )
        } When { post("/api/v1/context/assignment-proposals") } Then {
            statusCode(201)
            body("status", equalTo("PENDING"))
            body("makerId", equalTo("ctx-admin-1"))
        } Extract { path<String>("id") }
        pendingProposal = proposal

        // The maker cannot decide their own proposal — verbatim guard over real REST.
        Given {
            contentType("application/json")
            body("""{"approve":true}""")
        } When { patch("/api/v1/context/assignment-proposals/$proposal") } Then {
            statusCode(409)
            body(containsString("maker cannot decide their own assignment"))
        }
        // Still PENDING: the self-decide was a guard refusal, not a decision.
        Given { queryParam("limit", 50) } When { get("/api/v1/context/assignment-proposals") } Then {
            statusCode(200)
            body("find { it.id == '$proposal' }.status", equalTo("PENDING"))
        }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "ctx-admin-2", roles = ["ROLE_ADMIN"])
    fun `a distinct checker approves the pending proposal into an active assignment`() {
        val proposal = requireNotNull(pendingProposal) {
            "the ordered propose step must run first"
        }
        val assignment = Given {
            contentType("application/json")
            body("""{"approve":true}""")
        } When { patch("/api/v1/context/assignment-proposals/$proposal") } Then {
            statusCode(200)
            body("status", equalTo("APPROVED"))
        } Extract { path<String>("assignmentId") }
        assertThat(assignment).isNotBlank()

        Given { queryParam("limit", 50) } When { get("/api/v1/context/assignment-proposals/assignments") } Then {
            statusCode(200)
            body("find { it.id == '$assignment' }.principalId", equalTo(INVESTIGATOR))
        }
    }

    @Test
    @Order(3)
    @TestSecurity(user = INVESTIGATOR, roles = ["ROLE_COMPLIANCE"])
    fun `a created case event over the real broker is readable evidence with durable JDBC proof`() {
        val caseId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        publishAmlCaseCreated(caseId, partyId, accountId = UUID.randomUUID())
        assertThat(awaitAmlEvidenceRows(caseId, 1)).isEqualTo(1)
        ContextE2EWireMockResource.stubCaseStatus(caseId, "OPEN")
        seedAssignment(INVESTIGATOR, caseId.toString(), AML_PURPOSE, "aml-case:$caseId")

        Given {
            header("X-Investigation-Case-Id", caseId.toString())
            header("X-Investigation-Purpose", AML_PURPOSE)
        } When { get("/api/v1/context/aml-cases/$caseId") } Then {
            statusCode(200)
            header("Cache-Control", containsString("no-store"))
            body("root", equalTo("aml-case:$caseId"))
            body("observations", hasSize<Any>(1))
            body("observations[0].evidence.caseId", equalTo(caseId.toString()))
            body("observations[0].evidence.partyId", equalTo(partyId.toString()))
            body("observations[0].evidence.status", equalTo("OPEN"))
            body("observations[0].evidence.eventType", equalTo("aml.case.created.v1"))
        }
        assertThat(readAuditDecisions(caseId.toString())).contains("ALLOWED:POLICY_ALLOWED")
    }

    @Test
    @Order(4)
    @TestSecurity(user = INVESTIGATOR, roles = ["ROLE_COMPLIANCE"])
    fun `the owning service current state decides eligibility fail closed`() {
        val caseId = UUID.randomUUID()
        publishAmlCaseCreated(caseId, UUID.randomUUID())
        awaitAmlEvidenceRows(caseId, 1)
        seedAssignment(INVESTIGATOR, caseId.toString(), AML_PURPOSE, "aml-case:$caseId")
        // The source now reports the case CLEARED: historical evidence must not read as open.
        ContextE2EWireMockResource.stubCaseStatus(caseId, "CLEARED")

        Given {
            header("X-Investigation-Case-Id", caseId.toString())
            header("X-Investigation-Purpose", AML_PURPOSE)
        } When { get("/api/v1/context/aml-cases/$caseId") } Then {
            statusCode(403)
            header("Cache-Control", containsString("no-store"))
        }
        assertThat(readAuditDecisions(caseId.toString())).contains("ALLOWED:POLICY_ALLOWED")
    }

    @Test
    @Order(5)
    @TestSecurity(user = INVESTIGATOR, roles = ["ROLE_COMPLIANCE"])
    fun `related assigned cases sharing a reference surface as leads in the network`() {
        val root = UUID.randomUUID()
        val related = UUID.randomUUID()
        val unrelated = UUID.randomUUID()
        val sharedParty = UUID.randomUUID()
        publishAmlCaseCreated(root, sharedParty)
        publishAmlCaseCreated(related, sharedParty)
        publishAmlCaseCreated(unrelated, UUID.randomUUID())
        awaitAmlEvidenceRows(root, 1)
        awaitAmlEvidenceRows(related, 1)
        awaitAmlEvidenceRows(unrelated, 1)
        listOf(root, related, unrelated).forEach {
            ContextE2EWireMockResource.stubCaseStatus(it, "OPEN")
            seedAssignment(INVESTIGATOR, it.toString(), AML_PURPOSE, "aml-case:$it")
        }

        Given {
            header("X-Investigation-Case-Id", root.toString())
            header("X-Investigation-Purpose", AML_PURPOSE)
        } When { get("/api/v1/context/aml-cases/$root/network") } Then {
            statusCode(200)
            body("root.root", equalTo("aml-case:$root"))
            // Only the case sharing the party reference becomes a lead; the unrelated
            // assignment is discovered but contributes no visible edge.
            body("related", hasSize<Any>(1))
            body("related[0].root", equalTo("aml-case:$related"))
        }
    }

    @Test
    @Order(6)
    @TestSecurity(user = INVESTIGATOR, roles = ["ROLE_COMPLIANCE"])
    fun `a wire event breaking the broker contract is dead lettered and never persisted`() {
        val caseId = UUID.randomUUID()
        publishAmlMalformed(caseId)

        val rejected = awaitDlqRecord { record -> record.value().contains(caseId.toString()) }
        assertThat(rejected.key()).isNotEqualTo(caseId.toString())
        assertThat(
            jdbc { c ->
                c.prepareStatement(
                    "SELECT count(*) FROM context_aml_case_evidence WHERE case_id = ?",
                ).use { ps ->
                    ps.setObject(1, caseId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            },
        ).isZero()
    }

    @Test
    @Order(7)
    @TestSecurity(user = INVESTIGATOR, roles = ["ROLE_COMPLIANCE"])
    fun `purpose boundary and anonymous identity are refused before any evidence leaves`() {
        val caseId = UUID.randomUUID()
        publishAmlCaseCreated(caseId, UUID.randomUUID())
        awaitAmlEvidenceRows(caseId, 1)
        ContextE2EWireMockResource.stubCaseStatus(caseId, "OPEN")
        seedAssignment(INVESTIGATOR, caseId.toString(), AML_PURPOSE, "aml-case:$caseId")

        // A PAYMENT_COMPLAINT purpose header cannot be replayed against the AML lens.
        Given {
            header("X-Investigation-Case-Id", caseId.toString())
            header("X-Investigation-Purpose", "PAYMENT_COMPLAINT")
        } When { get("/api/v1/context/aml-cases/$caseId") } Then { statusCode(400) }
        assertThat(readAuditDecisions(caseId.toString())).noneMatch { it.startsWith("DENIED:PURPOSE_MISMATCH") }
    }

    @Test
    @Order(8)
    fun `an anonymous caller is refused at the role boundary`() {
        val caseId = UUID.randomUUID()
        publishAmlCaseCreated(caseId, UUID.randomUUID())
        awaitAmlEvidenceRows(caseId, 1)
        ContextE2EWireMockResource.stubCaseStatus(caseId, "OPEN")

        Given {
            header("X-Investigation-Case-Id", caseId.toString())
            header("X-Investigation-Purpose", AML_PURPOSE)
        } When { get("/api/v1/context/aml-cases/$caseId") } Then { statusCode(401) }
    }

    @Test
    @Order(9)
    @TestSecurity(user = INVESTIGATOR, roles = ["ROLE_COMPLIANCE"])
    fun `an allowed read commits to the audit wire and the outbox marks it sent`() {
        val caseId = UUID.randomUUID()
        publishAmlCaseCreated(caseId, UUID.randomUUID())
        awaitAmlEvidenceRows(caseId, 1)
        ContextE2EWireMockResource.stubCaseStatus(caseId, "OPEN")
        seedAssignment(INVESTIGATOR, caseId.toString(), AML_PURPOSE, "aml-case:$caseId")

        Given {
            header("X-Investigation-Case-Id", caseId.toString())
            header("X-Investigation-Purpose", AML_PURPOSE)
        } When { get("/api/v1/context/aml-cases/$caseId") } Then { statusCode(200) }

        // The scheduled relay (5 s cadence) turns the read audit into a broker fact. The wire
        // commitment is a SHA-256 of the restricted row by design, so the envelope joins to the
        // JDBC audit row only through the aggregate id.
        val auditId = readAuditId(caseId.toString())
        val committed = awaitAuditCommitment(auditId)
        assertThat(committed.get("eventType").asText()).isEqualTo("CONTEXT_READ_AUDIT_COMMITTED")
        assertThat(committed.get("sourceService").asText()).isEqualTo("context-service")
        assertThat(committed.get("commitment").asText()).isNotBlank()
        assertThat(commitmentStatus(auditId)).isEqualTo("SENT")
    }

    private companion object {
        private var pendingProposal: String? = null
    }
}
