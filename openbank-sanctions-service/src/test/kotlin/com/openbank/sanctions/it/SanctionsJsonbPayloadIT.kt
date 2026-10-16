// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sanctions.it

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsRepositoryImpl
import io.quarkus.flyway.FlywayConfigurationCustomizer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.uni
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** Legacy populated JSONB schema; HTTP retrieval and real reactive persistence, not mocks. */
@QuarkusTest
@TestProfile(SanctionsJsonbPayloadIT.LegacySchemaProfile::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "payload-regression", roles = ["ROLE_OPERATOR"])
class SanctionsJsonbPayloadIT {
    class LegacySchemaProfile : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(LegacySchemaTarget::class.java)

        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.hibernate-orm.log.sql" to "true",
        )
    }

    @Singleton
    @Alternative
    class LegacySchemaTarget : FlywayConfigurationCustomizer {
        override fun customize(configuration: FluentConfiguration) {
            configuration.target("16")
        }
    }

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var repository: SanctionsRepositoryImpl

    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val id = UUID.fromString("a1313131-1313-4131-8131-131313131313")
    private val columns = listOf("aliases", "identifiers", "matches", "checked_lists")
    private val payloads = listOf(
        """["Synthetic Žluťoučký", "Alias \"quoted\"", "Synthetic Žluťoučký"]""",
        """{"syntheticPassport":"TEST-ONLY-123", "unicode":"žluťoučký"}""",
        """[{"listType":"OFAC_SDN","matchType":"ALIAS","matchScore":0.9,"matchedName":"Synthetic Match",""" +
            """"matchedId":null,"listEntryDate":null,"programs":["TEST-ONLY"]}]""",
        """["OFAC_SDN", "EU_CONSOLIDATED"]""",
    )

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    @BeforeEach
    fun seedLegacyRow() {
        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT max(version::integer) FROM flyway_schema_history WHERE success").use {
                    assertThat(it.next()).isTrue()
                    assertThat(it.getInt(1)).isEqualTo(16)
                }
                s.executeUpdate("DELETE FROM sanctions_checks")
            }
            c.prepareStatement(
                "INSERT INTO sanctions_checks (id,idempotency_key,entity_type,name,aliases,identifiers," +
                    "matches,checked_lists,status,overall_score) VALUES (?,?,'INDIVIDUAL','Synthetic Payload Probe'," +
                    "?::jsonb,?::jsonb,?::jsonb,?::jsonb,'POTENTIAL_HIT',0.9)",
            ).use { s ->
                s.setObject(1, id)
                s.setString(2, "payload-regression-legacy")
                payloads.forEachIndexed { i, json -> s.setString(i + 3, json) }
                assertThat(s.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun response(path: String): JsonNode {
        val r = given().get(path)
        assertThat(r.statusCode).describedAs("legacy JSONB HTTP read %s", path).isEqualTo(200)
        return mapper.readTree(r.asString())
    }

    private fun assertPayloads(node: JsonNode) {
        listOf("aliases", "identifiers", "matches", "checkedLists").forEachIndexed { i, field ->
            assertThat(node[field]).describedAs(field).isEqualTo(mapper.readTree(payloads[i]))
        }
        assertThat(node["dateOfBirth"]?.isNull ?: true).isTrue()
        assertThat(node["reviewedAt"]?.isNull ?: true).isTrue()
    }

    @Test
    fun `legacy populated arrays and objects are readable by all three HTTP routes`() {
        assertPayloads(response("/api/v1/sanctions/$id"))
        assertPayloads(response("/api/v1/sanctions").single { it["id"].asText() == id.toString() })
        val pending = response("/api/v1/sanctions/pending")
        pending.forEach { assertThat(it["status"].asText()).isEqualTo("POTENTIAL_HIT") }
        assertPayloads(pending.single { it["id"].asText() == id.toString() })
        given().get("/api/v1/sanctions/ffffffff-ffff-4fff-8fff-ffffffffffff").then().statusCode(404)
    }

    @Test
    fun `reactive insert and update round trip JSON semantics without changing JSONB storage`() {
        val original = onEventLoop { repository.findById(id) }!!
        val inserted = original.copy(
            id = UUID.fromString("b1313131-1313-4131-8131-131313131313"),
            idempotencyKey = "payload-regression-insert",
        )
        onEventLoop { repository.save(inserted) }
        assertThat(onEventLoop { repository.findById(inserted.id) }).isEqualTo(inserted)
        val updated = inserted.copy(
            status = SanctionsCheckStatus.ESCALATED,
            aliases = listOf("Synthetic Updated Alias", "Updated Žluťoučký"),
            identifiers = mapOf("syntheticPassport" to "TEST-ONLY-UPDATED"),
            matches = inserted.matches.map {
                it.copy(
                    matchedName = "Synthetic Updated Match",
                    matchedId = "TEST-ONLY-ID",
                    programs = listOf("UPDATED", "TEST-ONLY"),
                )
            },
            checkedLists = listOf(SanctionsListType.UN_CONSOLIDATED),
            dateOfBirth = "1980-01-01",
            nationality = "CZ",
            reviewedBy = "synthetic-checker",
            reviewNote = "Synthetic updated review",
            reviewedAt = inserted.checkedAt.plusSeconds(60),
        )
        onEventLoop { repository.updateWithEvent(updated, "sanctions.check.reviewed") }
        assertThat(onEventLoop { repository.findById(inserted.id) }).isEqualTo(updated)
        val returned = response("/api/v1/sanctions/${inserted.id}")
        val expected = mapper.valueToTree<JsonNode>(updated)
        val changedFields = listOf(
            "aliases",
            "identifiers",
            "matches",
            "checkedLists",
            "dateOfBirth",
            "nationality",
            "reviewedBy",
            "reviewNote",
        )
        for (field in changedFields) {
            assertThat(returned[field]).describedAs("updated %s", field).isEqualTo(expected[field])
        }
    }

    private fun assertJsonbColumn(connection: Connection, column: String) {
        connection.prepareStatement(
            "SELECT data_type,is_nullable,column_default FROM information_schema.columns " +
                "WHERE table_schema='public' AND table_name='sanctions_checks' AND column_name=?",
        ).use { statement ->
            statement.setString(1, column)
            statement.executeQuery().use { row ->
                assertThat(row.next()).isTrue()
                assertThat(row.getString(1)).isEqualTo("jsonb")
                assertThat(row.getString(2)).isEqualTo("NO")
                assertThat(row.getString(3)).contains("::jsonb")
            }
        }
    }

    @Test
    fun `database retains JSONB type defaults not null and invalid JSON rejection`() {
        dataSource.connection.use { c ->
            for (column in columns) {
                assertJsonbColumn(c, column)
                assertThatThrownBy {
                    c.createStatement().use {
                        it.executeUpdate("UPDATE sanctions_checks SET $column = 'not-json'::jsonb WHERE id='$id'")
                    }
                }.isInstanceOf(SQLException::class.java).satisfies({ error ->
                    assertThat((error as SQLException).sqlState).isEqualTo("22P02")
                })
                assertThatThrownBy {
                    c.createStatement().use {
                        it.executeUpdate("UPDATE sanctions_checks SET $column = NULL WHERE id='$id'")
                    }
                }.isInstanceOf(SQLException::class.java).satisfies({ error ->
                    assertThat((error as SQLException).sqlState).isEqualTo("23502")
                })
            }
        }
    }
}
