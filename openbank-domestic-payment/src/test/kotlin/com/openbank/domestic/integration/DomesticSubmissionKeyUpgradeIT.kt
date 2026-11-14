// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentCommand
import com.openbank.domestic.application.usecase.DomesticPaymentRequestFingerprint
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.libs.domain.money.Money
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource

/** The running rail starts with a populated V20 database and applies V21 through its usual Flyway startup. */
@QuarkusTest
@TestProfile(DomesticSubmissionKeyUpgradeIT.UpgradeProfile::class)
class DomesticSubmissionKeyUpgradeIT {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_OPERATOR", "ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = ACTOR_ID),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `a V20 direct payment still fences its original key after V21 without changing the old outbox`() {
        dataSource.connection.use { connection ->
            // The newest migration is the hold facts (V22); V21, the fence under test, ran before it.
            assertThat(installedVersion(connection)).isEqualTo("22")
            assertHistoricalWrite(connection)
        }

        val originalDigest = digest("openbank-domestic-approval-key-v1|$ORIGINAL_KEY")
        val release = RestAssured.given().contentType(ContentType.JSON)
            .header("Idempotency-Key", APPROVAL_ID)
            .header("X-OpenBank-Original-Submission-Key-Digest", originalDigest)
            .body(railRequest)
            .post("/api/v1/domestic-payments/approval-releases")
        assertThat(release.statusCode).isEqualTo(HTTP_CONFLICT)
        assertThat(release.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(release.body.asString()).doesNotContain(PAYMENT_ID, ORIGINAL_KEY)

        RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", ORIGINAL_KEY)
            .body(mapOf("debtorAccountId" to ACCOUNT_ID, "customerSubmissionScope" to customerScope))
            .post("/api/v1/domestic-payments/submissions/lookup")
            .then().statusCode(HTTP_OK).extract().jsonPath().also {
                assertThat(it.getString("outcome")).isEqualTo("FOUND")
                assertThat(it.getString("paymentId")).isEqualTo(PAYMENT_ID)
                assertThat(it.getString("status")).isEqualTo("RECEIVED")
            }
        dataSource.connection.use(::assertHistoricalWrite)
    }

