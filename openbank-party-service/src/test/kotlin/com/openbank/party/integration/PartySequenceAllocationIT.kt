// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.party.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * #5913. `PUT /api/v1/parties/{id}/payees` answered 500 on every call for the life of the
 * endpoint, and so did the marketing-consent projection's first write:
 *
 *   SQLGrammarException: relation "party_payees_seq" does not exist (42P01)
 *     [select nextval('party_payees_SEQ')]
 *
 * V16 and V18 wrote `CREATE SEQUENCE "party_payees_SEQ"` — quoted, so Postgres keeps the case.
 * Hibernate emits `nextval('party_payees_SEQ')`, where the identifier inside the literal is
 * UNQUOTED and Postgres folds it to lower case, so it looks for `party_payees_seq`. V6 had
 * already fixed exactly this for parties/party_documents/party_outbox; V16 and V18 reintroduced
 * it. V19 is the fix.
 *
 * Why this test is an IT and not a unit test: `PartyServicePayeeTest` mocks the repository, so it
 * passes against a database that cannot allocate an id at all — the mock never touches a
 * sequence. The defect lives strictly between Hibernate and Postgres, so only a real request
 * against a real schema can see it. That is the same reason the consent `SuppressionEntity`
 * column-name defect and the sca `eventType` payload defect both shipped green.
 *
 * The assertion is deliberately the ALLOCATED ID, not merely a 200: a 200 would also be
 * satisfied by an endpoint that stopped persisting, and the claim here is specifically that the
 * insert reached the table with an id drawn from the sequence Hibernate asks for.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class PartySequenceAllocationIT {

    @Inject
    lateinit var dataSource: DataSource

    private fun createParty(email: String): UUID {
        val body = """
            {"partyType":"INDIVIDUAL","legalName":"Sequence Probe","tradingName":null,
             "dateOfBirth":"1990-01-01","nationality":"CZ","taxId":null,"registrationNumber":null,
             "email":"$email","phone":null,"address":null}
        """.trimIndent()
        val id = Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body(body)
        } When {
            post("/api/v1/parties")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }
        return UUID.fromString(id)
    }

    private fun payeeRowCount(partyId: UUID): Int = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement("SELECT count(*) FROM party_payees WHERE party_id = ?")
        ps.setObject(1, partyId)
        val rs = ps.executeQuery()
        if (rs.next()) rs.getInt(1) else 0
    }

    private fun nextval(sequence: String): Long = dataSource.connection.use { conn ->
        val rs = conn.createStatement().executeQuery("SELECT nextval('$sequence')")
        rs.next()
        rs.getLong(1)
    }

    @Test
    @TestSecurity(user = "sequence-it", roles = ["ROLE_ADMIN", "ROLE_OPERATOR"])
    fun `saving a payee allocates an id and writes the row`() {
        val partyId = createParty("payee-seq-${UUID.randomUUID()}@example.cz")

        val payeeId = Given {
            contentType("application/json")
            body("""{"name":"Sequence Probe Payee","iban":"CZ6508000000192000145399","bic":null}""")
        } When {
            put("/api/v1/parties/$partyId/payees")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getString("id")
        }

        assertThat(payeeId).describedAs("payee id returned by the endpoint").isNotBlank()
        assertThat(payeeRowCount(partyId)).describedAs("party_payees rows for %s", partyId).isEqualTo(1)
    }

    /**
     * The projection consumer's sequence is the same defect, found by grepping the migrations
     * rather than by a request — nothing in the fuzz lane reaches it. Asserting the sequence
     * directly keeps the claim honest: this test is about id allocation being possible, and does
     * not pretend to exercise the consumer.
     *
     * `nextval` with the identifier spelled exactly as Hibernate spells it is the whole point —
     * an assertion against `party_marketing_consent_seq` in lower case would pass against the
     * broken quoted sequence too, and prove nothing.
     */
    @Test
    fun `both Hibernate-managed sequences resolve under the name Hibernate uses`() {
        assertThat(nextval("party_payees_SEQ")).isPositive()
        assertThat(nextval("party_marketing_consent_SEQ")).isPositive()
    }
}
