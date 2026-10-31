// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/** Real HTTP and PostgreSQL coverage of the PROCESSING → SETTLED path (#12181). */
@QuarkusTest
@TestProfile(SctInstSettlementAtomicityIT.SchemeEnabledProfile::class)
@QuarkusTestResource(
    value = com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_sepa_instant_it")],
)
@Suppress("NestedBlockDepth") // JDBC xmin oracle and trigger cleanup need nested resource scopes.
class SctInstSettlementAtomicityIT {
    @Inject lateinit var dataSource: DataSource

    class SchemeEnabledProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.sct-inst.scheme-submission.enabled" to "true",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Test
    @TestSecurity(user = "operator-atomicity", roles = ["ROLE_OPERATOR"])
    fun `accepted scheme payment transitions from processing to settled`() {
        val key = UUID.randomUUID().toString()
        val paymentId = given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", key)
            .body(
                """{"idempotencyKey":"$key","debtorAccountId":"${UUID.randomUUID()}","debtorIban":"CZ6508000000192000145399","debtorName":"Test Debtor","creditorIban":"DE89370400440532013000","creditorName":"Test Creditor","creditorBic":"COBADEFFXXX","amount":99.99,"currency":"EUR","endToEndId":"E2E-$key"}""",
            )
            .`when`().post("/api/v1/sepa-instant")
            .then().statusCode(201).body("status", equalTo("SETTLED"))
            .extract().path<String>("paymentId")

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT xmin::text, status, settled_at IS NOT NULL FROM sct_inst_payments WHERE payment_id = ?::uuid",
            ).use { statement ->
                statement.setString(1, paymentId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    val transitionXmin = rows.getString(1)
                    assertThat(rows.getString(2)).isEqualTo("SETTLED")
                    assertThat(rows.getBoolean(3)).isTrue()
                    connection.prepareStatement(
                        "SELECT event_type, xmin::text, status, payload FROM sct_inst_outbox " +
                            "WHERE aggregate_id = ?::uuid ORDER BY created_at, id",
                    ).use { events ->
                        events.setString(1, paymentId)
                        events.executeQuery().use { outbox ->
                            assertThat(outbox.next()).isTrue()
                            assertThat(outbox.getString(1)).isEqualTo("SctInstPaymentSubmitted")
                            assertThat(outbox.getString(3)).isEqualTo("PENDING")
                            assertThat(outbox.next()).isTrue()
                            assertThat(outbox.getString(1)).isEqualTo("SctInstPaymentSettled")
                            assertThat(outbox.getString(2)).isEqualTo(transitionXmin)
                            assertThat(outbox.getString(3)).isEqualTo("PENDING")
                            assertThat(outbox.getString(4)).contains("\"paymentId\":\"$paymentId\"")
                            assertThat(outbox.next()).isFalse()
                        }
                    }
                }
            }
        }
    }

    @Test
    @TestSecurity(user = "operator-atomicity", roles = ["ROLE_OPERATOR"])
    fun `recall commits changed payment and event together without duplicate on retry`() {
        val key = UUID.randomUUID().toString()
        val paymentId = given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", key)
            .body(
                """{"idempotencyKey":"$key","debtorAccountId":"${UUID.randomUUID()}","debtorIban":"CZ6508000000192000145399","debtorName":"Test Debtor","creditorIban":"DE89370400440532013000","creditorName":"Test Creditor","creditorBic":"COBADEFFXXX","amount":99.99,"currency":"EUR","endToEndId":"E2E-$key"}""",
            )
            .`when`().post("/api/v1/sepa-instant")
            .then().statusCode(201).body("status", equalTo("SETTLED"))
            .extract().path<String>("paymentId")

        given()
            .contentType(ContentType.JSON)
            .body("""{"reason":"Customer requested"}""")
            .`when`().post("/api/v1/sepa-instant/$paymentId/recall")
            .then().statusCode(200).body("status", equalTo("RECALLED"))

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT p.xmin::text, o.xmin::text, p.recalled_at IS NOT NULL, p.recall_reason, " +
                    "o.status, o.payload FROM sct_inst_payments p JOIN sct_inst_outbox o " +
                    "ON o.aggregate_id = p.payment_id " +
                    "WHERE p.payment_id = ?::uuid AND o.event_type = 'SctInstPaymentRecalled'",
            ).use { statement ->
                statement.setString(1, paymentId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(2)).isEqualTo(rows.getString(1))
                    assertThat(rows.getBoolean(3)).isTrue()
                    assertThat(rows.getString(4)).isEqualTo("Customer requested")
                    assertThat(rows.getString(5)).isEqualTo("PENDING")
                    assertThat(rows.getString(6)).contains("\"paymentId\":\"$paymentId\"")
                    assertThat(rows.next()).isFalse()
                }
            }
        }

        given()
            .contentType(ContentType.JSON)
            .body("""{"reason":"Customer requested"}""")
            .`when`().post("/api/v1/sepa-instant/$paymentId/recall")
            .then().statusCode(400)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM sct_inst_outbox WHERE aggregate_id = ?::uuid " +
                    "AND event_type = 'SctInstPaymentRecalled'",
            ).use { statement ->
                statement.setString(1, paymentId)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getInt(1)).isEqualTo(1)
                }
            }
        }
    }

    @Test
    @TestSecurity(user = "operator-atomicity", roles = ["ROLE_OPERATOR"])
    fun `failed settled event insert rolls back the settled transition`() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """CREATE FUNCTION reject_settled_event() RETURNS trigger LANGUAGE plpgsql AS
                       $$ BEGIN IF NEW.event_type = 'SctInstPaymentSettled' THEN
                       RAISE EXCEPTION 'injected settled-event write failure'; END IF;
                       RETURN NEW; END $$""",
                )
                statement.execute(
                    "CREATE TRIGGER reject_settled_event BEFORE INSERT ON sct_inst_outbox " +
                        "FOR EACH ROW EXECUTE FUNCTION reject_settled_event()",
                )
            }
        }
        try {
            val key = UUID.randomUUID().toString()
            given()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", key)
                .body(
                    """{"idempotencyKey":"$key","debtorAccountId":"${UUID.randomUUID()}","debtorIban":"CZ6508000000192000145399","debtorName":"Test Debtor","creditorIban":"DE89370400440532013000","creditorName":"Test Creditor","creditorBic":"COBADEFFXXX","amount":99.99,"currency":"EUR","endToEndId":"E2E-$key"}""",
                )
                .`when`().post("/api/v1/sepa-instant")
                .then().statusCode(500)

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT status, settled_at FROM sct_inst_payments WHERE idempotency_key = ?",
                ).use { query ->
                    query.setString(1, key)
                    query.executeQuery().use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getString(1)).isEqualTo("PROCESSING")
                        assertThat(rows.getObject(2)).isNull()
                    }
                }
                connection.prepareStatement(
                    "SELECT event_type FROM sct_inst_outbox o JOIN sct_inst_payments p " +
                        "ON o.aggregate_id = p.payment_id WHERE p.idempotency_key = ? ORDER BY o.id",
                ).use { query ->
                    query.setString(1, key)
                    query.executeQuery().use { events ->
                        assertThat(events.next()).isTrue()
                        assertThat(events.getString(1)).isEqualTo("SctInstPaymentSubmitted")
                        assertThat(events.next()).isFalse()
                    }
                }
            }
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER reject_settled_event ON sct_inst_outbox")
                    statement.execute("DROP FUNCTION reject_settled_event()")
                }
            }
        }
    }
}