    class UpgradeProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(PopulatedV20::class.java),
            QuarkusTestProfile.TestResourceEntry(
                DomesticPaymentOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class.java,
            ),
        )
    }

    class PopulatedV20 : QuarkusTestResourceLifecycleManager {
        private val infrastructure = PostgresRedisTestResource().apply {
            init(mapOf("db" to "openbank_domestic_upgrade_it"))
        }

        override fun start(): Map<String, String> {
            val configuration = infrastructure.start()
            return runCatching {
                val url = configuration.getValue("quarkus.datasource.jdbc.url")
                val username = configuration.getValue("quarkus.datasource.username")
                val password = configuration.getValue("quarkus.datasource.password")
                Flyway.configure(javaClass.classLoader)
                    .dataSource(url, username, password)
                    .locations("classpath:db/migration")
                    .target("20")
                    .load()
                    .migrate()
                DriverManager.getConnection(url, username, password).use { connection ->
                    check(installedVersion(connection) == "20")
                    connection.autoCommit = false
                    seedV20Payment(connection)
                    seedV20Outbox(connection)
                    connection.commit()
                }
                configuration + ("quarkus.scheduler.enabled" to "false")
            }.onFailure { infrastructure.stop() }.getOrThrow()
        }

        override fun stop() = infrastructure.stop()
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CONFLICT = 409
        const val PAYMENT_ID = "2f8187ca-f29e-41da-98b4-80210a1e5275"
        const val APPROVAL_ID = "59b01083-1b15-4eca-9a7e-17c87a6cdc16"
        const val ACCOUNT_ID = "a8f6f638-8080-492e-a752-4bd4a2a2897f"
        const val ACTOR_ID = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"
        const val OUTBOX_ID = "8c555c68-68d5-4fca-88f8-5bec0a22d5c0"
        const val ORIGINAL_KEY = "fixture:V20-direct-key"
        const val DEBTOR_NUMBER = "1234567890"
        const val DEBTOR_BANK = "0800"
        const val DEBTOR_NAME = "Alice Example"
        const val CREDITOR_NUMBER = "9876543210"
        const val CREDITOR_BANK = "0100"
        const val CREDITOR_NAME = "Brno Utility"
        const val END_TO_END = "V20-DIRECT-UPGRADE-IT"
        val postedAt: OffsetDateTime = OffsetDateTime.parse("2026-01-15T10:00:00Z")
        val amount = "125.00".toBigDecimal()
        val customerScope = digest("synthetic-original-customer-scope")
        val railRequest = mapOf(
            "debtorAccountId" to ACCOUNT_ID,
            "debtorAccountNumber" to DEBTOR_NUMBER,
            "debtorBankCode" to DEBTOR_BANK,
            "debtorName" to DEBTOR_NAME,
            "creditorAccountNumber" to CREDITOR_NUMBER,
            "creditorBankCode" to CREDITOR_BANK,
            "creditorName" to CREDITOR_NAME,
            "amount" to amount,
            "currency" to "CZK",
            "priority" to "STANDARD",
            "customerSubmissionScope" to customerScope,
        )
        val fingerprint = DomesticPaymentRequestFingerprint.sha256(
            CreateDomesticPaymentCommand(
                idempotencyKey = ORIGINAL_KEY,
                debtorAccountId = UUID.fromString(ACCOUNT_ID),
                debtorAccountNumber = DEBTOR_NUMBER,
                debtorBankCode = DEBTOR_BANK,
                debtorName = DEBTOR_NAME,
                creditorAccountNumber = CREDITOR_NUMBER,
                creditorBankCode = CREDITOR_BANK,
                creditorName = CREDITOR_NAME,
                amount = Money.of(amount, "CZK"),
                variableSymbol = null,
                specificSymbol = null,
                constantSymbol = null,
                messageForPayee = null,
                priority = DomesticPaymentPriority.STANDARD,
                statementLabel = null,
                endToEndId = END_TO_END,
                actorId = UUID.fromString(ACTOR_ID),
                actorScope = ACTOR_ID,
                customerSubmissionScope = customerScope,
            ),
        )
        val outboxPayload = """
            {"paymentId":"$PAYMENT_ID","idempotencyKey":"$ORIGINAL_KEY","status":"RECEIVED",
            "debtorAccountId":"$ACCOUNT_ID","debtorAccountNumber":"$DEBTOR_NUMBER","debtorBankCode":"$DEBTOR_BANK",
            "creditorAccountNumber":"$CREDITOR_NUMBER","creditorBankCode":"$CREDITOR_BANK","amount":$amount,
            "currency":"CZK","priority":"STANDARD","endToEndId":"$END_TO_END","occurredAt":"${postedAt.toInstant()}",
            "aggregateRevision":1,"initiatedByPartyId":"$ACTOR_ID","eventType":"DOMESTIC_PAYMENT_CREATED",
            "sourceService":"domestic-payment"}
        """.trimIndent()

        private fun digest(value: String): String = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
        )

        private fun installedVersion(connection: Connection): String = connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
            ).use { rows ->
                check(rows.next()) { "No successful Flyway migration recorded" }
                rows.getString(1)
            }
        }

        private fun seedV20Payment(connection: Connection) = connection.prepareStatement(
            """
            INSERT INTO domestic_payments (
                id, payment_id, idempotency_key, status, debtor_account_id, debtor_account_number,
                debtor_bank_code, debtor_name, creditor_account_number, creditor_bank_code, creditor_name,
                amount, currency, priority, transfer_scope, end_to_end_id, initiated_by_party_id,
                request_fingerprint, customer_submission_scope, created_at, updated_at
            ) VALUES (nextval('domestic_payments_seq'), ?, ?, 'RECEIVED', ?, ?, ?, ?, ?, ?, ?, ?, 'CZK',
                'STANDARD', 'EXTERNAL', ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.fromString(PAYMENT_ID))
            statement.setString(2, ORIGINAL_KEY)
            statement.setObject(3, UUID.fromString(ACCOUNT_ID))
            statement.setString(4, DEBTOR_NUMBER)
            statement.setString(5, DEBTOR_BANK)
            statement.setString(6, DEBTOR_NAME)
            statement.setString(7, CREDITOR_NUMBER)
            statement.setString(8, CREDITOR_BANK)
            statement.setString(9, CREDITOR_NAME)
            statement.setBigDecimal(10, amount)
            statement.setString(11, END_TO_END)
            statement.setObject(12, UUID.fromString(ACTOR_ID))
            statement.setString(13, fingerprint)
            statement.setString(14, customerScope)
            statement.setObject(15, postedAt)
            statement.setObject(16, postedAt)
            check(statement.executeUpdate() == 1)
        }

        private fun seedV20Outbox(connection: Connection) = connection.prepareStatement(
            """
            INSERT INTO domestic_payment_outbox (
                id, event_id, aggregate_id, event_type, payload, status, synthetic, created_at, updated_at
            ) VALUES (nextval('domestic_payment_outbox_seq'), ?, ?, 'domestic.payment.created', ?, 'PENDING',
                false, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.fromString(OUTBOX_ID))
            statement.setObject(2, UUID.fromString(PAYMENT_ID))
            statement.setString(3, outboxPayload)
            statement.setObject(4, postedAt)
            statement.setObject(5, postedAt)
            check(statement.executeUpdate() == 1)
        }

        private fun assertHistoricalWrite(connection: Connection) = connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT p.payment_id, p.idempotency_key, p.amount, p.status, p.request_fingerprint,
                       p.original_submission_key_digest, p.created_at, p.xmin::text AS payment_xmin,
                       o.event_id, o.status AS outbox_status, o.payload, o.xmin::text AS outbox_xmin,
                       (SELECT count(*) FROM domestic_payments) AS payments,
                       (SELECT count(*) FROM domestic_payment_outbox) AS outbox_events
                FROM domestic_payments p JOIN domestic_payment_outbox o ON o.aggregate_id = p.payment_id
                """.trimIndent(),
            ).use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(rows.getString("payment_id")).isEqualTo(PAYMENT_ID)
                assertThat(rows.getString("idempotency_key")).isEqualTo(ORIGINAL_KEY)
                assertThat(rows.getBigDecimal("amount")).isEqualByComparingTo(amount)
                assertThat(rows.getString("status")).isEqualTo("RECEIVED")
                assertThat(rows.getString("request_fingerprint")).isEqualTo(fingerprint)
                assertThat(rows.getString("original_submission_key_digest")).isNull()
                assertThat(rows.getObject("created_at", OffsetDateTime::class.java)).isEqualTo(postedAt)
                assertThat(rows.getString("event_id")).isEqualTo(OUTBOX_ID)
                assertThat(rows.getString("outbox_status")).isEqualTo("PENDING")
                assertThat(rows.getString("payload")).isEqualTo(outboxPayload)
                assertThat(rows.getString("payment_xmin")).isEqualTo(rows.getString("outbox_xmin"))
                assertThat(rows.getInt("payments")).isEqualTo(1)
                assertThat(rows.getInt("outbox_events")).isEqualTo(1)
                assertThat(rows.next()).isFalse()
            }
        }
    }
}
