// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * The ADR-0211 explicit origination commands over real HTTP and a real database:
 *
 *  - withdraw: desk leg, pre-disbursement; the reason minimum length is enforced, the stored row
 *    and the outbox evidence agree, a second withdrawal of the terminal row is refused;
 *  - submitDocuments: refused outside DOCS_REQUIRED (the default pack skips that state, so the
 *    happy path is a pack-mandated case — unit-covered in OriginationCommandsTest);
 *  - sign: drives AWAITING_SIGNATURE → SIGNED, persists the artefact hash in the SAME claim as
 *    the transition (JDBC read), and after SIGNED neither a second sign nor a withdrawal is
 *    possible — the signed contract is no longer abandonable.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@QuarkusTestResource(LendingOutboxWriteIT.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_lending_it")],
)
class OriginationCommandsIT {

    @Inject
    lateinit var dataSource: DataSource

    private lateinit var withdrawApplicationId: String
    private lateinit var signatureApplicationId: String

    private companion object {
        const val PROPOSER = "commands-it-proposer"
        const val CHECKER = "commands-it-checker"
        const val ADVANCES_TO_FOUR_EYES = 4
        val ARTEFACT_HASH = "ab".repeat(32)
    }

    private fun apply(): String = Given {
        contentType("application/json")
        body(
            """
            {"partyId":"${UUID.randomUUID()}","requestedAmount":{"amount":"10000.00","currency":{"code":"EUR"}},
            "nominalAnnualRate":0.05,"termPeriods":12,"firstDueDate":"${LocalDate.now().plusMonths(1)}"}
            """.trimIndent(),
        )
    } When {
        post("/api/v1/lending/applications")
    } Then {
        statusCode(201)
    } Extract {
        path<String>("id")
    }

    private fun advance(id: String) {
        Given {
            contentType("application/json")
        } When {
            post("/api/v1/lending/applications/$id/advance")
        } Then {
            statusCode(200)
        }
    }

    private fun storedStatus(id: String): String = dataSource.connection.use { c ->
        c.prepareStatement("select status::text from loan_application where id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs ->
                check(rs.next()) { "application $id not stored" }
                rs.getString(1)
            }
        }
    }

    private fun storedArtefactHash(id: String): String? = dataSource.connection.use { c ->
        c.prepareStatement("select signature_artefact_hash from loan_application where id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs ->
                check(rs.next()) { "application $id not stored" }
                rs.getString(1)
            }
        }
    }

    private fun transitionsTo(id: String, state: String): Int = dataSource.connection.use { c ->
        c.prepareStatement(
            "select count(*) from lending_outbox where event_type = 'credit.application.transition' " +
                "and aggregate_id = ? and payload like ?",
        ).use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.setString(2, "%\"toState\":\"$state\"%")
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    @Test
    @Order(1)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER", "ROLE_ADMIN"])
    fun `1 - a too-short withdrawal reason is refused before anything moves`() {
        withdrawApplicationId = apply()
        Given {
            contentType("application/json")
            body("""{"reason":"short"}""")
        } When {
            post("/api/v1/lending/applications/$withdrawApplicationId/withdraw")
        } Then {
            statusCode(400)
        }
        assertThat(storedStatus(withdrawApplicationId)).isEqualTo("SUBMITTED")
    }

    @Test
    @Order(2)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER", "ROLE_ADMIN"])
    fun `2 - withdrawal moves SUBMITTED to WITHDRAWN and the evidence agrees`() {
        Given {
            contentType("application/json")
            body("""{"reason":"applicant no longer needs the loan"}""")
        } When {
            post("/api/v1/lending/applications/$withdrawApplicationId/withdraw")
        } Then {
            statusCode(200)
        }
        assertThat(storedStatus(withdrawApplicationId)).isEqualTo("WITHDRAWN")
        assertThat(transitionsTo(withdrawApplicationId, "WITHDRAWN")).isEqualTo(1)
    }

    @Test
    @Order(3)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER", "ROLE_ADMIN"])
    fun `3 - a terminal WITHDRAWN row refuses a second withdrawal`() {
        Given {
            contentType("application/json")
            body("""{"reason":"applicant changed their mind again"}""")
        } When {
            post("/api/v1/lending/applications/$withdrawApplicationId/withdraw")
        } Then {
            statusCode(422)
        }
        assertThat(storedStatus(withdrawApplicationId)).isEqualTo("WITHDRAWN")
        assertThat(transitionsTo(withdrawApplicationId, "WITHDRAWN")).isEqualTo(1)
    }

    @Test
    @Order(4)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER", "ROLE_ADMIN"])
    fun `4 - document submission outside DOCS_REQUIRED is refused with the row unchanged`() {
        signatureApplicationId = apply()
        repeat(ADVANCES_TO_FOUR_EYES) { advance(signatureApplicationId) }
        assertThat(storedStatus(signatureApplicationId)).isEqualTo("FOUR_EYES")

        Given {
            contentType("application/json")
            body("""{"reason":"all documents attached"}""")
        } When {
            post("/api/v1/lending/applications/$signatureApplicationId/submit-documents")
        } Then {
            statusCode(422)
        }
        assertThat(storedStatus(signatureApplicationId)).isEqualTo("FOUR_EYES")
    }

    @Test
    @Order(5)
    @TestSecurity(user = CHECKER, roles = ["ROLE_CREDIT_RISK", "ROLE_ADMIN"])
    fun `5 - a second person approves and the application reaches AWAITING_SIGNATURE`() {
        Given {
            contentType("application/json")
            body("""{"approve":true,"reason":"within policy"}""")
        } When {
            post("/api/v1/lending/applications/$signatureApplicationId/decision")
        } Then {
            statusCode(200)
        }
        advance(signatureApplicationId)
        assertThat(storedStatus(signatureApplicationId)).isEqualTo("AWAITING_SIGNATURE")
    }

    @Test
    @Order(6)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER", "ROLE_ADMIN"])
    fun `6 - sign moves to SIGNED and persists the artefact hash in the same claim`() {
        Given {
            contentType("application/json")
            body("""{"artefactHash":"$ARTEFACT_HASH"}""")
        } When {
            post("/api/v1/lending/applications/$signatureApplicationId/sign")
        } Then {
            statusCode(200)
        }
        assertThat(storedStatus(signatureApplicationId)).isEqualTo("SIGNED")
        assertThat(storedArtefactHash(signatureApplicationId)).isEqualTo(ARTEFACT_HASH)
        assertThat(transitionsTo(signatureApplicationId, "SIGNED")).isEqualTo(1)
    }

    @Test
    @Order(7)
    @TestSecurity(user = PROPOSER, roles = ["ROLE_LENDING_OFFICER", "ROLE_ADMIN"])
    fun `7 - a signed application can be neither re-signed nor withdrawn`() {
        Given {
            contentType("application/json")
            body("""{"artefactHash":"$ARTEFACT_HASH"}""")
        } When {
            post("/api/v1/lending/applications/$signatureApplicationId/sign")
        } Then {
            statusCode(422)
        }
        Given {
            contentType("application/json")
            body("""{"reason":"applicant got cold feet"}""")
        } When {
            post("/api/v1/lending/applications/$signatureApplicationId/withdraw")
        } Then {
            statusCode(422)
        }
        assertThat(storedStatus(signatureApplicationId)).isEqualTo("SIGNED")
        assertThat(storedArtefactHash(signatureApplicationId)).isEqualTo(ARTEFACT_HASH)
        assertThat(transitionsTo(signatureApplicationId, "SIGNED")).isEqualTo(1)
    }
}
