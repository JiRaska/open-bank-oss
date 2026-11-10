// SPDX-License-Identifier: Apache-2.0
package com.openbank.engagement.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.engagement.application.port.out.GameStore
import com.openbank.engagement.domain.model.game.GameCourse
import com.openbank.engagement.domain.model.game.GameLevel
import com.openbank.engagement.domain.model.game.GameLevelState
import com.openbank.engagement.domain.model.game.GameRequirement
import com.openbank.engagement.domain.model.game.GameUnlockKind
import com.openbank.libs.authz.PolicyDecisionPoint
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

@QuarkusTest
@TestSecurity(user = "game-test-admin", roles = ["ROLE_ADMIN"])
@QuarkusTestResource(SurfaceRestContractIT.NoKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class GameCatalogIT {
    @Inject
    lateinit var games: GameStore

    @Inject
    lateinit var mapper: ObjectMapper

    @Inject
    lateinit var policy: Instance<PolicyDecisionPoint>

    @Test
    fun `production policy decision point is actually wired`() {
        assertThat(policy.isUnsatisfied).isFalse()
        assertThat(policy.isAmbiguous).isFalse()
    }

    @Test
    @TestSecurity(user = "marketing-editor", roles = ["ROLE_MARKETING"])
    fun `marketing saves real course through HTTP but cannot publish or change live chapters`(): Unit = runBlocking {
        val before = games.catalog()
        val root = if (Files.isDirectory(Path.of("openbank-admin-ui"))) Path.of("") else Path.of("..")
        val template = mapper.readValue(
            Files.readString(root.resolve("openbank-admin-ui/public/lion-quest/embankment.json")),
            GameCourse::class.java,
        ).copy(id = "marketing_example")
        val draft = GameLevel(
            template.id,
            template.theme,
            98,
            "Nové dobrodružství",
            "New adventure",
            GameLevelState.BETA,
            content = template,
        )
        try {
            val edited = before.copy(levels = before.levels + draft)
            given().contentType("application/json").body(mapper.writeValueAsString(edited))
                .put("/api/v1/game/admin/drafts").then().statusCode(200)
            val saved = games.catalog()
            assertThat(saved.levels.last().content).isEqualTo(template)
            assertThat(saved.revision).isEqualTo(before.revision + 1)
            given().contentType("application/json").body(mapper.writeValueAsString(saved))
                .put("/api/v1/game/admin/catalog").then().statusCode(403)
            val publish = saved.copy(
                levels = saved.levels.map {
                    if (it.id == draft.id) it.copy(state = GameLevelState.LIVE) else it
                },
            )
            given().contentType("application/json").body(mapper.writeValueAsString(publish))
                .put("/api/v1/game/admin/drafts").then().statusCode(400)
            assertThat(games.catalog()).isEqualTo(saved)
        } finally {
            games.update(before.copy(revision = games.catalog().revision), "game-test-admin", java.time.Instant.now())
        }
    }

    @Test
    fun `capable clients receive authored tracks while legacy clients retain bundled chapters`(): Unit = runBlocking {
        val before = games.catalog()
        val root = if (Files.isDirectory(Path.of("openbank-admin-ui"))) Path.of("") else Path.of("..")
        val template = mapper.readValue(
            Files.readString(root.resolve("openbank-admin-ui/public/lion-quest/embankment.json")),
            GameCourse::class.java,
        )
        val authored = template.copy(
            levelEnd = 6000.0,
            ground = template.ground.dropLast(1) + mapOf("x0" to 4440.0, "x1" to 6000.0),
        )
        val newCourse = GameLevel(
            "new_course",
            "embankment",
            97,
            "Nové kolo",
            "New course",
            GameLevelState.BETA,
            content = authored.copy(id = "new_course"),
        )
        try {
            games.update(
                before.copy(
                    levels = before.levels.map {
                        if (it.id == "embankment") it.copy(content = authored) else it
                    } + newCourse,
                ),
                "game-test-admin",
                java.time.Instant.now(),
            )
            val party = UUID.randomUUID()
            val capable = given().queryParam("partyId", party).queryParam("beta", true)
                .queryParam("contentSchema", 1).get("/api/v1/game/levels").then().statusCode(200)
                .extract().jsonPath()
            assertThat(capable.getFloat("levels.find { it.level.id == 'new_course' }.level.content.levelEnd"))
                .isEqualTo(6000f)
            val legacy = given().queryParam("partyId", party).queryParam("beta", true)
                .get("/api/v1/game/levels").then().statusCode(200).extract().jsonPath()
            assertThat(legacy.getList<String>("levels.level.id")).contains("embankment").doesNotContain("new_course")
            assertThat(legacy.get<Any?>("levels.find { it.level.id == 'embankment' }.level.content")).isNull()
        } finally {
            games.update(before.copy(revision = games.catalog().revision), "game-test-admin", java.time.Instant.now())
        }
    }

    @Test
    fun `null course elements and null component values return 400 without changing the catalogue`(): Unit =
        runBlocking {
            val before = games.catalog()
            val root = if (Files.isDirectory(Path.of("openbank-admin-ui"))) Path.of("") else Path.of("..")
            val raw = Files.readString(root.resolve("openbank-admin-ui/public/lion-quest/embankment.json"))
            val fields = listOf(
                "ground", "platforms", "coins", "vampires", "bats", "piggies", "shields", "slopes", "thieves",
                "dropZones", "rooftopObjects", "pigeons", "bridgePlatforms", "bridgeTraps",
            )
            for (field in fields) {
                val content = mapper.readTree(raw) as com.fasterxml.jackson.databind.node.ObjectNode
                content.putArray(field).addNull()
                val document = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(before)
                val level = document.path("levels").first { it.path("id").asText() == "embankment" }
                    as com.fasterxml.jackson.databind.node.ObjectNode
                level.set<com.fasterxml.jackson.databind.JsonNode>("content", content)
                for (path in listOf("/api/v1/game/admin/catalog", "/api/v1/game/admin/drafts")) {
                    given().contentType("application/json").body(document.toString()).put(path).then().statusCode(400)
                }
            }
            val content = mapper.readTree(raw) as com.fasterxml.jackson.databind.node.ObjectNode
            content.putArray("coins").add(mapper.readTree("""{"x":null,"y":38}"""))
            val document = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(before)
            val level = document.path("levels").first { it.path("id").asText() == "embankment" }
                as com.fasterxml.jackson.databind.node.ObjectNode
            level.set<com.fasterxml.jackson.databind.JsonNode>("content", content)
            given().contentType("application/json").body(document.toString())
                .put("/api/v1/game/admin/catalog").then().statusCode(400)
            assertThat(games.catalog()).isEqualTo(before)
        }

    @Test
    fun `null catalogue element returns 400 without changing stored revision`() {
        val before = given().get(
            "/api/v1/game/admin/catalog",
        ).then().statusCode(200).extract().jsonPath().getLong("revision")
        given().contentType("application/json").body("""{"revision":$before,"levels":[null]}""")
            .put("/api/v1/game/admin/catalog").then().statusCode(400)
        val after = given().get(
            "/api/v1/game/admin/catalog",
        ).then().statusCode(200).extract().jsonPath().getLong("revision")
        assertThat(after).isEqualTo(before)
    }

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
