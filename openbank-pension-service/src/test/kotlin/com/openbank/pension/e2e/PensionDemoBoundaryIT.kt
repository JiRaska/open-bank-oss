// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.e2e

import com.openbank.pension.application.ProviderBoundary
import com.openbank.pension.it.PostgresTestResource
import com.openbank.pension.testsupport.PensionDemoCompany
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/** Storage/creation proof in two separate demo invocations; NOT real OIDC or cross-deployment auth. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(PensionFullLifecycleJourneyE2E.StubbedCollaborators::class)
class PensionDemoBoundaryIT {
    @Inject
    lateinit var boundary: ProviderBoundary

    private val company = PensionDemoCompany.current()

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `the deployment uses its own synthetic provider database and persists its provider`() {
        assertThat(boundary.providerEntityId).isEqualTo(company.providerId)
        val response = create(company.providerId, "demo-owned-contract")
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(201)
        val contractId = response.jsonPath().getString("contractId")
        connection().use { db ->
            db.createStatement().use { statement ->
                statement.executeQuery("select current_database()").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo(PensionDemoCompany.databaseName())
                }
            }
            db.prepareStatement("select provider_entity_id from pension_contracts where contract_id = ?").use { query ->
                query.setObject(1, UUID.fromString(contractId))
                query.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject(1, UUID::class.java)).isEqualTo(company.providerId)
                }
            }
        }
        println(
            "PENSION_DEMO_CONTEXT|${company.slug}|${boundary.providerEntityId}|${PensionDemoCompany.databaseName()}",
        )
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a foreign provider is refused without persisting its contract`() {
        val before = foreignContracts()
        val response = create(company.other.providerId, "demo-wrong-provider")
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(400)
        assertThat(foreignContracts()).isEqualTo(before).isZero()
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a different participant cannot read the synthetic contract`() {
        val response = create(company.providerId, "demo-private-contract")
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(201)
        given().header("X-Customer-Party-Id", STRANGER)
            .`when`().get("/api/v2/pension/contracts/${response.jsonPath().getString("contractId")}")
            .then().statusCode(404)
    }

    private fun create(provider: UUID, key: String) = given().contentType("application/json")
        .header("X-Customer-Party-Id", CUSTOMER)
        .header("Idempotency-Key", key)
        .body(
            """{"productLine":"DPS","jurisdiction":"CZ","providerEntityId":"$provider",
                "providerType":"PENSION_COMPANY","birthDate":"1985-05-05","residencyCountry":"CZ",
                "schedule":{"amount":1700,"currency":"CZK","frequency":"MONTHLY"},
                "strategyCode":"CONSERVATIVE"}""",
        ).`when`().post("/api/v2/pension/contracts")

    private fun foreignContracts(): Long = connection().use { db ->
        db.prepareStatement("select count(*) from pension_contracts where provider_entity_id <> ?").use { query ->
            query.setObject(1, company.providerId)
            query.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
    }

    private fun connection() = ConfigProvider.getConfig().let { config ->
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private companion object {
        // Deliberately identical customer/key fixtures in both deployments: storage is separate.
        const val CUSTOMER = "00000000-0000-4000-8000-000000000101"
        const val STRANGER = "00000000-0000-4000-8000-000000000102"
    }
}
