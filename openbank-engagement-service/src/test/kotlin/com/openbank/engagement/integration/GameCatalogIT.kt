// SPDX-License-Identifier: Apache-2.0
package com.openbank.engagement.integration

import com.openbank.engagement.application.port.out.GameStore
import com.openbank.engagement.domain.model.game.GameRequirement
import com.openbank.engagement.domain.model.game.GameUnlockKind
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
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
    @Inject
    lateinit var games: GameStore

    @Test
    fun `only the selected partys persisted education award unlocks an achievement chapter`(): Unit = runBlocking {
        val before = games.catalog()
        val party = UUID.randomUUID()
        val stranger = UUID.randomUUID()
        games.update(
            before.copy(
                levels = before.levels.map { level ->
                    if (level.id ==
                        "rooftops"
                    ) {
                        level.copy(
                            requirement = GameRequirement(GameUnlockKind.EARN_SOURCE, "EDUCATIONAL_CONTENT_COMPLETION"),
                        )
                    } else {
                        level
                    }
                },
            ),
            "game-test-admin",
            java.time.Instant.now(),
        )
        try {
            fun unlocked(id: UUID): Boolean = given().queryParam("partyId", id).queryParam("beta", true)
                .get("/api/v1/game/levels").then().statusCode(200).extract().jsonPath()
                .getBoolean("levels.find { it.level.id == 'rooftops' }.unlocked")
            assertThat(unlocked(party)).isFalse()
            val config = ConfigProvider.getConfig()
            DriverManager.getConnection(
                config.getValue("quarkus.datasource.jdbc.url", String::class.java),
                config.getValue("quarkus.datasource.username", String::class.java),
                config.getValue("quarkus.datasource.password", String::class.java),
            ).use { connection ->
                connection.prepareStatement(
                    "INSERT INTO gamification_award(id,party_id,challenge_id,earn_source_id,points,rule_version,correlation_event_id,occurred_at) VALUES (?,?,'COMPLETE_BUDGETING_COURSE','EDUCATIONAL_CONTENT_COMPLETION',50,'v1',?,now())",
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, party)
                    statement.setObject(3, UUID.randomUUID())
                    statement.executeUpdate()
                }
            }
            assertThat(unlocked(party)).isTrue()
            assertThat(unlocked(stranger)).isFalse()
        } finally {
            games.update(before.copy(revision = games.catalog().revision), "game-test-admin", java.time.Instant.now())
        }
    }

    @Test
    fun `erasure removes existing progress and rejects a delayed offline completion`(): Unit = runBlocking {
        val party = UUID.randomUUID()
        given().queryParam("partyId", party).post("/api/v1/game/levels/embankment/completion").then().statusCode(204)
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement(
                "INSERT INTO party_adverse_state(id,party_id,state,set_at) VALUES (?,?,'ERASURE_REQUESTED',now())",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, party)
                statement.executeUpdate()
            }
        }
        games.erase(party)
        given().queryParam("partyId", party).post("/api/v1/game/levels/embankment/completion").then().statusCode(403)
        val result = given().queryParam(
            "partyId",
            party,
        ).get("/api/v1/game/levels").then().statusCode(200).extract().jsonPath()
        assertThat(result.getList<Any>("levels")).isEmpty()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement("SELECT count(*) FROM game_completion WHERE party_id = ?").use { statement ->
                statement.setObject(1, party)
                statement.executeQuery().use { rows ->
                    rows.next()
                    assertThat(rows.getLong(1)).isZero()
                }
            }
        }
    }

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
