// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
class PassportPermitFenceTest {
    @Inject
    lateinit var dataSource: DataSource

    @ParameterizedTest
    @ValueSource(strings = ["OPEN", "ACTIVATE"])
    fun `revocation wins when it owns the grant row first`(operation: String) {
        val request = seed(operation)
        dataSource.connection.use { winner ->
            winner.autoCommit = false
            assertTrue(winner.revoke(request.grant))
            assertNull(race(winner) { contender -> contender.consume(request) })
        }
        dataSource.connection.use { read ->
            assertNull(read.scalar("SELECT consumed_at FROM passport_account_permits WHERE id = ?", request.permit))
            assertEquals(
                true,
                read.scalar("SELECT revoked_at IS NOT NULL FROM passport_grants WHERE id = ?", request.grant),
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["OPEN", "ACTIVATE"])
    fun `consumption wins and only the exact operation replays after revocation`(operation: String) {
        val request = seed(operation)
        val receipt = dataSource.connection.use { winner ->
            winner.autoCommit = false
            val consumed = winner.consume(request)
            assertNotNull(consumed)
            assertTrue(race(winner) { contender -> contender.revoke(request.grant) })
            consumed
        }
        dataSource.connection.use { read ->
            assertEquals(receipt, read.consume(request))
            assertNull(read.consume(request.copy(party = UUID.randomUUID())))
            assertNull(read.consume(request.copy(operationId = UUID.randomUUID())))
            assertNull(read.consume(request.copy(operation = if (operation == "OPEN") "ACTIVATE" else "OPEN")))
            assertEquals(request.permit, read.issue(request.copy(permit = UUID.randomUUID())))
            assertNull(read.issue(request.copy(operationId = UUID.randomUUID(), permit = UUID.randomUUID())))
            assertNull(read.issue(request.copy(country = "AT")))
            assertFalse(read.revoke(request.grant))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["OPEN", "ACTIVATE"])
    fun `commit entry point requires evidence and recovers the receipt after revocation`(operation: String) {
        val request = seed(operation, issue = false)
        dataSource.connection.use { db ->
            assertNull(db.admit(request))
            db.grantSubjectEvidence(request)
            val admitted = db.admit(request)
            assertNotNull(admitted)
            assertTrue(db.revoke(request.grant))
            assertEquals(admitted, db.admit(request))
            assertNull(db.admit(request.copy(operationId = UUID.randomUUID())))
            assertNull(db.admit(request.copy(party = UUID.randomUUID())))
        }
    }

    @ParameterizedTest
    @CsvSource("SIGN,FOS", "DISBURSE,FOS", "SIGN,BRANCH", "DISBURSE,BRANCH")
    fun `revocation that owns the grant prevents a new loan admission`(operation: String, mode: String) {
        val request = seed(operation, issue = false, mode = mode)
        dataSource.connection.use { winner ->
            winner.grantSubjectEvidence(request)
            winner.autoCommit = false
            assertTrue(winner.revoke(request.grant))
            assertNull(race(winner) { contender -> contender.admitLoan(request) })
        }
    }

    @ParameterizedTest
    @CsvSource("SIGN,FOS", "DISBURSE,FOS", "SIGN,BRANCH", "DISBURSE,BRANCH")
    fun `loan admission that owns the grant preserves only its exact receipt`(operation: String, mode: String) {
        val request = seed(operation, issue = false, mode = mode)
        dataSource.connection.use { winner ->
            assertNull(winner.admitLoan(request.copy(operationId = UUID.randomUUID())))
            winner.grantSubjectEvidence(request)
            assertNull(winner.admitLoan(request.copy(country = "DE")))
            assertNull(winner.admitLoan(request.copy(mode = if (mode == "FOS") "BRANCH" else "FOS")))
            assertNull(winner.admitLoan(request.copy(establishment = UUID.randomUUID())))
            assertNull(winner.admitLoan(request.copy(revision = UUID.randomUUID())))
            winner.autoCommit = false
            val receipt = winner.admitLoan(request)
            assertNotNull(receipt)
            assertTrue(race(winner) { contender -> contender.revoke(request.grant) })
            assertEquals(receipt, winner.admitLoan(request))
            assertNull(winner.admitLoan(request.copy(operation = request.otherLoanOperation())))
            assertNull(winner.admitLoan(request.copy(party = UUID.randomUUID())))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["FOS", "BRANCH"])
    @TestSecurity(user = "service-account-openbank-lending", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-lending"),
            Claim(key = "preferred_username", value = "service-account-openbank-lending"),
        ],
    )
    fun `only exact consumed loan operations return receipts`(mode: String) {
        val signature = seed("SIGN", issue = false, mode = mode)
        val disbursement = signature.copy(operation = "DISBURSE", operationId = UUID.randomUUID())
        dataSource.connection.use { it.grantSubjectEvidence(signature) }
        callLoanAdmission(signature, "/receipt", 404)
        val differentPin = signature.copy(contentHash = "2".repeat(64))
        callLoanAdmission(differentPin, "", 409).header("X-FOS-Decision", org.hamcrest.Matchers.nullValue())
        callLoanAdmission(signature, "", 204)
        callLoanAdmission(signature, "/receipt", 204)
        callLoanAdmission(differentPin, "/receipt", 404)
        callLoanAdmission(signature.copy(country = "DE"), "/receipt", 404)
        callLoanAdmission(signature.copy(mode = if (mode == "FOS") "BRANCH" else "FOS"), "/receipt", 404)
        callLoanAdmission(disbursement, "/receipt", 404)
        callLoanAdmission(disbursement, "", 204)
        callLoanAdmission(disbursement, "/receipt", 204)
    }

    @ParameterizedTest
    @ValueSource(strings = ["FOS", "BRANCH"])
    @TestSecurity(user = "service-account-openbank-lending", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-lending"),
            Claim(key = "preferred_username", value = "service-account-openbank-lending"),
        ],
    )
    fun `only an approved live route for the exact verified subject and revision is returned`(mode: String) {
        val request = seed("SIGN", issue = false, mode = mode)
        callRoute(request).then().statusCode(409)
        dataSource.connection.use { it.grantSubjectEvidence(request) }

        val route = callRoute(request).then().statusCode(200).extract().jsonPath()
        assertEquals(mode, route.getString("mode"))
        assertEquals(request.country, route.getString("hostCountry"))
        callRoute(request.copy(mode = if (mode == "FOS") "BRANCH" else "FOS")).then().statusCode(409)
        assertEquals(request.bank.toString(), route.getString("bankId"))
        assertEquals(request.establishment.toString(), route.getString("establishmentId"))
        assertEquals("WEB", route.getString("channel"))
        assertEquals("RETAIL", route.getString("customerSegment"))
        assertEquals(HASH, route.getString("packSetDigest"))
        callRoute(request.copy(contentHash = "2".repeat(64))).then().statusCode(409)
        callRoute(request.copy(party = UUID.randomUUID())).then().statusCode(409)
        dataSource.connection.use { db ->
            val count = db.scalar("SELECT count(*) FROM passport_account_permits WHERE grant_id = ?", request.grant)
            assertEquals(0L, count)
            assertTrue(db.revoke(request.grant))
        }
        callRoute(request).then().statusCode(409)
        callLoanAdmission(request, "", 409).header("X-FOS-Decision", "refused")
    }

    private fun callRoute(request: PermitRequest) = given().contentType("application/json").body(
        mapOf(
            "grantId" to request.grant,
            "partyId" to request.party,
            "revisionId" to request.revision,
            "revisionContentHash" to request.contentHash,
        ) + explicitBranch(request),
    ).post("/api/v2/internal/passport-commitments/route")

    @Test
    fun `an exact refusal does not become an admission when evidence arrives later`() {
        val request = seed("SIGN", issue = false)
        dataSource.connection.use { db ->
            assertNull(db.admitLoan(request))
            db.grantSubjectEvidence(request)
            assertNull(db.admitLoan(request))
            assertNotNull(db.admitLoan(request.copy(operationId = UUID.randomUUID())))
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-account", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-account"),
            Claim(key = "preferred_username", value = "service-account-openbank-account"),
        ],
    )
    fun `account principal cannot admit a loan or read its receipt`() {
        val request = PermitRequest("SIGN")
        callLoanAdmission(request, "", 403)
        callLoanAdmission(request, "/receipt", 403)
        callRoute(request).then().statusCode(403)
    }

    private fun callLoanAdmission(request: PermitRequest, suffix: String, status: Int) =
        given().contentType("application/json").body(
            mapOf(
                "grantId" to request.grant,
                "operation" to request.operation,
                "operationId" to request.operationId,
                "partyId" to request.party,
                "revisionId" to request.revision,
                "revisionContentHash" to request.contentHash,
                "binding" to HASH,
                "hostCountry" to request.country,
                "bankId" to request.bank,
                "establishmentId" to request.establishment,
                "channel" to "WEB",
                "customerSegment" to "RETAIL",
                "packSetDigest" to HASH,
            ) + explicitBranch(request),
        ).post("/api/v2/internal/passport-commitments$suffix").then().statusCode(status)

    private fun explicitBranch(request: PermitRequest): Map<String, String> =
        if (request.mode == "BRANCH") mapOf("mode" to request.mode) else emptyMap()

    private fun Connection.grantSubjectEvidence(request: PermitRequest) = execute(
        """
        INSERT INTO passport_grant_subject_evidence (grant_id, party_id, evidence_ref, valid_until)
        VALUES (?, ?, 'synthetic-subject', CURRENT_TIMESTAMP + interval '1 hour')
        """.trimIndent(),
        request.grant,
        request.party,
    )

    private fun PermitRequest.otherLoanOperation() = if (operation == "SIGN") "DISBURSE" else "SIGN"

    private fun Connection.admitLoan(request: PermitRequest): Any? = scalar(
        "SELECT admit_passport_commitment(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        request.grant,
        request.operation,
        request.operationId,
        request.party,
        request.revision,
        HASH,
        request.country,
        request.bank,
        request.establishment,
        "WEB",
        "RETAIL",
        HASH,
        request.contentHash,
        "org.openbank.banking.loan",
        request.mode,
    )

    @Test
    fun `only a distinct staff person can activate a proposal for issuance`() {
        val request = seed("OPEN", issue = false, approved = false)
        dataSource.connection.use { db ->
            assertNull(db.issue(request))
            assertEquals(
                false,
                db.scalar("SELECT approve_passport_grant(?, ?, 'synthetic')", request.grant, request.maker),
            )
            val checker = UUID.randomUUID()
            assertEquals(true, db.scalar("SELECT approve_passport_grant(?, ?, 'synthetic')", request.grant, checker))
            assertEquals(request.permit, db.issue(request))
            assertEquals(false, db.scalar("SELECT approve_passport_grant(?, ?, 'synthetic')", request.grant, checker))
        }
    }

    @Test
    @TestSecurity(user = "synthetic-passport-replica", roles = ["ROLE_OPERATOR"])
    fun `independent catalog readers converge across restart and replay without observing a rolled back grant`() {
        val request = seed("OPEN", issue = false, approved = false)
        val online = GrantReplica(request.grant, pageSize = 7)
        val offline = GrantReplica(request.grant, pageSize = 13)
        online.catchUp()
        assertEquals("PROPOSED", online.snapshot?.get("state"))

        dataSource.connection.use { db ->
            assertEquals(
                true,
                db.scalar("SELECT approve_passport_grant(?, ?, 'synthetic')", request.grant, UUID.randomUUID()),
            )
            online.catchUp()
            assertEquals("APPROVED", online.snapshot?.get("state"))

            db.autoCommit = false
            assertTrue(db.revoke(request.grant))
            db.rollback()
            online.catchUp()
            assertEquals("APPROVED", online.snapshot?.get("state"))

            assertTrue(db.revoke(request.grant))
            db.commit()
        }

        online.catchUp()
        offline.catchUp()
        offline.apply(checkNotNull(online.firstSnapshot))
        assertEquals(online.snapshot, offline.snapshot)
        assertEquals("REVOKED", offline.snapshot?.get("state"))
        assertEquals(3, offline.snapshot?.get("version"))
        assertEquals(listOf("PROPOSED", "APPROVED", "REVOKED"), offline.states)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-account", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-account"),
            Claim(key = "preferred_username", value = "service-account-openbank-account"),
        ],
    )
    fun `receipt lookup cannot consume an issued permit and remains readable after revocation`() {
        val request = seed("OPEN")
        val body = mapOf(
            "grantId" to request.grant,
            "operationId" to request.operationId,
            "operation" to request.operation,
            "partyId" to request.party,
            "revisionId" to request.revision,
            "binding" to HASH,
        )
        assertReceipt(body, 404)
        dataSource.connection.use { db ->
            assertNotNull(db.consume(request))
            assertTrue(db.revoke(request.grant))
        }
        assertReceipt(body, 204)
        assertReceipt(body + ("binding" to "1".repeat(64)), 404)
    }

    private fun assertReceipt(body: Map<String, Any>, status: Int) {
        given().contentType("application/json").body(body)
            .post("/api/v2/internal/passport-account-admissions/receipt").then().statusCode(status)
    }

    private class GrantReplica(private val grant: UUID, private val pageSize: Int) {
        private var cursor: String? = null
        var snapshot: Map<String, Any>? = null
            private set
        var firstSnapshot: Map<String, Any>? = null
            private set
        val states = mutableListOf<String>()

        fun catchUp() {
            while (true) {
                val query = given().queryParam("limit", pageSize)
                cursor?.let { query.queryParam("after", it) }
                val page = query.get("/api/v2/events").then().statusCode(200).extract().jsonPath()
                val changes = page.getList("items", Map::class.java)
                if (changes.isEmpty()) return
                changes.forEachIndexed { index, item ->
                    if (item["aggregateId"] == grant.toString() && item["aggregateType"] == "PASSPORT_GRANT") {
                        assertEquals("com.openbank.catalog.passport-grant.changed", item["eventType"])
                        val payload = page.getMap<String, Any>("items[$index].payload")
                        assertFalse(
                            payload.keys.any {
                                it in
                                    setOf("evidenceRef", "proposedBy", "approvedBy", "revokeReason")
                            },
                        )
                        apply(payload)
                    }
                }
                cursor = page.getString("nextCursor")
            }
        }

        fun apply(payload: Map<String, Any>) {
            val previous = snapshot?.get("version") as? Int ?: 0
            if ((payload.getValue("version") as Int) > previous) {
                if (firstSnapshot == null) firstSnapshot = payload
                snapshot = payload
                states += payload.getValue("state") as String
            }
        }
    }

    private fun Connection.admit(request: PermitRequest): Any? = scalar(
        "SELECT admit_passport_account(?, ?, ?, ?, ?, ?)",
        request.grant,
        request.operation,
        request.operationId,
        request.party,
        request.revision,
        HASH,
    )

    private fun <T> race(winner: Connection, contend: (Connection) -> T): T =
        Executors.newSingleThreadExecutor().use { executor ->
            dataSource.connection.use { contender ->
                val pid = contender.scalar("SELECT pg_backend_pid()") as Int
                val winnerPid = winner.scalar("SELECT pg_backend_pid()") as Int
                contender.autoCommit = false
                contender.createStatement().use { it.execute("SET LOCAL statement_timeout = '10s'") }
                val waiting = executor.submit<T> {
                    val outcome = contend(contender)
                    contender.commit()
                    outcome
                }
                awaitBlocked(waiting, winnerPid, pid)
                winner.commit()
                waiting.get(5, TimeUnit.SECONDS)
            }
        }

    private fun awaitBlocked(waiting: Future<*>, winnerPid: Int, pid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        dataSource.connection.use { observer ->
            while (observer.scalar("SELECT ? = ANY(pg_blocking_pids(?))", winnerPid, pid) != true) {
                check(!waiting.isDone && System.nanoTime() < deadline) { "Contender did not wait on the grant" }
                Thread.sleep(10)
            }
        }
    }

    private fun seed(
        operation: String,
        issue: Boolean = true,
        approved: Boolean = true,
        mode: String = "FOS",
    ): PermitRequest {
        val request = PermitRequest(operation, country = if (mode == "BRANCH") "AT" else "SK", mode = mode)
        dataSource.connection.use { db ->
            val offering = UUID.randomUUID()
            val specification = UUID.randomUUID()
            db.seedSpecification(specification, operation in setOf("SIGN", "DISBURSE"))
            db.execute(
                """
                INSERT INTO catalog_offerings (id, specification_id, code, market)
                VALUES (?, ?, ?, jsonb_build_object('countries', jsonb_build_array(CAST(? AS text))))
                """.trimIndent(),
                offering,
                specification,
                "OFFER_$offering",
                request.country,
            )
            db.execute(
                """
                INSERT INTO catalog_revisions (id, offering_id, revision_no, schema_id, schema_version,
                    state, content, content_hash, effective_from, maker_id, checker_id, created_at, updated_at)
                SELECT ?, ?, 1, schema_id, schema_version, 'PUBLISHED', '{}', ?, CURRENT_TIMESTAMP - interval '1 day',
                    'synthetic_maker', 'synthetic_checker', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    FROM catalog_specifications WHERE id = ?
                """.trimIndent(),
                request.revision,
                offering,
                request.contentHash,
                specification,
            )
            db.execute(
                """
                INSERT INTO passport_grants (id, bank_id, establishment_id, host_country, mode, offering_id,
                    channel, customer_segment, pack_set_digest, evidence_ref, proposed_by,
                    effective_from, effective_until)
                VALUES (?, ?, ?, ?, ?, ?, 'WEB', 'RETAIL', ?, 'synthetic-proof', ?,
                    CURRENT_TIMESTAMP - interval '1 day', CURRENT_TIMESTAMP + interval '1 day')
                """.trimIndent(),
                request.grant,
                request.bank,
                request.establishment,
                request.country,
                request.mode,
                offering,
                HASH,
                request.maker,
            )
            if (approved) {
                assertEquals(
                    true,
                    db.scalar("SELECT approve_passport_grant(?, ?, 'synthetic')", request.grant, UUID.randomUUID()),
                )
            }
            if (issue) assertEquals(request.permit, db.issue(request))
        }
        return request
    }

    private fun Connection.seedSpecification(specification: UUID, loan: Boolean) = execute(
        """
        INSERT INTO catalog_specifications (id, code, schema_id, schema_version, created_at)
        SELECT ?, ?, schema_id, schema_version, CURRENT_TIMESTAMP FROM catalog_schemas
            WHERE schema_id = ? ORDER BY schema_version DESC LIMIT 1
        """.trimIndent(),
        specification,
        "SPEC_$specification",
        if (loan) "org.openbank.banking.loan" else "org.openbank.banking.legacy-product",
    )

    private fun Connection.issue(request: PermitRequest): UUID? = scalar(
        "SELECT issue_passport_account_permit(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        request.permit,
        request.grant,
        request.operation,
        request.operationId,
        request.party,
        request.revision,
        HASH,
        request.bank,
        request.establishment,
        request.country,
        "FOS",
        "WEB",
        "RETAIL",
        HASH,
    ) as? UUID

    private fun Connection.consume(request: PermitRequest): OffsetDateTime? =
        prepareStatement("SELECT consume_passport_account_permit(?, ?, ?, ?, ?, ?)").use { statement ->
            listOf(
                request.permit,
                request.operation,
                request.operationId,
                request.party,
                request.revision,
                HASH,
            ).forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result ->
                check(result.next())
                result.getObject(1, OffsetDateTime::class.java)
            }
        }

    private fun Connection.revoke(grant: UUID): Boolean =
        scalar("SELECT revoke_passport_grant(?, ?, 'synthetic revoke')", grant, UUID.randomUUID()) as Boolean

    private fun Connection.scalar(sql: String, vararg values: Any): Any? = prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { result ->
            check(result.next())
            result.getObject(1)
        }
    }

    private fun Connection.execute(sql: String, vararg values: Any) = prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        assertEquals(1, statement.executeUpdate())
    }

    private data class PermitRequest(
        val operation: String,
        val grant: UUID = UUID.randomUUID(),
        val permit: UUID = UUID.randomUUID(),
        val operationId: UUID = UUID.randomUUID(),
        val party: UUID = UUID.randomUUID(),
        val revision: UUID = UUID.randomUUID(),
        val contentHash: String = "1".repeat(64),
        val bank: UUID = UUID.randomUUID(),
        val establishment: UUID = UUID.randomUUID(),
        val country: String = "SK",
        val mode: String = "FOS",
        val maker: UUID = UUID.randomUUID(),
    )

    private companion object {
        val HASH = "a".repeat(64)
    }
}
