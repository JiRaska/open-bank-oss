// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import com.openbank.delegation.application.port.out.MandateDirectory
import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Original domestic key, authority and signing-mode invariants through real HTTP and PostgreSQL. */
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
class DomesticSubmissionModeIT {
    private val register = FakeMandateDirectory()
    private val sca = FakeApprovalSca()
    private val bob = UUID.randomUUID()
    private val submission = Submission(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

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
    fun `identical concurrent claims bind one mode and never create a payment receipt`() {
        register.joint(submission.entity, 2, submission.human, bob)
        val beforeClaim = Instant.now()
        val responses = raceClaims(submission, submission)
        responses.forEach { assertMode(it, "HELD") }
        val binding = bindings().single()
        assertThat(binding.mode).isEqualTo("HELD")
        assertThat(binding.claimedAt).isBetween(beforeClaim, Instant.now())
        assertThat(lookup(submission).statusCode).isEqualTo(HTTP_NOT_FOUND)
        assertThat(approvals()).isEmpty()
    }

    @Test
    fun `claim age supplies no payment outcome and never frees a bound key`() {
        seedOldDirectClaim()
        val binding = bindings().single()
        assertThat(binding.claimedAt).isBefore(Instant.now().minus(Duration.ofDays(365)))
        assertConflict(claim(submission.copy(amount = "120001.00")))
        assertThat(lookup(submission).statusCode).isEqualTo(HTTP_NOT_FOUND)
        assertThat(bindings()).containsExactly(binding)
        assertThat(approvals()).isEmpty()
    }

    @Test
    fun `concurrent changed payloads cannot bind two modes or overwrite the winner`() {
        register.sole(submission.entity, submission.human)
        val changed = submission.copy(amount = "120001.00")
        val responses = raceClaims(submission, changed)
        assertThat(responses.map { it.statusCode }).containsExactlyInAnyOrder(HTTP_OK, HTTP_CONFLICT)
        val winner = if (responses.first().statusCode == HTTP_OK) submission else changed
        assertMode(claim(winner), "DIRECT")
        assertConflict(responses.single { it.statusCode == HTTP_CONFLICT })
        assertThat(bindingModes()).containsExactly("DIRECT")
    }

    @Test
    fun `concurrent policy evaluations cannot bind both a direct and a held mode`() {
        register.joint(submission.entity, 2, submission.human, bob)
        val sole = FakeMandateDirectory().apply { sole(submission.entity, submission.human) }
        val responses = raceClaims(submission, submission, firstRegister = sole)
        val mode = bindingModes().single()
        val accepted = responses.filter { it.statusCode == HTTP_OK }
        assertThat(accepted).isNotEmpty()
        accepted.forEach { assertMode(it, mode) }
        responses.filter { it.statusCode != HTTP_OK }
            .forEach { assertConflict(it, "DOMESTIC_DIRECT_NO_LONGER_AUTHORIZED") }
        assertThat(lookup(submission).statusCode).isEqualTo(HTTP_NOT_FOUND)
        assertThat(approvals()).isEmpty()
    }

    @Test
    fun `stored direct claim cannot dispatch after the company moves to joint signatures`() {
        register.sole(submission.entity, submission.human)
        assertMode(claim(submission), "DIRECT")
        register.joint(submission.entity, 2, submission.human, bob)
        assertConflict(claim(submission), "DOMESTIC_DIRECT_NO_LONGER_AUTHORIZED")
        assertConflict(create(submission))
        assertThat(lookup(submission).statusCode).isEqualTo(HTTP_NOT_FOUND)
        assertThat(bindingModes()).containsExactly("DIRECT")
        assertThat(approvals()).isEmpty()
    }

    @Test
    fun `stored held claim cannot fall through or auto-approve after policy changes`() {
        register.joint(submission.entity, 2, submission.human, bob)
        assertMode(claim(submission), "HELD")
        assertConflict(claim(submission, enforce = false), "DOMESTIC_HELD_POLICY_CHANGED")
        register.sole(submission.entity, submission.human)
        assertConflict(claim(submission), "DOMESTIC_HELD_POLICY_CHANGED")
        assertConflict(create(submission), "DOMESTIC_HELD_POLICY_CHANGED")
        assertThat(lookup(submission).statusCode).isEqualTo(HTTP_NOT_FOUND)
        assertThat(bindingModes()).containsExactly("HELD")
        assertThat(approvals()).isEmpty()
    }

    @Test
    fun `a committed held receipt needs the exact original actor entity debit account and grant`() {
        register.joint(submission.entity, 2, submission.human, bob)
        assertMode(claim(submission), "HELD")
        val original = create(submission).then().statusCode(HTTP_CREATED).extract().jsonPath()
        val id = original.getString("id")
        val changed = listOf(
            submission.copy(entity = UUID.randomUUID()),
            submission.copy(human = bob),
            submission.copy(account = UUID.randomUUID()),
            submission.copy(correlation = newDigest()),
            submission.copy(amount = "120001.00"),
        )
        changed.forEach { assertConflict(claim(it)) }
        assertConflict(create(changed.last()))
        changed.dropLast(1).forEach { assertThat(lookup(it).statusCode).isEqualTo(HTTP_NOT_FOUND) }
        assertThat(lookup(submission.copy(digest = newDigest())).statusCode).isEqualTo(HTTP_NOT_FOUND)
        assertThat(lookup(submission, bound = false).statusCode).isEqualTo(HTTP_NOT_FOUND)

        register.sole(submission.entity, submission.human)
        assertMode(claim(submission), "HELD")
        assertConflict(claim(submission, enforce = false), "DOMESTIC_HELD_POLICY_CHANGED")
        val receipt = lookup(submission).then().statusCode(HTTP_OK).extract().jsonPath()
        assertThat(receipt.getString("approvalId")).isEqualTo(id)
        assertThat(receipt.getString("status")).isEqualTo("PENDING")
        assertThat(approvals()).containsExactly(id)
    }

    @Test
    fun `disabled enforcement permits a new direct binding without reopening it under stricter policy`() {
        register.joint(submission.entity, 2, submission.human, bob)
        assertMode(claim(submission, enforce = false), "DIRECT")
        assertMode(claim(submission, enforce = false), "DIRECT")
        assertConflict(claim(submission, enforce = true), "DOMESTIC_DIRECT_NO_LONGER_AUTHORIZED")
        assertThat(bindingModes()).containsExactly("DIRECT")
        assertThat(approvals()).isEmpty()
    }

    @Test
    fun `domestic mode accepts the rail currency spelling and preserves the frozen payload`() {
        register.joint(submission.entity, 2, submission.human, bob)
        val frozen = submission.payload(currency = " cZk ", railCurrency = "czK")
        assertMode(claim(submission, payload = frozen), "HELD")
        val stored = create(submission, payload = frozen).then().statusCode(HTTP_CREATED).extract().jsonPath()
        assertThat(stored.getString("payload.currency")).isEqualTo(" cZk ")
        assertThat(stored.getString("payload.railRequest.currency")).isEqualTo("czK")
        assertConflict(claim(submission))
    }

    @Test
    fun `bound domestic release carries only the original key digest beside the frozen payload`() {
        register.joint(submission.entity, 2, submission.human, bob)
        assertMode(claim(submission), "HELD")
        val original = create(submission).then().statusCode(HTTP_CREATED).extract().jsonPath()
        val id = UUID.fromString(original.getString("id"))
        val signature = sca.approval(bob, id, original.getString("payloadSha256"))
        post(submission.entity, "/$id/signatures", mapOf("partyId" to bob, "scaChallengeId" to signature))
            .then().statusCode(HTTP_OK)

        val legacyCaller = post(submission.entity, "/$id/release-claim", emptyMap())
        assertThat(legacyCaller.statusCode).isEqualTo(HTTP_FORBIDDEN)
        assertThat(lookup(submission).jsonPath().getString("status")).isEqualTo("APPROVED")

        val release = claimRelease(id)
            .then().statusCode(HTTP_OK).extract().response()
        assertThat(release.header("Cache-Control")).isEqualTo("no-store")
        val result = release.jsonPath()
        assertThat(result.getString("originalSubmissionKeyDigest")).isEqualTo(submission.digest)
        assertThat(result.getMap<String, Any>("payload")).isEqualTo(original.getMap<String, Any>("payload"))
        assertThat(release.body.asString()).doesNotContain(submission.correlation)
        assertThat(lookup(submission).body.asString()).doesNotContain(submission.digest)
        assertThat(claimRelease(id).statusCode).isEqualTo(HTTP_CONFLICT)
    }

    @Test
    fun `exact decimal claim matches the stored numeric rail amount`() {
        register.joint(submission.entity, 2, submission.human, bob)
        val amount = "99999999999999.99"
        val exact = submission.copy(amount = amount)
        assertMode(claim(exact), "HELD")
        val stored = create(exact).then().statusCode(HTTP_CREATED).extract().asString()
        assertThat(stored).contains("\"amount\":$amount", "\"amount\":\"$amount\"")
        val wrongAmount = exact.payload(railAmount = "99999999999999.98")
        assertThat(claim(exact, payload = wrongAmount).statusCode).isEqualTo(HTTP_BAD_REQUEST)
    }

    @Test
    fun `policy and dispatch must use the same debit account and amount`() {
        register.joint(submission.entity, 2, submission.human, bob)
        val wrongAccount = submission.payload(railAccount = UUID.randomUUID())
        val wrongAmount = submission.payload(railAmount = "120001.00")
        listOf(wrongAccount, wrongAmount).forEach {
            assertThat(claim(submission, payload = it).statusCode).isEqualTo(HTTP_BAD_REQUEST)
        }
        assertThat(bindingModes()).isEmpty()
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
    fun `an identity with only the Edge username cannot bind a mode`() {
        assertThat(claim(submission, enforce = false).statusCode).isEqualTo(HTTP_FORBIDDEN)
        assertThat(bindingModes()).isEmpty()
    }

    private fun raceClaims(
        first: Submission,
        second: Submission,
        firstRegister: FakeMandateDirectory = register,
    ): List<Response> {
        val ready = CompletableDeferred<Unit>()
        val arrivals = AtomicInteger()
        mandateSeam.delegate = object : MandateDirectory by register {
            override suspend fun activeMandates(entityPartyId: UUID) = withTimeout(TimeUnit.SECONDS.toMillis(WAIT)) {
                val order = arrivals.incrementAndGet()
                if (order == 2) ready.complete(Unit)
                ready.await()
                val directory = if (order == 1) firstRegister else register
                directory.activeMandates(entityPartyId)
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        return try {
            listOf(first, second).map { request -> pool.submit<Response> { claim(request) } }
                .map { it.get(CALL, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            mandateSeam.delegate = register
        }
    }

    private fun assertMode(response: Response, mode: String) {
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(HTTP_OK)
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(response.jsonPath().getMap<String, String>("$")).containsOnlyKeys("mode").containsEntry("mode", mode)
    }

    private fun assertConflict(response: Response, code: String = "SUBMISSION_CORRELATION_REUSED") {
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(HTTP_CONFLICT)
        assertThat(response.jsonPath().getString("code")).isEqualTo(code)
        assertThat(response.body.asString()).doesNotContain(submission.digest, submission.correlation)
    }

    private fun claim(
        request: Submission,
        enforce: Boolean? = null,
        payload: Map<String, Any> = request.payload(),
    ): Response = post(
        request.entity,
        "/submissions/claim",
        buildMap {
            put("initiatorPartyId", request.human)
            put("submissionKeyDigest", request.digest)
            put("submissionCorrelation", request.correlation)
            put("payload", payload)
            enforce?.let { put("enforceApprovalPolicy", it) }
        },
    )

    private fun create(request: Submission, payload: Map<String, Any> = request.payload()): Response = post(
        request.entity,
        "",
        mapOf(
            "kind" to "PAYMENT",
            "initiatorSignature" to mapOf("partyId" to request.human, "scaChallengeId" to sca.initiator(request.human)),
            "submissionKeyDigest" to request.digest,
            "submissionCorrelation" to request.correlation,
            "payload" to payload,
        ),
    )

    private fun lookup(request: Submission, bound: Boolean = true): Response = post(
        request.entity,
        "/submissions/lookup",
        buildMap {
            put("initiatorPartyId", request.human)
            put("submissionCorrelation", request.correlation)
            if (bound) {
                put("submissionKeyDigest", request.digest)
                put("debtorAccountId", request.account)
            }
        },
    )

    private fun post(entity: UUID, path: String, body: Map<String, Any>) = RestAssured.given()
        .contentType(ContentType.JSON).body(body).post("/api/v1/entities/$entity/approval-requests$path")

    private fun claimRelease(id: UUID) = post(submission.entity, "/$id/domestic-release-claim", emptyMap())

    private fun approvals(): List<String> = RestAssured.given()
        .get("/api/v1/entities/${submission.entity}/approval-requests").then().statusCode(HTTP_OK)
        .extract().jsonPath().getList("data.id")

    private fun bindingModes(): List<String> = bindings().map { it.mode }

    private fun bindings(): List<StoredBinding> = jdbc().use { connection ->
        connection.prepareStatement(
            "select mode, claimed_at from approval_submission_mode_claims where submission_key_digest = ?",
        ).use { statement ->
            statement.setString(1, submission.digest)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(StoredBinding(rows.getString(1), rows.getObject(2, OffsetDateTime::class.java).toInstant()))
                    }
                }
            }
        }
    }

    private fun seedOldDirectClaim() = jdbc().use { connection ->
        connection.prepareStatement(
            """
            insert into approval_submission_mode_claims
              (submission_key_digest, authority_digest, entity_party_id, initiator_party_id,
               debit_account_id, payload_sha256, mode, claimed_at)
            values (?, ?, ?, ?, ?, ?, 'DIRECT', CURRENT_TIMESTAMP - interval '10 years')
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, submission.digest)
            statement.setString(2, submission.correlation)
            statement.setObject(3, submission.entity)
            statement.setObject(4, submission.human)
            statement.setObject(5, submission.account)
            statement.setString(6, newDigest())
            statement.executeUpdate()
        }
    }

    private fun jdbc() = DriverManager.getConnection(
        ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java),
        "openbank",
        "openbank_secret",
    )

    private data class StoredBinding(val mode: String, val claimedAt: Instant)

    private data class Submission(
        val entity: UUID,
        val human: UUID,
        val account: UUID,
        val digest: String = newDigest(),
        val correlation: String = newDigest(),
        val amount: String = "120000.00",
    ) {
        fun payload(
            railAccount: UUID = account,
            railAmount: String = amount,
            currency: String = "CZK",
            railCurrency: String = currency,
        ) = mapOf(
            "rail" to "DOMESTIC",
            "amount" to amount,
            "currency" to currency,
            "creditorIban" to CREDITOR,
            "debtorAccountId" to account.toString(),
            "railRequest" to mapOf(
                "debtorAccountId" to railAccount.toString(),
                "amount" to railAmount.toBigDecimal(),
                "currency" to railCurrency,
                "creditorAccount" to CREDITOR,
            ),
        )
    }

    private companion object {
        const val CREDITOR = "CZ6508000000192000145399"
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val WAIT = 10L
        const val CALL = 30L

        fun newDigest(): String = UUID.randomUUID().toString().replace("-", "").repeat(2)
    }
}
