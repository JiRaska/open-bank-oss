// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-0341 D1 / ADR-0352 phase 3 against the real database, driven through the real REST endpoint
 * (a reactive Panache write needs the Vert.x context only an HTTP request carries) and read back with
 * plain JDBC, so nothing here trusts the code under test to report its own writes.
 *
 * - a successful opening persists a pin equal to the published revision, and its AccountCreated
 *   outbox row — written by the SAME transaction (equal `xmin`) — carries that pin;
 * - an unreachable catalog and a product with no published revision are each refused, and leave no
 *   account row and no outbox row behind;
 * - the database itself refuses to change a pin once set (V33 trigger).
 *
 * Dispatch is off (shared profile with [AccountOutboxAtomicityIT]): the dispatcher UPDATEs claimed
 * rows, which would give the outbox row a new `xmin` and race the same-transaction assertion.
 */
@QuarkusTest
@TestProfile(AccountOutboxAtomicityIT.NoDispatchProfile::class)
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_accounts_it")],
)
class AccountProductRevisionPinIT {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var objectMapper: ObjectMapper

    private val productId: UUID = UUID.fromString("00000000-2222-0000-0000-000000000001")

    @AfterEach
    fun resetCatalog() {
        TestProductCatalogPort.mode = TestProductCatalogPort.Mode.PUBLISHED
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    fun `opening pins the published revision and the outbox row carries it from the same transaction`() {
        val partyId = UUID.randomUUID()
        val response = open(partyId)
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(201)
        val accountId = UUID.fromString(response.jsonPath().getString("id"))
        val expected = TestProductCatalogPort.revisionOf(productId)

        val pin = query(
            """
            SELECT product_revision_id, product_revision_number, product_offering_id, product_revision_product_code,
                   product_revision_content_hash, product_revision_ir_hash, product_revision_ir_version,
                   product_revision_pinned_at = opened_at AS pinned_at_opening, xmin::text
            FROM accounts WHERE id = ?
            """,
            accountId,
        ).single()
        assertThat(pin[0]).isEqualTo(expected.revisionId)
        assertThat(pin[1]).isEqualTo(expected.number)
        assertThat(pin[2]).isEqualTo(expected.offeringId)
        assertThat(pin[3]).isEqualTo("BEZNY_UCET")
        assertThat(pin[4]).isEqualTo(expected.contentHash)
        assertThat(pin[5]).isEqualTo(expected.irRevisionHash)
        assertThat(pin[6]).isEqualTo(expected.irVersion)
        assertThat(pin[7]).describedAs("pinnedAt is the opening instant").isEqualTo(true)

        val outbox = query(
            "SELECT event_type, payload, xmin::text FROM account_outbox WHERE aggregate_id = ?",
            accountId,
        )
        assertThat(outbox).describedAs("exactly one outbox row for the opening").hasSize(1)
        val (eventType, payload, outboxXmin) = outbox.single()
        assertThat(eventType).isEqualTo("AccountCreated")
        assertThat(outboxXmin)
            .describedAs("account row and its AccountCreated outbox row must be written by one transaction")
            .isEqualTo(pin[8])
        val wire = objectMapper.readTree(payload as String).path("productRevision")
        assertThat(wire.path("revisionId").asText()).isEqualTo(expected.revisionId.toString())
        assertThat(wire.path("revisionNumber").asLong()).isEqualTo(expected.number)
        assertThat(wire.path("irRevisionHash").asText()).isEqualTo(expected.irRevisionHash)

        // The REST read exposes the same pin.
        val read = RestAssured.given().get("/api/v1/accounts/$accountId")
        assertThat(read.jsonPath().getString("productRevision.revisionId")).isEqualTo(expected.revisionId.toString())
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    fun `an unreachable catalog refuses the opening with 503 and persists nothing`() {
        TestProductCatalogPort.mode = TestProductCatalogPort.Mode.CATALOG_UNREACHABLE
        val partyId = UUID.randomUUID()

        val response = open(partyId)

        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(503)
        assertThat(response.jsonPath().getString("code")).isEqualTo("PRODUCT_CATALOG_UNAVAILABLE")
        assertNothingPersistedFor(partyId)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    fun `a product with no published revision refuses the opening with 422 and persists nothing`() {
        TestProductCatalogPort.mode = TestProductCatalogPort.Mode.NO_PUBLISHED_REVISION
        val partyId = UUID.randomUUID()

        val response = open(partyId)

        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(422)
        assertThat(response.jsonPath().getString("code")).isEqualTo("PRODUCT_REVISION_NOT_PUBLISHED")
        assertNothingPersistedFor(partyId)
    }

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    fun `the database refuses to change a pin once it is set`() {
        val response = open(UUID.randomUUID())
        assertThat(response.statusCode).isEqualTo(201)
        val accountId = UUID.fromString(response.jsonPath().getString("id"))

        assertThatThrownBy {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "UPDATE accounts SET product_revision_number = product_revision_number + 1 WHERE id = ?",
                )
                    .use {
                        it.setObject(1, accountId)
                        it.executeUpdate()
                    }
            }
        }.isInstanceOf(SQLException::class.java).hasMessageContaining("immutable")

        // Control: an ordinary update on the same row still works, so the trigger is about the pin only.
        val renamed = dataSource.connection.use { c ->
            c.prepareStatement("UPDATE accounts SET nickname = 'pin-it' WHERE id = ?")
                .use {
                    it.setObject(1, accountId)
                    it.executeUpdate()
                }
        }
        assertThat(renamed).isEqualTo(1)
    }

    @Test
    fun `a legacy row accepts a whole pin but refuses a pin with a missing revision number`() {
        val accountId = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO accounts (id, account_number, account_type, party_id, product_id, currency_code)
                VALUES (?, ?, 'CURRENT', ?, ?, 'CZK')
                """.trimIndent(),
            ).use {
                it.setObject(1, accountId)
                it.setString(2, accountId.toString().replace("-", ""))
                it.setObject(3, UUID.randomUUID())
                it.setObject(4, productId)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
        }
        assertThat(query("SELECT product_revision_id FROM accounts WHERE id = ?", accountId).single()[0]).isNull()

        assertThatThrownBy { pinLegacyRow(accountId, null) }
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("chk_accounts_product_revision_pin_whole")
        assertThat(query("SELECT product_revision_id FROM accounts WHERE id = ?", accountId).single()[0]).isNull()

        // The nullable legacy state and a complete first pin remain supported; only the partial pin fails.
        assertThat(pinLegacyRow(accountId, 4L)).isEqualTo(1)
        assertThat(query("SELECT product_revision_number FROM accounts WHERE id = ?", accountId).single()[0])
            .isEqualTo(4L)
    }

    private fun pinLegacyRow(accountId: UUID, number: Long?): Int = dataSource.connection.use { c ->
        c.prepareStatement(
            """
            UPDATE accounts SET product_revision_id = ?, product_revision_number = ?, product_offering_id = ?,
                product_revision_product_code = 'BEZNY_UCET', product_revision_pinned_at = opened_at
            WHERE id = ?
            """.trimIndent(),
        ).use {
            it.setObject(1, UUID.randomUUID())
            it.setObject(2, number)
            it.setObject(3, UUID.randomUUID())
            it.setObject(4, accountId)
            it.executeUpdate()
        }
    }

    private fun assertNothingPersistedFor(partyId: UUID) {
        assertThat(query("SELECT id FROM accounts WHERE party_id = ?", partyId))
            .describedAs("no account row for a refused opening")
            .isEmpty()
        assertThat(
            query(
                "SELECT o.id FROM account_outbox o WHERE o.payload::jsonb ->> 'partyId' = ?",
                partyId.toString(),
            ),
        ).describedAs("no outbox row for a refused opening").isEmpty()
    }

    private fun open(partyId: UUID): Response = RestAssured.given()
        .contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .body(
            """
            {
              "partyId": "$partyId",
              "productId": "$productId",
              "accountType": "CURRENT",
              "currencyCode": "CZK",
              "legalName": "Pin IT s.r.o."
            }
            """.trimIndent(),
        )
        .post("/api/v1/accounts")

    private fun query(sql: String, vararg args: Any): List<List<Any?>> = dataSource.connection.use { c ->
        c.prepareStatement(sql.trimIndent()).use { statement ->
            args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }
            statement.executeQuery().use { rows ->
                val width = rows.metaData.columnCount
                generateSequence { if (rows.next()) (1..width).map { rows.getObject(it) } else null }.toList()
            }
        }
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"
    }
}
