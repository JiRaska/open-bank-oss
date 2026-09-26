// SPDX-License-Identifier: Apache-2.0
package com.openbank.settlement.infrastructure.rest

import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.it.PostgresTestResource
import com.openbank.settlement.it.SettlementOpaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.util.UUID
import javax.sql.DataSource

/** Real HTTP + deployment OPA + PostgreSQL; no mocked use case or repository. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(SettlementOpaTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(SettlementQueryProfile::class)
class SettlementQueryResourceIT {
    @Inject lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "settlement-query-operator", roles = ["ROLE_OPERATOR"])
    fun `operator reads every stored state and exact amount without changing financial state`() {
        val spec = javaClass.getResourceAsStream("/openapi.yaml")!!.use { Yaml().load<Map<String, Any>>(it) }
        val schemas = (spec.getValue("components") as Map<*, *>)["schemas"] as Map<*, *>
        val properties = (schemas["SettlementDetails"] as Map<*, *>)["properties"] as Map<*, *>
        val vocabulary = (properties["status"] as Map<*, *>)["enum"] as List<*>
        assertThat(vocabulary).containsExactlyInAnyOrderElementsOf(SettlementStatus.entries.map { it.name })
        for (status in SettlementStatus.entries) {
            val id = seed(status)
            val before = snapshot(id)
            val detail = given().get("$BASE/$id").then().statusCode(200)
                .header("Cache-Control", "no-store").extract()
            assertThat(detail.path<String>("id")).isEqualTo(id.toString())
            assertThat(detail.path<String>("payerAccountId")).isEqualTo(PAYER.toString())
            assertThat(detail.path<String>("payeeAccountId")).isEqualTo(PAYEE.toString())
            assertThat(detail.path<String>("amount")).isEqualTo("999999999999999.9900")
            assertThat(detail.path<String>("currency")).isEqualTo("CZK")
            assertThat(detail.path<String>("status")).isEqualTo(status.name)
            assertThat(detail.path<String>("createdAt")).isNotBlank()
            assertThat(detail.path<String>("updatedAt")).isNotBlank()
            assertThat(snapshot(id)).isEqualTo(before)
        }
    }

    @Test
    @TestSecurity(user = "settlement-query-admin", roles = ["ROLE_ADMIN"])
    fun `administrator can inspect a stored settlement`() {
        given().get("$BASE/${seed(SettlementStatus.BOOKED)}").then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "settlement-query-operator", roles = ["ROLE_OPERATOR"])
    fun `missing row and malformed UUID have distinct explicit outcomes`() {
        given().get("$BASE/${UUID.randomUUID()}").then().statusCode(404)
        given().get("$BASE/not-a-uuid").then().statusCode(400)
        given().get("$BASE/1-1-1-1-1").then().statusCode(400)
    }

    @Test
    fun `unauthenticated caller cannot read financial state`() {
        given().get("$BASE/${UUID.randomUUID()}").then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "settlement-query-viewer", roles = ["ROLE_VIEWER"])
    fun `viewer role cannot read financial state`() {
        given().get("$BASE/${UUID.randomUUID()}").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR"])
    fun `service identity cannot borrow the human operator permission`() {
        given().get("$BASE/${UUID.randomUUID()}").then().statusCode(403)
    }

    private fun seed(status: SettlementStatus): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO settlements (id, payer_account_id, payee_account_id, amount, currency, status) " +
                    "VALUES (?, ?, ?, 999999999999999.99, 'CZK', ?)",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, PAYER)
                statement.setObject(3, PAYEE)
                statement.setString(4, status.name)
                statement.executeUpdate()
            }
        }
        return id
    }

    private fun snapshot(id: UUID): String = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT row_to_json(s)::text || ':' || " +
                "(SELECT count(*) FROM settlement_outbox WHERE aggregate_id = s.id)::text " +
                "FROM settlements s WHERE id = ?",
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }

    private companion object {
        const val BASE = "/api/v1/settlements"
        val PAYER: UUID = UUID.fromString("a0000000-0000-0000-0000-000000000010")
        val PAYEE: UUID = UUID.fromString("a0000000-0000-0000-0000-000000000011")
    }
}

/** An independent Quarkus application lifecycle for the read-only HTTP proof. */
class SettlementQueryProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = SettlementFourEyesProfile().getConfigOverrides()
}
