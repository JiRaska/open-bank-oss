// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.e2e

import com.openbank.pension.application.ProviderBoundary
import com.openbank.pension.application.port.`in`.CreateDraftCommand
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.it.PostgresTestResource
import com.openbank.pension.testsupport.PensionDemoCompany
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.LocalDate
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
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `the deployment uses its own synthetic provider database and persists its provider`() {
        assertThat(boundary.providerEntityId).isEqualTo(company.providerId)
        val contractId = create(company.providerId, "demo-owned-contract").toString()
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
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `a foreign provider is refused without persisting its contract`() {
        val before = foreignContracts()
        assertThatThrownBy { create(company.other.providerId, "demo-wrong-provider") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(foreignContracts()).isEqualTo(before).isZero()
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-relay-subject"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `a different participant cannot read the synthetic contract`() {
        val contractId = create(company.providerId, "demo-private-contract")
        given().header("X-Customer-Party-Id", STRANGER)
            .`when`().get("/api/v2/pension/contracts/$contractId")
            .then().statusCode(404)
        given().header("X-Customer-Party-Id", CUSTOMER)
            .`when`().get("/api/v2/pension/contracts/$contractId")
            .then().statusCode(200)
    }

    @Inject
    lateinit var contracts: PensionContractUseCase

    /**
     * Opens the draft through the use case: the S1 create route is retired (#12435), and the
     * provider boundary this class proves is enforced there, not in the route.
     */
    private fun create(provider: UUID, key: String): UUID = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async {
            contracts.createDraft(
                CreateDraftCommand(
                    participantPartyId = UUID.fromString(CUSTOMER), productLine = ProductLine.DPS,
                    jurisdiction = "CZ", providerEntityId = provider, providerType = ProviderType.PENSION_COMPANY,
                    birthDate = LocalDate.parse("1985-05-05"), residencyCountry = "CZ", residencyEvidence = emptySet(),
                    hasGuardian = false,
                    schedule = ContributionSchedule(BigDecimal("1700"), "CZK", ContributionFrequency.MONTHLY),
                    initialStrategy = "CONSERVATIVE", beneficiaries = emptyList(), idempotencyKey = key,
                ),
            ).id
        }.asUni()
    }

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
