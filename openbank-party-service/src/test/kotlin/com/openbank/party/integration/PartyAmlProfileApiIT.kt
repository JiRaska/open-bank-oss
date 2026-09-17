// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.party.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID
import javax.sql.DataSource

/**
 * The personal AML profile over real HTTP and a real database. Rows are read back over plain JDBC:
 * the claims are "a new version row exists and the old one is kept", "the parties columns moved"
 * and "PARTY_UPDATED went to the outbox in that transaction" — none of which a mocked repository
 * can observe. The dispatcher is off so the outbox row stays PENDING for the assertion.
 */
@QuarkusTest
@QuarkusTestResource(PartyAmlProfileApiIT.DispatcherOffResource::class)
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PartyAmlProfileApiIT {

    class DispatcherOffResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = mapOf("openbank.outbox.dispatch-enabled" to "false")
        override fun stop() = Unit
    }

    companion object {
        const val EDGE = "service-account-openbank-edge"
        private var personId: UUID? = null

        val CZ_ONLY = """
            {"purpose":["EVERYDAY_BANKING","SALARY"],"incomeSources":["EMPLOYMENT"],
             "occupation":"EMPLOYED","expectedMonthlyTurnover":"UP_TO_30K","cashIntensive":false,
             "pep":{"isPep":false},"taxResidencies":["CZ"],"usPerson":false,"truthful":true}
        """.trimIndent()

        val FOREIGN_PEP = """
            {"purpose":["SAVINGS"],"incomeSources":["OTHER"],"incomeNote":"royalties",
             "occupation":"SELF_EMPLOYED","expectedMonthlyTurnover":"OVER_500K","cashIntensive":false,
             "pep":{"isPep":true,"category":"MP","detail":"member of parliament"},
             "taxResidencies":["CZ","DE"],"tin":{"DE":"DE-123"},"usPerson":false,"truthful":true}
        """.trimIndent()
    }

    @Inject
    lateinit var dataSource: DataSource

    private fun createParty(type: String): UUID {
        val extra = if (type == "COMPANY") {
            """"registrationNumber":"4527${(1000..9999).random()}","registrationCountry":"CZ","legalForm":"112","""
        } else {
            ""
        }
        val body = """
            {"partyType":"$type","legalName":"AML Probe","tradingName":null,"dateOfBirth":"1990-01-01",
             "nationality":"CZ","taxId":null,$extra
             "email":"aml-${UUID.randomUUID()}@example.cz","phone":null,"address":null}
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

    private fun put(id: UUID, body: String, status: Int) = Given {
        contentType("application/json")
        body(body)
    } When {
        put("/api/v1/parties/$id/aml-profile")
    } Then {
        statusCode(status)
    }

    private fun <T> query(sql: String, id: UUID, read: (java.sql.ResultSet) -> T): List<T> =
        dataSource.connection.use { conn ->
            val ps = conn.prepareStatement(sql)
            ps.setObject(1, id)
            val rs = ps.executeQuery()
            val out = mutableListOf<T>()
            while (rs.next()) out += read(rs)
            out
        }

    @Test
    @Order(1)
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    fun `a person with no declaration answers 404, and the party GET reports every derived fact as unknown`() {
        val id = createParty("INDIVIDUAL")
        personId = id
        Given { this } When { get("/api/v1/parties/$id/aml-profile") } Then { statusCode(404) }
        Given { this } When { get("/api/v1/parties/$id") } Then {
            statusCode(200)
            // The column defaults to false; the API must still say "unknown", or a reader such as
            // kyb takes an undeclared person for a known non-PEP and skips the PEP question.
            body("pepFlag", equalTo(null))
            body("pepCategory", equalTo(null))
            body("fatcaStatus", equalTo(null))
            body("crsStatus", equalTo(null))
        }
    }

    @Test
    @Order(2)
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    fun `the edge declares a CZ-only profile - version 1, derived columns written, PARTY_UPDATED in the outbox`() {
        val id = requireNotNull(personId)
        put(id, CZ_ONLY, 200).body("version", equalTo(1))
            .body("crsStatus", equalTo("NON_REPORTABLE"))
            .body("fatcaStatus", equalTo("NON_US"))
            .body("eddRequired", equalTo(false))
            .body("reviewStatus", equalTo("STANDARD"))

        val party = query(
            "SELECT pep_flag, pep_category, fatca_status, crs_status FROM parties WHERE party_id = ?",
            id,
        ) {
            listOf(it.getBoolean(1), it.getString(2), it.getString(3), it.getString(4))
        }.single()
        assertThat(party).containsExactly(false, null, "NON_US", "NON_REPORTABLE")

        // Declared "not a PEP": now, and only now, false is a known fact.
        Given { this } When { get("/api/v1/parties/$id") } Then {
            statusCode(200)
            body("pepFlag", equalTo(false))
            body("fatcaStatus", equalTo("NON_US"))
            body("crsStatus", equalTo("NON_REPORTABLE"))
        }

        val events =
            query("SELECT payload FROM party_outbox WHERE aggregate_id = ? AND event_type = 'PARTY_UPDATED'", id) {
                it.getString(1)
            }
        assertThat(events).hasSize(1)
        assertThat(events.single()).contains("\"changeKind\":\"AML_PROFILE_DECLARED\"", "\"amlRiskFactors\":[]")
    }

    @Test
    @Order(3)
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    fun `a re-declaration becomes version 2, keeps version 1 unchanged, and moves the derived columns`() {
        val id = requireNotNull(personId)
        put(id, FOREIGN_PEP, 200).body("version", equalTo(2))
            .body("riskFactors", equalTo(listOf("PEP", "HIGH_TURNOVER")))
            .body("reviewStatus", equalTo("ENHANCED_DUE_DILIGENCE"))
            .body("tin.DE", equalTo("DE-123"))

        val rows = query(
            "SELECT version, is_current, purposes, is_pep FROM party_aml_profiles WHERE party_id = ? ORDER BY version",
            id,
        ) { listOf(it.getInt(1), it.getBoolean(2), it.getString(3), it.getBoolean(4)) }
        assertThat(rows).containsExactly(
            listOf(1, false, "EVERYDAY_BANKING,SALARY", false),
            listOf(2, true, "SAVINGS", true),
        )
        val party = query(
            "SELECT pep_flag, pep_category, fatca_status, crs_status FROM parties WHERE party_id = ?",
            id,
        ) {
            listOf(it.getBoolean(1), it.getString(2), it.getString(3), it.getString(4))
        }.single()
        assertThat(party).containsExactly(true, "MP", "NON_US", "REPORTABLE")

        val latest = query(
            "SELECT payload FROM party_outbox WHERE aggregate_id = ? AND event_type = 'PARTY_UPDATED' ORDER BY created_at",
            id,
        ) { it.getString(1) }.last()
        assertThat(latest).contains("\"amlRiskFactors\":[\"PEP\",\"HIGH_TURNOVER\"]", "\"eddRequired\":true")
        assertThat(latest).doesNotContain("DE-123", "royalties")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "staff-reader", roles = ["ROLE_VIEWER"])
    fun `staff read the current version and the party GET exposes the derived facts`() {
        val id = requireNotNull(personId)
        Given { this } When { get("/api/v1/parties/$id/aml-profile") } Then {
            statusCode(200)
            body("version", equalTo(2))
            body("pep.isPep", equalTo(true))
            body("pep.category", equalTo("MP"))
            body("taxResidencies", equalTo(listOf("CZ", "DE")))
        }
        Given { this } When { get("/api/v1/parties/$id") } Then {
            statusCode(200)
            body("pepFlag", equalTo(true))
            body("pepCategory", equalTo("MP"))
            body("crsStatus", equalTo("REPORTABLE"))
        }
    }

    @Test
    @Order(5)
    @TestSecurity(user = "staff-operator", roles = ["ROLE_OPERATOR"])
    fun `a staff session holding ROLE_OPERATOR cannot declare on the customer's behalf`() {
        val id = requireNotNull(personId)
        put(id, CZ_ONLY, 403)
        assertThat(query("SELECT version FROM party_aml_profiles WHERE party_id = ?", id) { it.getInt(1) })
            .containsExactlyInAnyOrder(1, 2)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "staff-reader", roles = ["ROLE_VIEWER"])
    fun `a viewer cannot declare`() {
        put(requireNotNull(personId), CZ_ONLY, 403)
    }

    @Test
    @Order(6)
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    fun `invalid declarations answer 400 and store nothing`() {
        val id = requireNotNull(personId)
        listOf(
            CZ_ONLY.replace("\"truthful\":true", "\"truthful\":false"),
            CZ_ONLY.replace("\"truthful\":true", "\"truthful\":null"),
            CZ_ONLY.replace("\"taxResidencies\":[\"CZ\"]", "\"taxResidencies\":[]"),
            CZ_ONLY.replace("\"taxResidencies\":[\"CZ\"]", "\"taxResidencies\":[\"US\"]"),
            CZ_ONLY.replace("\"taxResidencies\":[\"CZ\"]", "\"taxResidencies\":[\"CZ\"],\"tin\":{\"CZ\":\"1\"}"),
            CZ_ONLY.replace("\"taxResidencies\":[\"CZ\"]", "\"taxResidencies\":[\"CZ\"],\"tin\":{\"SK\":\"1\"}"),
            CZ_ONLY.replace("\"usPerson\":false,", ""),
            CZ_ONLY.replace("\"pep\":{\"isPep\":false}", "\"pep\":{}"),
            CZ_ONLY.replace("\"occupation\":\"EMPLOYED\"", "\"occupation\":\"ASTRONAUT\""),
            CZ_ONLY.replace("\"pep\":{\"isPep\":false}", "\"pep\":{\"isPep\":true}"),
            CZ_ONLY.replace("\"purpose\":[\"EVERYDAY_BANKING\",\"SALARY\"],", ""),
        ).forEach { put(id, it, 400) }
        put(id, "", 400)
        assertThat(query("SELECT version FROM party_aml_profiles WHERE party_id = ?", id) { it.getInt(1) })
            .containsExactlyInAnyOrder(1, 2)
    }

    @Test
    @Order(7)
    @TestSecurity(user = EDGE, roles = ["ROLE_OPERATOR"])
    fun `an unknown party is 404 and a company is 422`() {
        val stranger = UUID.randomUUID()
        put(stranger, CZ_ONLY, 404)
        Given { this } When { get("/api/v1/parties/$stranger/aml-profile") } Then { statusCode(404) }
        put(createParty("COMPANY"), CZ_ONLY, 422)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "staff-reader", roles = ["ROLE_VIEWER", "ROLE_ADMIN", "ROLE_OPERATOR"])
    fun `a PEP flag set by a screening before any declaration reads true, the rest stays unknown`() {
        val id = createParty("INDIVIDUAL")
        dataSource.connection.use { conn ->
            val ps = conn.prepareStatement("UPDATE parties SET pep_flag = TRUE WHERE party_id = ?")
            ps.setObject(1, id)
            assertThat(ps.executeUpdate()).isEqualTo(1)
        }
        Given { this } When { get("/api/v1/parties/$id") } Then {
            statusCode(200)
            body("pepFlag", equalTo(true))
            body("fatcaStatus", equalTo(null))
            body("crsStatus", equalTo(null))
        }
        Given { this } When { get("/api/v1/parties/$id/aml-profile") } Then { statusCode(404) }
    }

    @Test
    @Order(8)
    @TestSecurity(user = "m2m", roles = ["ROLE_API"])
    fun `a ROLE_API caller may declare`() {
        put(requireNotNull(personId), CZ_ONLY, 200).body("version", equalTo(3))
    }
}
