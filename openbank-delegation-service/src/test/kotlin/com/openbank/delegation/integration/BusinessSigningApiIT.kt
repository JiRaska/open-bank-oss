// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-0312 by effect: real HTTP through the resource, OPA advisory as in every IT here, a real
 * Postgres (Testcontainers) behind the reactive repository, and only the two outbound systems —
 * party-service's register and sca-service — replaced by stateful fakes that behave as those
 * services do. Each test names the guard it proves; the PR's sabotage table lists which one goes
 * red when that guard is removed.
 */
@QuarkusTest
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
@QuarkusTestResource(BusinessSigningApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class BusinessSigningApiIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory(
            "delegation-events-out",
            "spend-reservation-state-out",
            "approval-events-out",
        )

        override fun stop() = InMemoryConnector.clear()
    }

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

    // ------------------------------------------------------------------ happy path + events

    @Test
    fun `a JOINT register holds the payment until a second distinct human signs, then releases once`() {
        register.joint(entity, 2, alice, bob, carol)
        register.names[entity] = "Dodavatel s.r.o."
        register.people[alice] = "Jana Nováková"
        val created = createPayment(alice)
        assertThat(created.getString("status")).isEqualTo("PENDING")
        assertThat(created.getInt("required")).isEqualTo(2)
        assertThat(created.getInt("collected")).isEqualTo(1)
        val id = UUID.fromString(created.getString("id"))
        val sha = created.getString("payloadSha256")

        // Tell every eligible signer who has not signed — never the initiator.
        val requested = outboxPayload(id, "APPROVAL_REQUESTED")
        assertThat(requested).contains(bob.toString(), carol.toString())
        assertThat(recipientsOf(requested)).doesNotContain(alice.toString())
        // The flat v1 fields notification-service (#10313) reads.
        val v1 = JsonPath(requested)
        assertThat(v1.getString("type")).isEqualTo("APPROVAL_REQUESTED")
        assertThat(v1.getString("approvalId")).isEqualTo(id.toString())
        assertThat(v1.getString("initiatorPartyId")).isEqualTo(alice.toString())
        assertThat(v1.getString("entityName")).isEqualTo("Dodavatel s.r.o.")
        assertThat(v1.getString("initiatorName")).isEqualTo("Jana Nováková")
        assertThat(v1.getString("amount")).isEqualTo("120000.00")
        assertThat(v1.getString("currency")).isEqualTo("CZK")
        assertThat(v1.getString("payeeName")).isEqualTo("Dodavatel s.r.o.")
        assertThat(v1.getString("kind")).isEqualTo("PAYMENT")
        assertThat(v1.getInt("schemaVersion")).isEqualTo(1)
        assertThat(
            java.time.Instant.parse(v1.getString("expiresAt")),
        ).describedAs("ISO-8601, as the consumer parses it").isAfter(java.time.Instant.now())

        val signed = sign(id, bob, sca.approval(bob, id, sha))
        assertThat(signed.statusCode).isEqualTo(HTTP_OK)
        assertThat(signed.jsonPath().getString("status")).isEqualTo("APPROVED")
        assertThat(outboxCount(id, "APPROVAL_COMPLETED")).isEqualTo(1)

        val claim = claim(id)
        assertThat(claim.statusCode).isEqualTo(HTTP_OK)
        assertThat(claim.jsonPath().getString("payload.railRequest.creditorAccount.iban")).isEqualTo(CREDITOR)

        val result = post("/approval-requests/$id/release-result", mapOf("ok" to true, "releaseRef" to "DOM-123"))
        assertThat(result.statusCode).isEqualTo(HTTP_OK)
        assertThat(result.jsonPath().getString("releaseRef")).isEqualTo("DOM-123")
        assertThat(outboxCount(id, "PAYMENT_RELEASED")).isEqualTo(1)
    }

    // ------------------------------------------------------------------ guard: initiator-not-cosigner

    @Test
    fun `the initiator can never count as a co-signer`() {
        register.joint(entity, 2, alice, bob)
        val created = createPayment(alice)
        val id = UUID.fromString(created.getString("id"))

        val again = sign(id, alice, sca.approval(alice, id, created.getString("payloadSha256")))

        assertThat(again.statusCode).isEqualTo(HTTP_CONFLICT)
        assertThat(again.jsonPath().getString("code")).isEqualTo("ALREADY_SIGNED")
        assertThat(get(id).getString("status")).isEqualTo("PENDING")
        assertThat(get(id).getInt("collected")).isEqualTo(1)
    }

    // ------------------------------------------------------------------ guard: distinct signer

    @Test
    fun `the same person counts once, however many ceremonies they complete`() {
        register.joint(entity, 3, alice, bob, carol)
        val created = createPayment(alice)
        val id = UUID.fromString(created.getString("id"))
        val sha = created.getString("payloadSha256")
        assertThat(sign(id, bob, sca.approval(bob, id, sha)).statusCode).isEqualTo(HTTP_OK)

        val second = sign(id, bob, sca.approval(bob, id, sha))

        assertThat(second.statusCode).isEqualTo(HTTP_CONFLICT)
        assertThat(second.jsonPath().getString("code")).isEqualTo("ALREADY_SIGNED")
        assertThat(get(id).getInt("collected")).isEqualTo(2)
        assertThat(get(id).getString("status")).isEqualTo("PENDING")
    }

    // ------------------------------------------------------------------ guard: live mandate at signing

    @Test
    fun `a signer whose mandate was revoked after the request was created cannot sign`() {
        register.joint(entity, 2, alice, bob)
        val created = createPayment(alice)
        val id = UUID.fromString(created.getString("id"))
        val challenge = sca.approval(bob, id, created.getString("payloadSha256"))
        register.revoke(entity, bob)

        val response = sign(id, bob, challenge)

        assertThat(response.statusCode).isEqualTo(HTTP_FORBIDDEN)
        assertThat(response.jsonPath().getString("code")).isEqualTo("MANDATE_NOT_ACTIVE")
        assertThat(sca.consumeCalls).describedAs("a refused signer's ceremony is not spent").doesNotContain(challenge)
        assertThat(get(id).getString("status")).isEqualTo("PENDING")
    }

    // ------------------------------------------------------------------ guard: live mandate at release

    @Test
    fun `a round that no longer holds at release is refused`() {
        register.joint(entity, 2, alice, bob)
        val created = createPayment(alice)
        val id = UUID.fromString(created.getString("id"))
        sign(id, bob, sca.approval(bob, id, created.getString("payloadSha256")))
        register.revoke(entity, bob)

        val response = claim(id)

        assertThat(response.statusCode).isEqualTo(HTTP_CONFLICT)
        assertThat(response.jsonPath().getString("code")).isEqualTo("MANDATE_LAPSED")
        assertThat(get(id).getString("status")).isEqualTo("APPROVED")
    }

    // ------------------------------------------------------------------ guard: dynamic linking

    @Test
    fun `a challenge linked to a different payload does not sign`() {
        register.joint(entity, 2, alice, bob)
        val created = createPayment(alice)
        val id = UUID.fromString(created.getString("id"))
        val forged = sca.approval(bob, id, "0".repeat(SHA_LENGTH))

        val response = sign(id, bob, forged)

        assertThat(response.statusCode).isEqualTo(HTTP_FORBIDDEN)
        assertThat(response.jsonPath().getString("code")).isEqualTo("SCA_NOT_LINKED")
        assertThat(get(id).getInt("collected")).isEqualTo(1)
    }

    @Test
    fun `an initiator whose payment SCA was not consumed cannot create a held payment`() {
        register.joint(entity, 2, alice, bob)
        val response = post(
            "/approval-requests",
            paymentBody(alice, UUID.randomUUID()),
        )
        assertThat(response.statusCode).isEqualTo(HTTP_FORBIDDEN)
        assertThat(response.jsonPath().getString("code")).isEqualTo("SCA_NOT_VERIFIED")
    }

    // ------------------------------------------------------------------ guard: single-use release

    @Test
    fun `concurrent release claims have exactly one winner`() {
        register.joint(entity, 2, alice, bob)
        val created = createPayment(alice)
        val id = UUID.fromString(created.getString("id"))
        sign(id, bob, sca.approval(bob, id, created.getString("payloadSha256")))

        val barrier = CyclicBarrier(RACERS)
        val pool = Executors.newFixedThreadPool(RACERS)
        val statuses = try {
            (1..RACERS).map {
                pool.submit<Int> {
                    barrier.await(BARRIER_SECONDS, TimeUnit.SECONDS)
                    claim(id).statusCode
                }
            }.map { it.get(CALL_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(statuses.count { it == HTTP_OK }).isEqualTo(1)
        assertThat(statuses.count { it == HTTP_CONFLICT }).isEqualTo(RACERS - 1)
        assertThat(get(id).getString("status")).isEqualTo("RELEASED")
        assertThat(claim(id).statusCode).describedAs("a later claim is refused too").isEqualTo(HTTP_CONFLICT)
    }

    // ------------------------------------------------------------------ guard: expiry

    @Test
    fun `an expired request can be neither signed nor released`() {
        register.joint(entity, 2, alice, bob)
        val created = createPayment(alice, expiresInSeconds = 1)
        val id = UUID.fromString(created.getString("id"))
        Thread.sleep(EXPIRY_WAIT_MILLIS)

        val response = sign(id, bob, sca.approval(bob, id, created.getString("payloadSha256")))

        assertThat(response.statusCode).isEqualTo(HTTP_CONFLICT)
        assertThat(response.jsonPath().getString("code")).isEqualTo("EXPIRED")
        assertThat(claim(id).statusCode).isEqualTo(HTTP_CONFLICT)
    }

    // ------------------------------------------------------------------ guard: trusted payee only after approval

    @Test
    fun `a payee becomes trusted only when the full round completes`() {
        register.joint(entity, 2, alice, bob)
        val proposal = post(
            "/trusted-payees",
            mapOf(
                "initiatorPartyId" to alice,
                "iban" to "CZ65 0800 0000 1920 0014 5399",
                "name" to "Dodavatel s.r.o.",
            ),
        )
        assertThat(proposal.statusCode).isEqualTo(HTTP_ACCEPTED)
        val id = UUID.fromString(proposal.jsonPath().getString("id"))
        val sha = proposal.jsonPath().getString("payloadSha256")
        assertThat(proposal.jsonPath().getInt("required")).isEqualTo(2)

        assertThat(evaluate().getBoolean("trusted")).describedAs("pending ⇒ not trusted").isFalse()
        assertThat(sign(id, alice, sca.approval(alice, id, sha)).jsonPath().getString("status")).isEqualTo("PENDING")
        assertThat(evaluate().getBoolean("trusted")).describedAs("one of two ⇒ not trusted").isFalse()
        assertThat(sign(id, bob, sca.approval(bob, id, sha)).jsonPath().getString("status")).isEqualTo("APPROVED")

        val after = evaluate()
        assertThat(after.getBoolean("trusted")).isTrue()
        assertThat(after.getInt("required")).isEqualTo(1)
    }

    // ------------------------------------------------------------------ guard: policy change needs the full round

    @Test
    fun `a policy change needs the strictest round even when the first band needs one signature`() {
        register.joint(entity, 2, alice, bob)
        seedPolicy(
            """[{"maxAmount":{"amount":"50000","currency":"CZK"},"currency":"CZK","requiredSignatures":1},
               {"requiredSignatures":2}]""",
        )
        val proposal = RestAssured.given().contentType(ContentType.JSON)
            .body(mapOf("initiatorPartyId" to alice, "rules" to listOf(mapOf("requiredSignatures" to 1))))
            .put("/api/v1/entities/$entity/signing-policy")
        assertThat(proposal.statusCode).isEqualTo(HTTP_ACCEPTED)
        val id = UUID.fromString(proposal.jsonPath().getString("id"))
        val sha = proposal.jsonPath().getString("payloadSha256")
        assertThat(proposal.jsonPath().getInt("required")).isEqualTo(2)

        assertThat(sign(id, alice, sca.approval(alice, id, sha)).jsonPath().getString("status")).isEqualTo("PENDING")
        assertThat(policy().getInt("version")).describedAs("unchanged until the round completes").isEqualTo(1)
        assertThat(sign(id, bob, sca.approval(bob, id, sha)).jsonPath().getString("status")).isEqualTo("APPROVED")
        assertThat(policy().getInt("version")).isEqualTo(2)
        assertThat(policy().getList<Any>("rules")).hasSize(1)
    }

    // ------------------------------------------------------------------ guard: no notification before the initiator signs

    @Test
    fun `an administrative request stays silent and co-signer-proof until its initiator signs`() {
        register.joint(entity, 2, alice, bob)
        val proposal = post(
            "/trusted-payees",
            mapOf(
                "initiatorPartyId" to alice,
                "iban" to CREDITOR,
                "name" to "Dodavatel s.r.o.",
            ),
        )
        assertThat(proposal.statusCode).isEqualTo(HTTP_ACCEPTED)
        val id = UUID.fromString(proposal.jsonPath().getString("id"))
        val sha = proposal.jsonPath().getString("payloadSha256")
        assertThat(proposal.jsonPath().getString("status")).isEqualTo("AWAITING_INITIATOR")
        assertThat(proposal.jsonPath().getInt("collected")).isZero()
        assertThat(sha).hasSize(SHA_LENGTH)
        assertThat(
            outboxCount(id, "APPROVAL_REQUESTED"),
        ).describedAs("nobody is told before the initiator signs").isZero()

        val early = sign(id, bob, sca.approval(bob, id, sha))
        assertThat(early.statusCode).isEqualTo(HTTP_CONFLICT)
        assertThat(early.jsonPath().getString("code")).isEqualTo("AWAITING_INITIATOR")

        val first = sign(id, alice, sca.approval(alice, id, sha))
        assertThat(first.jsonPath().getString("status")).isEqualTo("PENDING")
        assertThat(first.jsonPath().getInt("collected")).isEqualTo(1)
        val requested = outboxPayload(id, "APPROVAL_REQUESTED")
        assertThat(recipientsOf(requested)).containsExactly(bob.toString())
        assertThat(JsonPath(requested).getString("entityPartyId")).isEqualTo(entity.toString())
        assertThat(outboxCount(id, "APPROVAL_SIGNED")).isZero()

        val duplicate = post(
            "/trusted-payees",
            mapOf(
                "initiatorPartyId" to alice,
                "iban" to CREDITOR,
                "name" to "Dodavatel s.r.o.",
            ),
        )
        assertThat(duplicate.statusCode).isEqualTo(HTTP_CONFLICT)
        assertThat(duplicate.jsonPath().getString("code")).isEqualTo("PAYEE_CHANGE_PENDING")
    }

    @Test
    fun `a retried payment creation replays the original request`() {
        register.joint(entity, 2, alice, bob)
        val challenge = sca.initiator(alice)
        val first = post("/approval-requests", paymentBody(alice, challenge))
        val retry = post("/approval-requests", paymentBody(alice, challenge))
        assertThat(first.statusCode).isEqualTo(HTTP_CREATED)
        assertThat(retry.statusCode).isEqualTo(HTTP_CREATED)
        assertThat(retry.jsonPath().getString("id")).isEqualTo(first.jsonPath().getString("id"))
    }

    // ------------------------------------------------------------------ reads

    @Test
    fun `a signer sees what waits for them across entities, and a rejection closes the request`() {
        register.joint(entity, 2, alice, bob)
        register.names[entity] = "Dodavatel s.r.o."
        val created = createPayment(alice)
        val id = UUID.fromString(created.getString("id"))

        val list = RestAssured.given().get("/api/v1/entities/$entity/approval-requests?status=PENDING&signer=$bob")
            .then().statusCode(HTTP_OK).extract().jsonPath()
        assertThat(list.getList<Any>("data")).hasSize(1)

        val pending = RestAssured.given().get(
            "/api/v1/parties/$bob/approval-requests/pending",
        ).then().statusCode(HTTP_OK)
            .extract().jsonPath()
        assertThat(pending.getInt("total")).isEqualTo(1)
        assertThat(pending.getString("entities[0].entityName")).isEqualTo("Dodavatel s.r.o.")
        val initiatorView = RestAssured.given().get("/api/v1/parties/$alice/approval-requests/pending").then()
            .extract().jsonPath()
        assertThat(initiatorView.getInt("total")).describedAs("nothing waits for the initiator").isZero()

        val rejected = post("/approval-requests/$id/rejection", mapOf("partyId" to bob, "reason" to "wrong amount"))
        assertThat(rejected.statusCode).isEqualTo(HTTP_OK)
        assertThat(rejected.jsonPath().getString("status")).isEqualTo("REJECTED")
        assertThat(claim(id).statusCode).isEqualTo(HTTP_CONFLICT)
        assertThat(outboxCount(id, "APPROVAL_REJECTED")).isEqualTo(1)
    }

    @Test
    fun `a SOLE register derives a one-signature policy`() {
        register.sole(entity, alice, bob)
        val policy = policy()
        assertThat(policy.getBoolean("isDerivedFromRegister")).isTrue()
        assertThat(policy.getInt("version")).isZero()
        assertThat(evaluate().getInt("required")).isEqualTo(1)
    }

    // ------------------------------------------------------------------ helpers

    /** Exactly the body customer-edge (#10314) sends. */
    private fun paymentBody(initiator: UUID, challenge: UUID, expiresInSeconds: Long? = null) = buildMap<String, Any?> {
        put("kind", "PAYMENT")
        put("initiatorSignature", mapOf("partyId" to initiator, "scaChallengeId" to challenge))
        put(
            "payload",
            mapOf(
                "rail" to "DOMESTIC",
                "amount" to "120000.00",
                "currency" to "CZK",
                "creditorIban" to CREDITOR,
                "creditorName" to "Dodavatel s.r.o.",
                "reference" to "INV-2026-09",
                "railRequest" to
                    mapOf("amount" to "120000.00", "currency" to "CZK", "creditorAccount" to mapOf("iban" to CREDITOR)),
            ),
        )
        expiresInSeconds?.let { put("expiresInSeconds", it) }
    }

    private fun createPayment(initiator: UUID, expiresInSeconds: Long? = null): JsonPath {
        val response = post("/approval-requests", paymentBody(initiator, sca.initiator(initiator), expiresInSeconds))
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(HTTP_CREATED)
        return response.jsonPath()
    }

    private fun sign(id: UUID, party: UUID, challenge: UUID) =
        post("/approval-requests/$id/signatures", mapOf("partyId" to party, "scaChallengeId" to challenge))

    private fun claim(id: UUID) = RestAssured.given().contentType(ContentType.JSON)
        .post("/api/v1/entities/$entity/approval-requests/$id/release-claim")

    private fun get(id: UUID): JsonPath = RestAssured.given().get(
        "/api/v1/entities/$entity/approval-requests/$id",
    ).then().statusCode(HTTP_OK).extract().jsonPath()

    private fun policy(): JsonPath = RestAssured.given().get(
        "/api/v1/entities/$entity/signing-policy",
    ).then().statusCode(HTTP_OK).extract().jsonPath()

    private fun evaluate(): JsonPath = post(
        "/signing/evaluate",
        mapOf(
            "amount" to "120000.00",
            "currency" to "CZK",
            "creditorIban" to "CZ6508000000192000145399",
            "rail" to "DOMESTIC",
        ),
    ).then().statusCode(HTTP_OK).extract().jsonPath()

    private fun post(path: String, body: Any) = RestAssured.given().contentType(ContentType.JSON).body(body)
        .post("/api/v1/entities/$entity$path")

    private fun jdbc() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        "openbank",
        "openbank_secret",
    )

    private fun seedPolicy(rulesJson: String) = jdbc().use { c ->
        c.prepareStatement(
            "insert into signing_policies (entity_party_id, version, rules_json, updated_at) values (?, 1, ?, now())",
        ).use {
            it.setObject(1, entity)
            it.setString(2, rulesJson.replace(Regex("\\s+"), ""))
            it.executeUpdate()
        }
    }

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

    private fun recipientsOf(payload: String): List<String> = JsonPath(payload).getList("recipientPartyIds")

    private companion object {
        const val CREDITOR = "CZ6508000000192000145399"
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_ACCEPTED = 202
        const val HTTP_FORBIDDEN = 403
        const val HTTP_CONFLICT = 409
        const val SHA_LENGTH = 64
        const val RACERS = 8
        const val BARRIER_SECONDS = 10L
        const val CALL_SECONDS = 30L
        const val EXPIRY_WAIT_MILLIS = 1_500L
    }
}
