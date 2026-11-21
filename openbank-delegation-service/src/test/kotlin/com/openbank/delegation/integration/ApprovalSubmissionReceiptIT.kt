// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import com.openbank.delegation.application.port.out.ApprovalScaVerifier
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import io.restassured.response.Response
import jakarta.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** #12317 receipt and concurrency guarantees over real HTTP and PostgreSQL. */
@QuarkusTest
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
@OidcSecurity(
    claims = [
        Claim(key = "sub", value = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"),
        Claim(key = "azp", value = "openbank-edge"),
        Claim(key = "preferred_username", value = "service-account-openbank-edge"),
    ],
)
@QuarkusTestResource(BusinessSigningApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class ApprovalSubmissionReceiptIT {

    private val register = FakeMandateDirectory()
    private val sca = FakeApprovalSca()

    private val entity = UUID.randomUUID()
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val carol = UUID.randomUUID()

    @Inject
    lateinit var mandateSeam: SwitchableMandateDirectory

    @Inject
    lateinit var scaSeam: SwitchableApprovalSca

    @BeforeEach
    fun installFakes() {
        mandateSeam.delegate = register
        scaSeam.delegate = sca
    }

    @Test
    fun `identical domestic submissions commit one receipt signature and outbox event`() {
        register.joint(entity, 2, alice, bob)
        val correlation = newCorrelation()
        val challenge = sca.initiator(alice)
        val body = paymentBody(alice, challenge, submissionCorrelation = correlation)
        val responses = racePaymentCreates(body, body)

        assertThat(responses.map { it.statusCode }).containsOnly(HTTP_CREATED)
        val id = UUID.fromString(responses.first().jsonPath().getString("id"))
        assertThat(responses.map { it.jsonPath().getString("id") }).containsOnly(id.toString())
        assertThat(receiptCount(correlation)).isEqualTo(1)
        assertThat(get(id).getList<String>("signatures.scaChallengeId")).containsExactly(challenge.toString())
        assertThat(outboxCount(id, "APPROVAL_REQUESTED")).isEqualTo(1)
        assertThat(outboxPayload(id, "APPROVAL_REQUESTED")).doesNotContain(correlation)
        assertThatThrownBy { deleteReceipt(correlation) }.isInstanceOfSatisfying(SQLException::class.java) {
            assertThat(it.sqlState).isEqualTo("P0001")
        }

        val changed = paymentBody(alice, challenge, amount = "120001.00", submissionCorrelation = correlation)
        val conflict = post("/approval-requests", changed)
        assertThat(conflict.statusCode).isEqualTo(HTTP_CONFLICT)
        assertThat(conflict.jsonPath().getString("code")).isEqualTo("SUBMISSION_CORRELATION_REUSED")

        val found = lookupSubmission(correlation)
        assertThat(found.statusCode).isEqualTo(HTTP_OK)
        assertThat(found.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(found.jsonPath().getMap<String, Any?>("$").keys)
            .contains("approvalId", "status")
            .allMatch { it in setOf("approvalId", "status", "releaseRef") }
        assertThat(found.jsonPath().getString("approvalId")).isEqualTo(id.toString())
        assertThat(found.jsonPath().getString("status")).isEqualTo("PENDING")
        for (missing in listOf(lookupSubmission(correlation, bob), lookupSubmission(correlation, entityId = carol))) {
            assertThat(missing.statusCode).isEqualTo(HTTP_NOT_FOUND)
            assertThat(missing.getHeader("Cache-Control")).isEqualTo("no-store")
        }
    }

    @Test
    fun `concurrent different payloads sharing one correlation return one approval and a stable conflict`() {
        register.joint(entity, 2, alice, bob)
        val correlation = newCorrelation()
        val first = paymentBody(alice, sca.initiator(alice), submissionCorrelation = correlation)
        val second = paymentBody(alice, sca.initiator(alice), amount = "120001.00", submissionCorrelation = correlation)

        val responses = racePaymentCreates(first, second)
        assertThat(responses.map { it.statusCode }).containsExactlyInAnyOrder(HTTP_CREATED, HTTP_CONFLICT)
        val refused = responses.single { it.statusCode == HTTP_CONFLICT }
        assertThat(refused.jsonPath().getString("code")).isEqualTo("SUBMISSION_CORRELATION_REUSED")
        assertThat(receiptCount(correlation)).isEqualTo(1)
    }

    @Test
    fun `an already consumed challenge cannot bind a competing or retroactive receipt`() {
        register.joint(entity, 2, alice, bob)
        val challenge = sca.initiator(alice)
        val correlations = List(2) { newCorrelation() }
        val bodies = correlations.map { paymentBody(alice, challenge, submissionCorrelation = it) }
        val responses = racePaymentCreates(bodies[0], bodies[1])
        assertThat(responses.map { it.statusCode }).containsExactlyInAnyOrder(HTTP_CREATED, HTTP_CONFLICT)
        val loser = responses.indexOfFirst { it.statusCode == HTTP_CONFLICT }
        assertThat(responses[loser].jsonPath().getString("code")).isEqualTo("SCA_CHALLENGE_USED")
        assertThat(receiptCount(correlations[loser])).isZero()
        assertThat(lookupSubmission(correlations[loser]).statusCode).isEqualTo(HTTP_NOT_FOUND)

        val legacyChallenge = sca.initiator(alice)
        val legacy = post("/approval-requests", paymentBody(alice, legacyChallenge))
        assertThat(legacy.statusCode).isEqualTo(HTTP_CREATED)
        val late = newCorrelation()
        val lateAttempt = post("/approval-requests", paymentBody(alice, legacyChallenge, submissionCorrelation = late))
        assertThat(lateAttempt.statusCode).isEqualTo(HTTP_CONFLICT)
        assertThat(lateAttempt.jsonPath().getString("code")).isEqualTo("SCA_CHALLENGE_USED")
        assertThat(lookupSubmission(late).statusCode).isEqualTo(HTTP_NOT_FOUND)
    }

    @Test
    fun `a legacy submission racing a correlated retry cannot create a second approval`() {
        register.joint(entity, 2, alice, bob)
        val challenge = sca.initiator(alice)
        val correlation = newCorrelation()
        val legacy = paymentBody(alice, challenge)
        val correlated = paymentBody(alice, challenge, submissionCorrelation = correlation)
        val (ordinaryResponse, correlatedResponse) = racePaymentCreates(legacy, correlated)
        assertThat(ordinaryResponse.statusCode).isEqualTo(HTTP_CREATED)
        val id = UUID.fromString(ordinaryResponse.jsonPath().getString("id"))
        assertThat(correlatedResponse.statusCode).isIn(HTTP_CREATED, HTTP_CONFLICT)
        if (correlatedResponse.statusCode == HTTP_CREATED) {
            assertThat(correlatedResponse.jsonPath().getString("id")).isEqualTo(id.toString())
            assertThat(lookupSubmission(correlation).jsonPath().getString("approvalId")).isEqualTo(id.toString())
        } else {
            assertThat(correlatedResponse.jsonPath().getString("code")).isEqualTo("SCA_CHALLENGE_USED")
            assertThat(lookupSubmission(correlation).statusCode).isEqualTo(HTTP_NOT_FOUND)
        }
        assertThat(outboxCount(id, "APPROVAL_REQUESTED")).isEqualTo(1)
        val ids = RestAssured.given().get("/api/v1/entities/$entity/approval-requests")
            .then().statusCode(HTTP_OK).extract().jsonPath().getList<String>("data.id")
        assertThat(ids).containsExactly(id.toString())
    }

    @Test
    fun `receipt reports lifecycle but only a recorded success supplies a release reference`() {
        register.joint(entity, 2, alice, bob)
        val correlation = newCorrelation()
        val body = paymentBody(alice, sca.initiator(alice), submissionCorrelation = correlation)
        val created = post("/approval-requests", body).then().statusCode(HTTP_CREATED).extract().jsonPath()
        val id = UUID.fromString(created.getString("id"))
        sign(id, bob, sca.approval(bob, id, created.getString("payloadSha256"))).then().statusCode(HTTP_OK)
        assertThat(lookupSubmission(correlation).jsonPath().getString("status")).isEqualTo("APPROVED")
        val claim = claim(id).then().statusCode(HTTP_OK).extract().jsonPath()
        assertThat(claim.getMap<String, Any>("$")).containsOnlyKeys("claimToken", "payload")
        val inFlight = lookupSubmission(correlation).jsonPath()
        assertThat(inFlight.getString("status")).isEqualTo("RELEASED")
        assertThat(inFlight.getString("releaseRef")).isNull()
        val releaseRef = UUID.randomUUID().toString()
        post(
            "/approval-requests/$id/release-result",
            mapOf("ok" to true, "releaseRef" to releaseRef, "claimToken" to claim.getString("claimToken")),
        ).then().statusCode(HTTP_OK)
        val released = lookupSubmission(correlation)
        assertThat(released.jsonPath().getString("releaseRef")).isEqualTo(releaseRef)
        assertThat(released.body.asString()).doesNotContain(claim.getString("claimToken"), CREDITOR, correlation)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"),
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `an API identity with the Edge username and a different client cannot write or read receipts`() {
        register.joint(entity, 2, alice, bob)
        val correlation = newCorrelation()
        val body = paymentBody(alice, sca.initiator(alice), submissionCorrelation = correlation)

        assertThat(post("/approval-requests", body).statusCode).isEqualTo(HTTP_FORBIDDEN)
        assertThat(lookupSubmission(correlation).statusCode).isEqualTo(HTTP_FORBIDDEN)
        assertThat(receiptCount(correlation)).isZero()
    }

    /** Exactly the body customer-edge (#10314) sends. */
    private fun paymentBody(
        initiator: UUID,
        challenge: UUID,
        amount: String = "120000.00",
        submissionCorrelation: String? = null,
    ) = buildMap<String, Any?> {
        put("kind", "PAYMENT")
        put("initiatorSignature", mapOf("partyId" to initiator, "scaChallengeId" to challenge))
        put(
            "payload",
            mapOf(
                "rail" to "DOMESTIC",
                "amount" to amount,
                "currency" to "CZK",
                "creditorIban" to CREDITOR,
                "creditorName" to "Dodavatel s.r.o.",
                "reference" to "INV-2026-09",
                "railRequest" to
                    mapOf("amount" to amount, "currency" to "CZK", "creditorAccount" to mapOf("iban" to CREDITOR)),
            ),
        )
        submissionCorrelation?.let { put("submissionCorrelation", it) }
    }

    private fun newCorrelation(): String = UUID.randomUUID().toString().replace("-", "").repeat(2)

    private fun lookupSubmission(correlation: String, initiator: UUID = alice, entityId: UUID = entity) =
        RestAssured.given().contentType(ContentType.JSON)
            .body(mapOf("submissionCorrelation" to correlation, "initiatorPartyId" to initiator))
            .post("/api/v1/entities/$entityId/approval-requests/submissions/lookup")

    private fun receiptCount(correlation: String): Int = jdbc().use { connection ->
        connection.prepareStatement("select count(*) from approval_submission_receipts where correlation_digest = ?")
            .use { statement ->
                statement.setString(1, correlation)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
    }

    private fun deleteReceipt(correlation: String) = jdbc().use { connection ->
        connection.prepareStatement("delete from approval_submission_receipts where correlation_digest = ?")
            .use { statement ->
                statement.setString(1, correlation)
                statement.executeUpdate()
            }
    }

    private fun racePaymentCreates(first: Any, second: Any): List<Response> {
        val ready = CompletableDeferred<Unit>()
        val arrivals = AtomicInteger()
        scaSeam.delegate = object : ApprovalScaVerifier by sca {
            override suspend fun verifyConsumedInitiatorChallenge(challengeId: UUID, partyId: UUID) =
                withTimeout(TimeUnit.SECONDS.toMillis(BARRIER_SECONDS)) {
                    if (arrivals.incrementAndGet() == 2) ready.complete(Unit)
                    ready.await()
                    sca.verifyConsumedInitiatorChallenge(challengeId, partyId)
                }
        }
        val pool = Executors.newFixedThreadPool(2)
        return try {
            listOf(first, second).map { body -> pool.submit<Response> { post("/approval-requests", body) } }
                .map { it.get(CALL_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            scaSeam.delegate = sca
        }
    }

    private fun sign(id: UUID, party: UUID, challenge: UUID) =
        post("/approval-requests/$id/signatures", mapOf("partyId" to party, "scaChallengeId" to challenge))

    private fun claim(id: UUID) = RestAssured.given().contentType(ContentType.JSON)
        .post("/api/v1/entities/$entity/approval-requests/$id/release-claim")

    private fun get(id: UUID): JsonPath = RestAssured.given().get(
        "/api/v1/entities/$entity/approval-requests/$id",
    ).then().statusCode(HTTP_OK).extract().jsonPath()

    private fun post(path: String, body: Any) = RestAssured.given().contentType(ContentType.JSON).body(body)
        .post("/api/v1/entities/$entity$path")

    private fun jdbc() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        "openbank",
        "openbank_secret",
    )

    private fun outboxCount(id: UUID, type: String): Int = jdbc().use { c ->
        c.prepareStatement("select count(*) from delegation_outbox where aggregate_id = ? and event_type = ?").use {
            it.setObject(1, id)
            it.setString(2, type)
            it.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun outboxPayload(id: UUID, type: String): String = jdbc().use { c ->
        c.prepareStatement("select payload from delegation_outbox where aggregate_id = ? and event_type = ?").use {
            it.setObject(1, id)
            it.setString(2, type)
            it.executeQuery().use { rs ->
                assertThat(rs.next()).describedAs("$type outbox row").isTrue()
                rs.getString(1)
            }
        }
    }

    private companion object {
        const val CREDITOR = "CZ6508000000192000145399"
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val BARRIER_SECONDS = 10L
        const val CALL_SECONDS = 30L
    }
}
