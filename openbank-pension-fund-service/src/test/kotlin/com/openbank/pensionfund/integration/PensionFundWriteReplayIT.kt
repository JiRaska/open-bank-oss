// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.integration

import com.openbank.libs.idempotency.IdempotencyScope
import com.openbank.libs.util.Hex
import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestIdentityAssociation
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.Principal
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Real HTTP and JDBC prove a replay preserves the response and commits only one fund. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(PensionFundWriteReplayIT.ReplayProfile::class)
@TestSecurity(user = "replay-operator", roles = ["ROLE_OPERATOR"])
class PensionFundWriteReplayIT {
    @Inject
    lateinit var identities: TestIdentityAssociation

    class ReplayProfile : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(PrecisionCatalogTerms::class.java)
    }

    @Test
    fun `same key replays the original creation snapshot after the fund is amended`() {
        val key = UUID.randomUUID().toString()
        val body = definition()
        val first = post(body, key).then().statusCode(201).extract().response()
        val originalJson = first.asString()
        val id = first.jsonPath().getString("id")
        assertThat(fundCount(body.getValue("isin").toString())).isEqualTo(1)
        assertThat(receiptCount(key)).isEqualTo(1)

        given().contentType("application/json").body(body + ("name" to "Amended replay fixture"))
            .put("/api/v2/funds/$id").then().statusCode(200)
        assertThat(given().get("/api/v2/funds/$id").then().statusCode(200).extract().path<String>("name"))
            .isEqualTo("Amended replay fixture")

        val replay = post(body, key).then().statusCode(201)
            .header("X-Idempotency-Replayed", "true").extract().response()
        assertThat(replay.asString()).isEqualTo(originalJson)
        assertThat(fundCount(body.getValue("isin").toString())).isEqualTo(1)
        assertThat(receiptCount(key)).isEqualTo(1)
    }

    @Test
    fun `a key reused for a changed definition cannot create or mutate another fund`() {
        val key = UUID.randomUUID().toString()
        val body = definition()
        val first = post(body, key).then().statusCode(201).extract().asString()
        val changed = definition()
        post(changed, key).then().statusCode(409)
        assertThat(fundCount(body.getValue("isin").toString())).isEqualTo(1)
        assertThat(fundCount(changed.getValue("isin").toString())).isZero()
        assertThat(receiptCount(key)).isEqualTo(1)
        assertThat(post(body, key).then().statusCode(201).extract().asString()).isEqualTo(first)
    }

    @Test
    fun `missing and blank keys refuse creation without writing a fund or receipt`() {
        val body = definition()
        val receiptsBefore = allReceiptCount()
        post(body, null).then().statusCode(400)
        post(body, "").then().statusCode(400)
        post(body, "   ").then().statusCode(400)
        assertThat(fundCount(body.getValue("isin").toString())).isZero()
        assertThat(allReceiptCount()).isEqualTo(receiptsBefore)
    }

    @Test
    fun `failed catalog terms leave no receipt and the key can retry a valid creation`() {
        val key = UUID.randomUUID().toString()
        val body = definition()
        post(body + ("productRevisionId" to UUID.randomUUID().toString()), key).then().statusCode(400)
        assertThat(fundCount(body.getValue("isin").toString())).isZero()
        assertThat(receiptCount(key)).isZero()

        post(body, key).then().statusCode(201)
        assertThat(fundCount(body.getValue("isin").toString())).isEqualTo(1)
        assertThat(receiptCount(key)).isEqualTo(1)
    }

    @Test
    fun `receipt insertion failure rolls back the fund and the identical request can retry`() {
        val key = UUID.randomUUID().toString()
        val body = definition()
        val hash = receiptHash(key)
        // Identifiers contain only a fixed prefix and UUID hex; the trigger rejects only this test's key.
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val function = "test_write_receipt_failure_$suffix"
        val trigger = "test_write_receipt_failure_$suffix"
        connection().use { db ->
            try {
                db.createStatement().use { statement ->
                    statement.execute(
                        "CREATE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS " +
                            "$$ BEGIN IF NEW.key_hash = '$hash' THEN " +
                            "RAISE EXCEPTION 'synthetic receipt insertion failure'; END IF; RETURN NEW; END; $$",
                    )
                    statement.execute(
                        "CREATE TRIGGER $trigger BEFORE INSERT ON pension_fund_write_replay " +
                            "FOR EACH ROW EXECUTE FUNCTION $function()",
                    )
                }
                post(body, key).then().statusCode(500)
                assertThat(fundCount(body.getValue("isin").toString())).isZero()
                assertThat(receiptCount(key)).isZero()
            } finally {
                db.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER IF EXISTS $trigger ON pension_fund_write_replay")
                    statement.execute("DROP FUNCTION IF EXISTS $function()")
                }
            }
        }

        post(body, key).then().statusCode(201)
        assertThat(fundCount(body.getValue("isin").toString())).isEqualTo(1)
        assertThat(receiptCount(key)).isEqualTo(1)
    }

    @Test
    fun `an in progress key refuses a write until its transaction lock is released`() {
        val key = UUID.randomUUID().toString()
        val body = definition()
        val scoped = IdempotencyScope("pension-fund-service", "replay-operator").storeKey(key)
        val digest = MessageDigest.getInstance("SHA-256").digest(scoped.toByteArray(Charsets.UTF_8))
        val lockKey = ByteBuffer.wrap(digest).long
        connection().use { db ->
            db.autoCommit = false
            try {
                holdTransactionLock(db, lockKey)
                post(body, key).then().statusCode(409)
                assertThat(fundCount(body.getValue("isin").toString())).isZero()
                assertThat(receiptCount(key)).isZero()
            } finally {
                db.rollback()
            }
        }

        post(body, key).then().statusCode(201)
        assertThat(fundCount(body.getValue("isin").toString())).isEqualTo(1)
        assertThat(receiptCount(key)).isEqualTo(1)
    }

    @Test
    fun `the same caller key belongs independently to each authenticated principal`() {
        val key = UUID.randomUUID().toString()
        val first = definition()
        val second = definition()
        post(first, key).then().statusCode(201)
        try {
            setIdentity("other-replay-operator")
            post(second, key).then().statusCode(201)
            assertThat(receiptCount(key, "other-replay-operator")).isEqualTo(1)
            assertThat(receiptCount(key)).isEqualTo(1)
            assertThat(fundCount(first.getValue("isin").toString())).isEqualTo(1)
            assertThat(fundCount(second.getValue("isin").toString())).isEqualTo(1)
        } finally {
            setIdentity("replay-operator")
        }
    }

    private fun holdTransactionLock(db: Connection, lockKey: Long) {
        db.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
            statement.setLong(1, lockKey)
            statement.executeQuery().use { rows -> check(rows.next()) }
        }
    }

    private fun setIdentity(name: String) {
        identities.setTestIdentity(
            QuarkusSecurityIdentity.builder().setPrincipal(Principal { name }).addRole("ROLE_OPERATOR").build(),
        )
    }

    private fun definition(): Map<String, Any> = mapOf(
        "name" to "Write replay fixture",
        "isin" to ("PF" + UUID.randomUUID().toString().replace("-", "").take(9).uppercase() + "0"),
        "lei" to "315700ABCDEF12345678",
        "depositaryReference" to "TEST-DEP",
        "custodyAccountReference" to "TEST-CUST",
        "currency" to "CZK",
        "riskClass" to 3,
        "productRevisionId" to PrecisionCatalogTerms.REVISION.toString(),
    )

    private fun post(body: Map<String, Any>, key: String?): Response {
        val request = given().contentType("application/json").body(body)
        if (key != null) request.header("Idempotency-Key", key)
        return request.post("/api/v2/funds")
    }

    private fun fundCount(isin: String): Long = count("SELECT count(*) FROM funds WHERE isin = ?", isin)

    private fun receiptCount(key: String, principal: String = "replay-operator"): Long =
        count("SELECT count(*) FROM pension_fund_write_replay WHERE key_hash = ?", receiptHash(key, principal))

    private fun receiptHash(key: String, principal: String = "replay-operator"): String {
        val scoped = IdempotencyScope("pension-fund-service", principal).storeKey(key)
        return Hex.lower(MessageDigest.getInstance("SHA-256").digest(scoped.toByteArray(Charsets.UTF_8)))
    }

    private fun allReceiptCount(): Long = count("SELECT count(*) FROM pension_fund_write_replay")

    private fun count(sql: String, value: String? = null): Long = connection().use { db ->
        db.prepareStatement(sql).use { statement ->
            if (value != null) statement.setString(1, value)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private fun connection(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }
}
