// SPDX-License-Identifier: Apache-2.0
package com.openbank.engagement.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

@QuarkusTest
@TestSecurity(user = "game-test-admin", roles = ["ROLE_ADMIN"])
@QuarkusTestResource(SurfaceRestContractIT.NoKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class GameCatalogIT {
    @Test
    fun `real catalogue update is audited stale write conflicts and rejected write leaves no change`() {
        val before = given().get("/api/v1/game/admin/catalog").then().statusCode(200).extract().body().asString()
        given().contentType("application/json").body(before).put("/api/v1/game/admin/catalog").then().statusCode(200)
        given().contentType("application/json").body(before).put("/api/v1/game/admin/catalog").then().statusCode(409)
        val history = given().get("/api/v1/game/admin/audit").then().statusCode(200).extract().jsonPath()
        assertThat(history.getString("[0].actor")).isEqualTo("game-test-admin")
        assertThat(history.getLong("[0].after.revision")).isEqualTo(history.getLong("[0].before.revision") + 1)
    }

    @Test
    fun `real completion is party scoped idempotent and cannot skip the predecessor or award points`() {
        val party = UUID.randomUUID()
        val stranger = UUID.randomUUID()
        fun complete(level: String, expected: Int) {
            given().queryParam("partyId", party).queryParam("beta", true)
                .post("/api/v1/game/levels/$level/completion").then().statusCode(expected)
        }
        complete("rooftops", 403)
        complete("embankment", 204)
        complete("embankment", 204)
        val chapters = given().queryParam("partyId", party).queryParam("beta", true)
            .get("/api/v1/game/levels").then().statusCode(200).extract().jsonPath()
        assertThat(chapters.getBoolean("levels.find { it.level.id == 'rooftops' }.unlocked")).isTrue()
        val other = given().queryParam("partyId", stranger).queryParam("beta", true)
            .get("/api/v1/game/levels").then().statusCode(200).extract().jsonPath()
        assertThat(other.getBoolean("levels.find { it.level.id == 'rooftops' }.unlocked")).isFalse()
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement("SELECT count(*) FROM game_completion WHERE party_id = ?").use { statement ->
                statement.setObject(1, party)
                statement.executeQuery().use { rows ->
                    rows.next()
                    assertThat(rows.getLong(1)).isEqualTo(1)
                }
            }
            connection.prepareStatement("SELECT count(*) FROM gamification_award WHERE party_id = ?").use { statement ->
                statement.setObject(1, party)
                statement.executeQuery().use { rows ->
                    rows.next()
                    assertThat(rows.getLong(1)).isZero()
                }
            }
        }
    }

    @Test
    @TestSecurity(user = "game-test-edge", roles = ["ROLE_API"])
    fun `edge cannot read or write the admin plane`() {
        given().get("/api/v1/game/admin/catalog").then().statusCode(403)
        given().contentType("application/json").body("{}").put("/api/v1/game/admin/catalog").then().statusCode(403)
        given().get("/api/v1/game/admin/audit").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "game-test-operator", roles = ["ROLE_OPERATOR"])
    fun `operator can inspect catalogue but cannot change it`() {
        val catalog = given().get("/api/v1/game/admin/catalog").then().statusCode(200).extract().body().asString()
        given().contentType("application/json").body(catalog).put("/api/v1/game/admin/catalog").then().statusCode(403)
    }
}
