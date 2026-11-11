// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** #12317 cross-cutover duplicate protection through the actual PostgreSQL write boundary. */
@QuarkusTest
@TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_OPERATOR", "ROLE_API"])
@OidcSecurity(
    claims = [
        Claim(key = "sub", value = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"),
        Claim(key = "azp", value = "openbank-edge"),
        Claim(key = "preferred_username", value = "service-account-openbank-edge"),
    ],
)
@QuarkusTestResource(DomesticPaymentOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_domestic_payment_it")],
)
class DomesticOriginalSubmissionKeyIT {
    @Inject
    lateinit var dataSource: DataSource

    private val originalKey = "fixture:Raw Case\\${UUID.randomUUID()}"
    private val approvalId = UUID.randomUUID().toString()
    private val debtorAccountId = UUID.randomUUID()
    private val originalDigest = digest(originalKey)

    @Test
    fun `a legacy direct payment blocks a held release under its original key`() {
        val accepted = post(originalKey)
        val directId = paymentId(accepted)
        assertConflict(post(approvalId, originalDigest), directId)
        assertSinglePayment(directId, originalKey, persistedDigest = null)
        assertReplay(post(originalKey), directId)
    }

    @Test
    fun `a held release blocks a later direct submit and replays only with its exact digest`() {
        val accepted = post(approvalId, originalDigest)
        val releasedId = paymentId(accepted)
        assertConflict(post(originalKey), releasedId)
        assertReplay(post(approvalId, originalDigest), releasedId)
        assertConflict(post(approvalId), releasedId)
        assertConflict(post(approvalId, digest("different-original-key")), releasedId)
        assertSinglePayment(releasedId, approvalId, originalDigest)
    }

    @Test
    fun `concurrent direct and held writes commit one payment and one atomic outbox`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val direct = executor.submit<Response> {
                check(start.await(CALL_SECONDS, TimeUnit.SECONDS))
                post(originalKey)
            }
            val held = executor.submit<Response> {
                check(start.await(CALL_SECONDS, TimeUnit.SECONDS))
                post(approvalId, originalDigest)
            }
            start.countDown()
            val responses = listOf(direct, held).map { it.get(CALL_SECONDS, TimeUnit.SECONDS) }
            assertThat(responses.map { it.statusCode }).containsExactlyInAnyOrder(HTTP_CREATED, HTTP_CONFLICT)
            val id = paymentId(responses.single { it.statusCode == HTTP_CREATED })
            assertConflict(responses.single { it.statusCode == HTTP_CONFLICT }, id)
            val directWon = responses.first().statusCode == HTTP_CREATED
            if (directWon) {
                assertSinglePayment(id, originalKey, persistedDigest = null)
            } else {
                assertSinglePayment(id, approvalId, originalDigest)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `older releases keep their replay semantics and malformed digest is refused`() {
        val id = paymentId(post(approvalId))
        assertReplay(post(approvalId), id)
        assertConflict(post(approvalId, originalDigest), id)
        assertThat(post(originalKey, "not-a-digest").statusCode).isEqualTo(HTTP_BAD_REQUEST)
        assertSinglePayment(id, approvalId, persistedDigest = null)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_OPERATOR", "ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"),
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `an API identity with a forged Edge username cannot attach the digest`() {
        assertThat(post(approvalId, originalDigest).statusCode).isEqualTo(HTTP_FORBIDDEN)
        assertThat(writes()).isEmpty()
    }

    @Test
    @TestSecurity(user = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90", roles = ["ROLE_PAYMENTS"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"),
        ],
    )
    fun `a staff initiator can still submit normally but cannot attach a release digest`() {
        assertThat(post(approvalId, originalDigest).statusCode).isEqualTo(HTTP_FORBIDDEN)
        val id = paymentId(post(originalKey))
        assertSinglePayment(id, originalKey, persistedDigest = null)
    }

    @Test
    fun `historical UTF-8 keys are hashed without trimming or backslash conversion`() {
        val raw = " Číslo \\ test "
        val expected = "1c8c650ece8e95c4b7f9ff187bca40c0c6ca3e20b45c1bfd673d2702ebcf6a67"
        dataSource.connection.use { connection ->
            connection.prepareStatement("select domestic_original_submission_digest(?)").use { statement ->
                statement.setString(1, raw)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo(expected)
                }
            }
        }
        assertThat(digest(raw)).isEqualTo(expected)
    }

    private fun post(key: String, originalKeyDigest: String? = null): Response {
        val request = RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", key)
        originalKeyDigest?.let { request.header(DIGEST_HEADER, it) }
        return request.body(
            mapOf(
                "debtorAccountId" to debtorAccountId,
                "debtorAccountNumber" to "1234567890",
                "debtorBankCode" to "0800",
                "debtorName" to "Alice Example",
                "creditorAccountNumber" to "9876543210",
                "creditorBankCode" to "0100",
                "creditorName" to "Brno Utility",
                "amount" to "100.00".toBigDecimal(),
                "currency" to "CZK",
                "priority" to "STANDARD",
            ),
        ).post("/api/v1/domestic-payments")
    }

    private fun paymentId(response: Response): String {
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(HTTP_CREATED)
        return response.jsonPath().getString("id")
    }

    private fun assertReplay(response: Response, id: String) {
        assertThat(paymentId(response)).isEqualTo(id)
        assertThat(response.header("X-Idempotency-Replayed")).isEqualTo("true")
    }

    private fun assertConflict(response: Response, id: String) {
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(HTTP_CONFLICT)
        assertThat(response.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(response.body.asString()).doesNotContain(id, originalKey, originalDigest)
    }

    private fun assertSinglePayment(id: String, key: String, persistedDigest: String?) {
        val row = writes().single()
        assertThat(row.id).isEqualTo(id)
        assertThat(row.railKey).isEqualTo(key)
        assertThat(row.digest).isEqualTo(persistedDigest)
        assertThat(row.outboxTransaction).isEqualTo(row.paymentTransaction)
        val event = checkNotNull(row.outboxPayload)
        assertThat(event).doesNotContain(originalDigest)
        assertThat(JsonPath(event).getString("idempotencyKey")).isEqualTo(key)
    }

    private fun writes(): List<StoredWrite> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT p.payment_id, p.idempotency_key, p.original_submission_key_digest,
                   p.xmin::text, o.xmin::text, o.payload
            FROM domestic_payments p LEFT JOIN domestic_payment_outbox o ON o.aggregate_id = p.payment_id
            WHERE p.idempotency_key IN (?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, originalKey)
            statement.setString(2, approvalId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            StoredWrite(
                                rows.getString(1), rows.getString(2), rows.getString(3),
                                rows.getString(4), rows.getString(5), rows.getString(6),
                            ),
                        )
                    }
                }
            }
        }
    }

    private data class StoredWrite(
        val id: String,
        val railKey: String,
        val digest: String?,
        val paymentTransaction: String,
        val outboxTransaction: String?,
        val outboxPayload: String?,
    )

    private companion object {
        const val DIGEST_HEADER = "X-OpenBank-Original-Submission-Key-Digest"
        const val HTTP_CREATED = 201
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_FORBIDDEN = 403
        const val HTTP_CONFLICT = 409
        const val CALL_SECONDS = 60L

        fun digest(rawKey: String): String = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest("openbank-domestic-approval-key-v1|$rawKey".toByteArray(Charsets.UTF_8)),
        )
    }
}
