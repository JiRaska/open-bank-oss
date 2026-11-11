// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sanctions.it

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.flyway.FlywayConfigurationCustomizer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Rows written before the reactive JSONB fix hold each payload as a jsonb STRING scalar. Such a row made
 * GET /api/v1/sanctions answer 400 for every caller (Jackson cannot bind a quoted string to a List). The
 * schema is held at V19, the legacy shape is seeded, the list read is shown to fail, then V20 is applied
 * and the same read must succeed with the decoded payloads.
 */
@QuarkusTest
@TestProfile(SanctionsLegacyJsonbStringIT.PreV20Profile::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "legacy-jsonb-string", roles = ["ROLE_OPERATOR"])
class SanctionsLegacyJsonbStringIT {
    class PreV20Profile : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(PreV20Target::class.java)
    }

    @Singleton
    @Alternative
    class PreV20Target : FlywayConfigurationCustomizer {
        override fun customize(configuration: FluentConfiguration) {
            configuration.target("19")
        }
    }

    @Inject
    lateinit var dataSource: DataSource

    private val mapper = jacksonObjectMapper()
    private val id = UUID.fromString("c2020202-2020-4202-8202-202020202020")

    @Test
    fun `V20 unwraps legacy jsonb string payloads so the check list reads again`() {
        dataSource.connection.use { c ->
            c.createStatement().use { it.executeUpdate("DELETE FROM sanctions_checks") }
            // to_jsonb(text) produces exactly the legacy shape: a jsonb string holding JSON text.
            c.prepareStatement(
                "INSERT INTO sanctions_checks (id,idempotency_key,entity_type,name,aliases,identifiers," +
                    "matches,checked_lists,status,overall_score) VALUES (?,?,'INDIVIDUAL','Synthetic Legacy Probe'," +
                    "to_jsonb(?::text),to_jsonb(?::text),to_jsonb(?::text),to_jsonb(?::text),'HIT',0.9)",
            ).use { s ->
                s.setObject(1, id)
                s.setString(2, "legacy-jsonb-string")
                s.setString(3, """["Synthetic Alias"]""")
                s.setString(4, """{"syntheticPassport":"TEST-ONLY-1"}""")
                s.setString(
                    5,
                    """[{"listType":"OFAC_SDN","matchType":"EXACT","matchScore":1.0,"matchedName":"Synthetic",""" +
                        """"matchedId":null,"listEntryDate":null,"programs":["TEST-ONLY"]}]""",
                )
                s.setString(6, """["OFAC_SDN","EU_CONSOLIDATED"]""")
                assertThat(s.executeUpdate()).isEqualTo(1)
            }
        }

        // The defect, reproduced against the real read path before the migration runs.
        given().get("/api/v1/sanctions").then().statusCode(400)

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()

        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT jsonb_typeof(aliases), jsonb_typeof(identifiers), jsonb_typeof(matches), " +
                        "jsonb_typeof(checked_lists) FROM sanctions_checks WHERE id='$id'",
                ).use { r ->
                    assertThat(r.next()).isTrue()
                    assertThat((1..4).map { r.getString(it) }).containsExactly("array", "object", "array", "array")
                }
            }
        }

        val body = given().get("/api/v1/sanctions").then().statusCode(200).extract().asString()
        val row = mapper.readTree(body).single { it["id"].asText() == id.toString() }
        assertThat(row["aliases"]).isEqualTo(mapper.readTree("""["Synthetic Alias"]"""))
        assertThat(row["identifiers"]["syntheticPassport"].asText()).isEqualTo("TEST-ONLY-1")
        assertThat(row["matches"][0]["matchedName"].asText()).isEqualTo("Synthetic")
        assertThat(row["checkedLists"]).isEqualTo(mapper.readTree("""["OFAC_SDN","EU_CONSOLIDATED"]"""))
    }
}
