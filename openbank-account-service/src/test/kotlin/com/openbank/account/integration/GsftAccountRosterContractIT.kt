// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.account.integration

import com.openbank.libs.security.Roles
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@TestProfile(GsftAccountRosterContractIT.IsolatedProfile::class)
class GsftAccountRosterContractIT {
    class IsolatedProfile : QuarkusTestProfile {
        override fun disableGlobalTestResources() = true

        override fun testResources() = listOf(
            QuarkusTestProfile.TestResourceEntry(
                PostgresRedpandaRedisTestResource::class.java,
                mapOf("db" to "openbank_accounts_gsft_it"),
            ),
        )
    }

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "service-account-openbank-tax-reporting", roles = [Roles.API])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "gsft-roster-contract"),
            Claim(key = "azp", value = "openbank-tax-reporting"),
            Claim(key = "preferred_username", value = "service-account-openbank-tax-reporting"),
        ],
    )
    fun `pages the historical Czech end of day across statuses and rejects a different day cursor`() {
        seedRoster()
        val first = given().queryParam("asOf", REFERENCE_DAY).queryParam("limit", 2)
            .get(PATH).then().statusCode(200).header("Cache-Control", "no-store").extract().jsonPath()
        assertThat(first.getList("data.id", String::class.java)).containsExactly(id(1), id(2))
        assertThat(first.getList("data.accountType", String::class.java)).containsExactly("CURRENT", "SAVINGS")
        assertThat(first.getBoolean("pagination.hasNextPage")).isTrue()

        val cursor = first.getString("pagination.nextCursor")
        val second = given().queryParam("asOf", REFERENCE_DAY).queryParam("limit", 2).queryParam("cursor", cursor)
            .get(PATH).then().statusCode(200).header("Cache-Control", "no-store").extract().jsonPath()
        assertThat(second.getList("data.id", String::class.java)).containsExactly(id(3))
        assertThat(second.getBoolean("pagination.hasNextPage")).isFalse()

        given().queryParam("asOf", "2026-03-28").queryParam("cursor", cursor)
            .get(PATH).then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-tax-reporting", roles = [Roles.API])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "unrelated-roster-contract"),
            Claim(key = "azp", value = "openbank-unrelated"),
            Claim(key = "preferred_username", value = "service-account-openbank-tax-reporting"),
        ],
    )
    fun `a machine role and matching username do not grant the roster`() {
        given().queryParam("asOf", REFERENCE_DAY).get(PATH).then().statusCode(403)
    }

    private fun seedRoster() {
        val opening = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        val midnight = OffsetDateTime.parse("2026-03-29T22:00:00Z")
        val fixtures = listOf(
            Fixture("CURRENT", "FROZEN", opening),
            Fixture("SAVINGS", "DORMANT", opening),
            Fixture("TERM_DEPOSIT", "CLOSED", opening, midnight),
            Fixture("CURRENT", "CLOSED", opening, midnight.minusSeconds(1)),
            Fixture("CURRENT", "ACTIVE", midnight),
            Fixture("GL_LIABILITY", "ACTIVE", opening),
        )
        val sql = """
            INSERT INTO accounts (id, account_number, account_type, party_id, product_id, currency_code, status,
                                  opened_at, closed_at, terms_version)
            VALUES (?, ?, ?, ?, ?, 'CZK', ?, ?, ?, 'gsft-fixture')
            ON CONFLICT (id) DO NOTHING
        """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                fixtures.forEachIndexed { index, fixture ->
                    val number = index + 1
                    statement.setObject(1, UUID.fromString(id(number)))
                    statement.setString(2, fixtureIban(number))
                    statement.setString(3, fixture.type)
                    statement.setObject(4, UUID.fromString("30000000-0000-0000-0000-000000000001"))
                    statement.setObject(5, UUID.fromString("30000000-0000-0000-0000-000000000002"))
                    statement.setString(6, fixture.status)
                    statement.setObject(7, fixture.openedAt)
                    statement.setObject(8, fixture.closedAt)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun id(number: Int) = "00000000-0000-7000-8000-00000000040$number"

    private fun fixtureIban(number: Int): String {
        val bban = "9999000000000000000$number"
        val checksum = 98 - BigInteger("${bban}123500").mod(BigInteger.valueOf(97)).toInt()
        return "CZ${checksum.toString().padStart(2, '0')}$bban"
    }

    private data class Fixture(
        val type: String,
        val status: String,
        val openedAt: OffsetDateTime,
        val closedAt: OffsetDateTime? = null,
    )

    private companion object {
        const val PATH = "/api/v1/accounts/reporting/deposits"
        const val REFERENCE_DAY = "2026-03-29"
    }
}
