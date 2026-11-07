// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.devops.application.port.out.DeliveryHistoryRepository
import com.openbank.devops.domain.model.DeliveryEvidenceState
import com.openbank.devops.domain.model.DeliveryObservation
import com.openbank.devops.domain.model.DeliveryOverview
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DeliveryOverviewResourceIT.Profile::class)
class DeliveryHistoryResourceIT {
    /** Test-only HTTP bridge supplies a real Vert.x context to the actual reactive repository. */
    @Path("/test/delivery-observations")
    class FixtureWriter(private val repository: DeliveryHistoryRepository) {
        @POST
        @RolesAllowed("ROLE_ADMIN")
        suspend fun save(observation: DeliveryObservation) = repository.save(observation)
    }

    @Inject
    lateinit var mapper: ObjectMapper

    private fun connection(): Connection {
        val c = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            c.getValue("quarkus.datasource.jdbc.url", String::class.java),
            c.getValue("quarkus.datasource.username", String::class.java),
            c.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private fun observation(at: Instant, count: Int? = 7, repo: String = "example/repo"): DeliveryObservation {
        val slot = Instant.ofEpochSecond(Math.floorDiv(at.epochSecond, 300) * 300)
        return DeliveryObservation(
            slot,
            Instant.now(),
            "1.2.3-test",
            DeliveryOverview(
                at, at.minusSeconds(86400), at, repo,
                DeliveryEvidenceState.UNAVAILABLE, "SOURCE_UNAVAILABLE", 0, emptyList(),
                DeliveryEvidenceState.PARTIAL, "PAGE_LIMIT", 5, count, null,
            ),
        )
    }

    private fun save(value: DeliveryObservation) {
        given().contentType("application/json").body(value).post("/test/delivery-observations")
            .then().statusCode(204)
    }

    private fun scheduledSamples(): Int = connection().use { c ->
        c.prepareStatement(
            "SELECT COUNT(*) FROM delivery_observations WHERE repository = ? AND collector_version <> ?",
        ).use { q ->
            q.setString(1, "example/repo")
            q.setString(2, "1.2.3-test")
            q.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    @Test
    fun `history rejects anonymous readers`() {
        given().get("/api/v1/devops/delivery/history").then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "other", roles = ["ROLE_OTHER"])
    fun `history rejects identities without read access`() {
        given().get("/api/v1/devops/delivery/history").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "reader", roles = ["ROLE_VIEWER"])
    fun `real cron commits a sample which a separate JDBC read can observe`() {
        val deadline = Instant.now().plusSeconds(30)
        var found = false
        while (!found && Instant.now().isBefore(deadline)) {
            found = scheduledSamples() > 0
            if (!found) Thread.sleep(100)
        }
        assertThat(found).describedAs("the real schedule must commit history, not merely fire").isTrue()
        given().get("/api/v1/devops/delivery/history?limit=1").then().statusCode(200)
            .body("samplingEnabled", equalTo(true))
            .body("slotIntervalSeconds", equalTo(300))
            .body("samplingCron", equalTo("*/1 * * * * ?"))
            .body("retentionDays", equalTo(30))
        given().get("/api/v1/devops/delivery/history?limit=0").then().statusCode(400)
        given().get("/api/v1/devops/delivery/history?limit=289").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "writer", roles = ["ROLE_ADMIN"])
    fun `durable round trip preserves incomplete evidence and duplicate slots cannot overwrite it`() {
        val first = observation(Instant.now().minusSeconds(600))
        save(first)
        save(first.copy(overview = first.overview.copy(observedMergedPrs = 999)))
        connection().use { c ->
            c.prepareStatement(
                "SELECT snapshot_json, collector_version, schema_version FROM delivery_observations " +
                    "WHERE repository = ? AND slot_start = ?",
            ).use { q ->
                q.setString(1, first.overview.repository)
                q.setObject(2, first.slotStart.atOffset(java.time.ZoneOffset.UTC))
                q.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    val actual = mapper.readValue(rs.getString(1), DeliveryOverview::class.java)
                    assertThat(actual).isEqualTo(first.overview)
                    assertThat(rs.getString(2)).isEqualTo("1.2.3-test")
                    assertThat(rs.getInt(3)).isEqualTo(1)
                    assertThat(rs.next()).isFalse()
                }
            }
        }
        val response = given().get(
            "/api/v1/devops/delivery/history?limit=288",
        ).then().statusCode(200).extract().asString()
        val rows = mapper.readTree(response).get("observations")
        val roundTrip = rows.first { it.get("slotStart").asText() == first.slotStart.toString() }
        assertThat(
            mapper.treeToValue(roundTrip.get("overview"), DeliveryOverview::class.java),
        ).isEqualTo(first.overview)
    }

    @Test
    @TestSecurity(user = "writer", roles = ["ROLE_ADMIN"])
    fun `retention removes expired rows but preserves recent rows and other repositories`() {
        val now = Instant.now()
        val oldId = UUID.randomUUID()
        val recentId = UUID.randomUUID()
        connection().use { c ->
            for ((id, age) in listOf(oldId to 31L, recentId to 29L)) {
                val sample = observation(now.minusSeconds(age * 86400))
                c.prepareStatement(
                    "INSERT INTO delivery_observations " +
                        "(id,repository,slot_start,observed_at,recorded_at," +
                        "collector_version,schema_version,snapshot_json) " +
                        "VALUES (?,?,?,?,?,?,1,CAST(? AS jsonb))",
                ).use { q ->
                    q.setObject(1, id)
                    q.setString(2, "example/repo")
                    q.setObject(3, sample.slotStart.atOffset(java.time.ZoneOffset.UTC))
                    q.setObject(4, sample.overview.observedAt.atOffset(java.time.ZoneOffset.UTC))
                    q.setObject(5, now.atOffset(java.time.ZoneOffset.UTC))
                    q.setString(6, "1.2.3-test")
                    q.setString(7, mapper.writeValueAsString(sample.overview))
                    q.executeUpdate()
                }
            }
        }
        save(observation(now.minusSeconds(1200)))
        save(observation(now, repo = "another/repo"))
        connection().use { c ->
            c.prepareStatement("SELECT id FROM delivery_observations WHERE id IN (?,?)").use { q ->
                q.setObject(1, oldId)
                q.setObject(2, recentId)
                q.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getObject(1)).isEqualTo(recentId)
                    assertThat(rs.next()).isFalse()
                }
            }
        }
        val json = mapper.readTree(
            given().get("/api/v1/devops/delivery/history").then().statusCode(200).extract().asString(),
        )
        assertThat(
            json.get("observations").all {
                it.get("overview").get("repository").asText() == "example/repo"
            },
        ).isTrue()
    }
}
