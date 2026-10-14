// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.tax.domain.returns.ParticipantCloseLineage
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.domain.returns.StatutoryReturn
import com.openbank.tax.infrastructure.returns.CatalogueParser
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_pension_golden_it")],
)
@QuarkusTestResource(StatutoryReturnMessagingTestResource::class)
@QuarkusTestResource(PensionProvidersStub::class)
class UnsupportedBeneficiaryReturnIT {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val catalogue = CatalogueParser.parse(mapper, "statutory-returns/cz/pension-cnb.v1.json")
    private val definition = catalogue.definition("PSP31-04")!!
    private val key = "unsupported-beneficiary-retry-2016"
    private val issuer = "tax-test-issuer"

    @Test
    @TestSecurity(user = "checker", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "iss", value = "tax-test-issuer"), Claim(key = "sub", value = "checker")])
    fun `historical revisions remain readable but all writes and exact receipt replays refuse without state change`() {
        val legacy = historical("2016-Q1")
        val approved = historical("2016-Q2").approve("checker", Instant.parse("2016-08-01T00:00:00Z"))
        val receipt = historical("2016-Q3", sourced = true)
        connection().use { db ->
            listOf(legacy, approved, receipt).forEach { seed(db, it) }
            val before = stored(db)
            val captures = captureCount()
            listOf("v1", "v2").forEach { version ->
                val base = "/api/$version/statutory-returns"
                listOf(legacy, approved, receipt).forEach { row ->
                    given().get("$base/${row.id}").then().statusCode(200)
                }
                given().post("$base/${legacy.id}/approve").then().statusCode(503)
                given().contentType("application/json").body("""{"reference":"test-submission"}""")
                    .post("$base/${approved.id}/submitted").then().statusCode(503)
                given().contentType("application/json").body(request(receipt.period.label))
                    .post("$base/assemble").then().statusCode(503)
                given().contentType("application/json").header("Idempotency-Key", key)
                    .body(request(receipt.period.label)).post("$base/source-assemblies").then().statusCode(503)
            }
            assertThat(stored(db)).isEqualTo(before)
            assertThat(captureCount()).isEqualTo(captures)
        }
    }

    private fun request(period: String) = mapper.writeValueAsString(
        mapOf(
            "catalogueId" to catalogue.id,
            "returnCode" to definition.code,
            "entityId" to PensionProvidersStub.COMPANY,
            "period" to period,
        ),
    )

    private fun historical(label: String, sourced: Boolean = false): StatutoryReturn {
        val period = ReportingPeriod.parse(definition.periodicity, label)
        val scope = "${issuer.length}:$issuer${"checker".length}:checker"
        return StatutoryReturn.assemble(
            catalogue, definition, PensionProvidersStub.COMPANY, period, 1,
            definition.datapoints.associateWith { BigDecimal.ZERO }, "maker", Instant.parse("2016-11-01T00:00:00Z"),
            participantCloseLineage = if (sourced) {
                ParticipantCloseLineage(
                    UUID.randomUUID(),
                    UUID.fromString(PensionProvidersStub.COMPANY),
                    period.previous().endDate.plusDays(1),
                    period.endDate,
                    1,
                    "participant-period-aggregates/2.0",
                    "a".repeat(64),
                    definition.datapoints.associateWith { "/historical/$it" },
                )
            } else {
                null
            },
            sourceAssemblyKeyHash = if (sourced) digest("tax-source-assembly:v1\n$scope\n$key") else null,
            sourceAssemblyRequestHash = if (sourced) {
                digest(
                    "tax-source-request:v1\n$scope\n${catalogue.id}\n${definition.code}\n${PensionProvidersStub.COMPANY}\n$label\n",
                )
            } else {
                null
            },
        )
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun connection(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private fun seed(db: Connection, row: StatutoryReturn) {
        val columns = linkedMapOf(
            "id" to row.id, "catalogue_id" to row.catalogueId, "catalogue_version" to row.catalogueVersion,
            "return_code" to row.returnCode, "entity_id" to row.entityId, "periodicity" to row.period.periodicity.name,
            "period_end" to row.period.endDate, "revision" to row.revision, "status" to row.status.name,
            "datapoints_json" to mapper.writeValueAsString(row.datapoints), "content_hash" to row.contentHash,
            "due_date" to row.dueDate, "assembled_by" to row.assembledBy,
            "assembled_at" to Timestamp.from(row.assembledAt),
            "approved_by" to row.approvedBy, "approved_at" to row.approvedAt?.let(Timestamp::from),
            "attested_hash" to row.attestedHash, "version" to row.version,
            "content_hash_version" to row.contentHashVersion,
            "participant_close_lineage_json" to row.participantCloseLineage?.let(mapper::writeValueAsString),
            "source_assembly_key_hash" to row.sourceAssemblyKeyHash,
            "source_assembly_request_hash" to row.sourceAssemblyRequestHash,
        )
        val sql = "INSERT INTO statutory_return (${columns.keys.joinToString()}) VALUES (${columns.keys.joinToString {
            "?"
        }})"
        db.prepareStatement(sql).use { statement ->
            columns.values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            assertThat(statement.executeUpdate()).isEqualTo(1)
        }
    }

    private fun stored(db: Connection): List<String> = db.createStatement().use { statement ->
        statement.executeQuery("SELECT to_jsonb(r)::text FROM statutory_return r ORDER BY id").use { result ->
            buildList { while (result.next()) add(result.getString(1)) }
        }
    }

    private fun captureCount(): Int {
        val base = ConfigProvider.getConfig().getValue("tax.source-test.stub-base", String::class.java)
        return given().get("$base/__stats").then().statusCode(200).extract().asString().toInt()
    }
}
