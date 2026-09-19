// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.context.infrastructure.AmlCaseEventDecoder
import com.openbank.context.infrastructure.AmlCaseHistoryRepository
import com.openbank.context.infrastructure.AmlCaseSourceStatus
import com.openbank.context.infrastructure.AmlCaseSourceUnavailable
import com.openbank.context.infrastructure.AssignmentAdministrationService
import com.openbank.context.infrastructure.ProposeAssignmentRequest
import com.openbank.libs.testing.containers.PostgresTestResource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusMock
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
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class AmlCaseEvidenceResourceIT {
    private lateinit var sourceStatus: AmlCaseSourceStatus

    @BeforeEach
    fun sourceCaseIsOpen() {
        sourceStatus = mockk()
        coEvery { sourceStatus.isOpen(any()) } returns true
        QuarkusMock.installMockForType(sourceStatus, AmlCaseSourceStatus::class.java)
    }

    @Inject lateinit var repository: AmlCaseHistoryRepository

    @Inject lateinit var decoder: AmlCaseEventDecoder

    @Inject lateinit var mapper: ObjectMapper

    @Inject lateinit var assignments: AssignmentAdministrationService

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `approved open case exposes minimized evidence without caching and revocation removes access`() {
        val id = UUID.randomUUID()
        record(id)
        val assignment = grant(id)
        val response = request(id).then().statusCode(200).header("Cache-Control", "no-store")
            .body("root", equalTo("aml-case:$id"))
            .body("observations.size()", equalTo(1))
            .body("observations[0].evidence.status", equalTo("OPEN"))
            .extract().asString()
        assertThat(response).doesNotContain("private-", "Synthetic Person", "matchedEntity", "customerReference")
        onVertx { assignments.revoke(assignment, "aml-revoker") }
        request(id).then().statusCode(403)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `unassigned and differently rooted assignments cannot expose a case`() {
        val id = UUID.randomUUID()
        record(id)
        request(id).then().statusCode(403)
        coVerify(exactly = 0) { sourceStatus.isOpen(any()) }
        assertThatThrownBy { grant(id, root = "aml-case:${UUID.randomUUID()}") }
            .isInstanceOf(IllegalArgumentException::class.java)
        request(id).then().statusCode(403)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `legacy complaint assignment does not authorize AML investigation`() {
        val id = UUID.randomUUID()
        record(id)
        grant(id, purpose = "PAYMENT_COMPLAINT", root = null)
        request(id).then().statusCode(403)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `terminal source status denies access even when historical snapshot was open`() {
        val id = UUID.randomUUID()
        record(id)
        grant(id)
        coEvery { sourceStatus.isOpen(id) } returns false
        request(id).then().statusCode(403)
        given().header("X-Investigation-Case-Id", id.toString()).header("X-Investigation-Purpose", PURPOSE)
            .queryParam("effectiveAt", "2026-09-01T12:00:00Z")
            .get("/api/v1/context/aml-cases/$id").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `source outage never discloses projected open evidence`() {
        val id = UUID.randomUUID()
        record(id)
        grant(id)
        coEvery { sourceStatus.isOpen(id) } throws AmlCaseSourceUnavailable()
        request(id).then().statusCode(503)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `future snapshots and case header mismatch are rejected`() {
        val id = UUID.randomUUID()
        record(id)
        grant(id)
        listOf("effectiveAt", "knownAt").forEach { field ->
            given().header("X-Investigation-Case-Id", id.toString()).header("X-Investigation-Purpose", PURPOSE)
                .queryParam(field, Instant.now().plusSeconds(3600).toString())
                .get("/api/v1/context/aml-cases/$id").then().statusCode(400)
        }
        given().header("X-Investigation-Case-Id", UUID.randomUUID().toString())
            .header("X-Investigation-Purpose", PURPOSE)
            .get("/api/v1/context/aml-cases/$id").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `operator is denied even with approved assignment and open case`() {
        val id = UUID.randomUUID()
        record(id)
        grant(id)
        request(id).then().statusCode(403)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `network includes only currently assigned and source-open cases with an observed shared party`() {
        val root = UUID.randomUUID()
        val related = UUID.randomUUID()
        val unrelated = UUID.randomUUID()
        val party = UUID.randomUUID().toString()
        record(root, party = party)
        record(related, party = party)
        record(unrelated, party = UUID.randomUUID().toString())
        grant(root)
        requestNetwork(root).then().statusCode(200).body("related.size()", equalTo(0))

        grant(related)
        grant(unrelated)
        requestNetwork(root).then().statusCode(200)
            .body("root.root", equalTo("aml-case:$root"))
            .body("related.size()", equalTo(1))
            .body("related[0].root", equalTo("aml-case:$related"))

        coEvery { sourceStatus.isOpen(related) } returns false
        requestNetwork(root).then().statusCode(200).body("related.size()", equalTo(0))
        coEvery { sourceStatus.isOpen(related) } throws AmlCaseSourceUnavailable()
        requestNetwork(root).then().statusCode(503)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `network omits a candidate when its bounded history no longer shows the linking evidence`() {
        val root = UUID.randomUUID()
        val related = UUID.randomUUID()
        val account = UUID.randomUUID()
        val relatedParty = UUID.randomUUID().toString()
        record(root, party = UUID.randomUUID().toString(), account = account)
        record(related, party = relatedParty, account = account)
        grant(root)
        grant(related)
        (1..21).forEach { index ->
            val previous = if (index % 2 == 1) "OPEN" else "UNDER_REVIEW"
            val next = if (index % 2 == 1) "UNDER_REVIEW" else "OPEN"
            val payload = mapper.writeValueAsString(
                mapOf(
                    "caseId" to related,
                    "partyId" to relatedParty,
                    "previousStatus" to previous,
                    "newStatus" to next,
                    "occurredAt" to Instant.parse("2026-09-02T00:00:00Z").plusSeconds(index.toLong()),
                ),
            )
            val observation = decoder.decode(payload, UUID.randomUUID().toString(), "aml.case.status_changed.v1")
            onVertx { repository.append(observation) }
        }
        requestNetwork(root).then().statusCode(200).body("related.size()", equalTo(0))
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_COMPLIANCE"])
    fun `network requires root assignment before candidate discovery or source access`() {
        val id = UUID.randomUUID()
        record(id, party = UUID.randomUUID().toString())
        requestNetwork(id).then().statusCode(403)
        coVerify(exactly = 0) { sourceStatus.isOpen(id) }
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `network route denies a non-compliance operator even with root assignment`() {
        val id = UUID.randomUUID()
        record(id, party = UUID.randomUUID().toString())
        grant(id)
        requestNetwork(id).then().statusCode(403)
    }

    private fun request(id: UUID) = given()
        .header("X-Investigation-Case-Id", id.toString()).header("X-Investigation-Purpose", PURPOSE)
        .get("/api/v1/context/aml-cases/$id")

    private fun requestNetwork(id: UUID) = given()
        .header("X-Investigation-Case-Id", id.toString()).header("X-Investigation-Purpose", PURPOSE)
        .get("/api/v1/context/aml-cases/$id/network")

    private fun grant(id: UUID, purpose: String = PURPOSE, root: String? = "aml-case:$id"): UUID {
        val proposal = onVertx {
            assignments.propose(
                ProposeAssignmentRequest(ACTOR, id.toString(), purpose, null, Instant.now().plusSeconds(3600), root),
                "aml-maker",
            )
        }
        return requireNotNull(onVertx { assignments.decide(proposal.id, true, "aml-checker") }.assignmentId)
    }

    private fun record(id: UUID, terminal: Boolean = false, party: String = PARTY, account: UUID? = null) {
        val fields = mutableMapOf<String, Any?>("caseId" to id, "partyId" to party)
        if (terminal) {
            fields.putAll(
                mapOf(
                    "previousStatus" to "OPEN",
                    "newStatus" to "CLEARED",
                    "occurredAt" to "2026-09-02T00:00:00Z",
                    "decisionReason" to "private-reason",
                    "assignedAnalyst" to "private-analyst",
                ),
            )
        } else {
            fields.putAll(
                mapOf(
                    "status" to "OPEN",
                    "riskLevel" to "LOW",
                    "screeningType" to "TRANSACTION_MONITORING",
                    "accountId" to account,
                    "transactionId" to null,
                    "occurredAt" to "2026-09-01T00:00:00Z",
                    "matchedEntity" to "Synthetic Person",
                    "customerReference" to "private-reference",
                ),
            )
        }
        val type = if (terminal) "aml.case.status_changed.v1" else "aml.case.created.v1"
        val observation = decoder.decode(mapper.writeValueAsString(fields), UUID.randomUUID().toString(), type)
        onVertx { repository.append(observation) }
    }

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private companion object {
        const val ACTOR = "aml-evidence-analyst"
        const val PURPOSE = "AML_INVESTIGATION"
        const val PARTY = "00000000-0000-4000-8000-000000000006"
    }
}
