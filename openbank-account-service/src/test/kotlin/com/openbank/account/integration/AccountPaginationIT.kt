// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.account.integration

import com.openbank.libs.api.pagination.CursorEncoder
import com.openbank.libs.security.Roles
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

@QuarkusTest
@QuarkusTestResource(com.openbank.account.it.PostgresRedpandaRedisTestResource::class)
class AccountPaginationIT {
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = [Roles.VIEWER])
    fun `cursor from another party is rejected`() {
        val ownerParty = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        try {
            seed(accountId, ownerParty, "CURRENT")
            Given {
                queryParam("partyId", UUID.randomUUID().toString())
                queryParam("limit", 1)
                queryParam("cursor", CursorEncoder.encode(accountId.toString()))
            } When { get("/api/v1/accounts") } Then { statusCode(400) }
        } finally {
            connection().use { db ->
                db.prepareStatement("DELETE FROM accounts WHERE id = ?").use { delete ->
                    delete.setObject(1, accountId)
                    delete.executeUpdate()
                }
            }
        }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = [Roles.VIEWER])
    fun `cursor includes savings with a lower UUID after current account`() {
        val partyId = UUID.randomUUID()
        val currentId = UUID.fromString("f${UUID.randomUUID().toString().drop(1)}")
        val savingsId = UUID.fromString("1${UUID.randomUUID().toString().drop(1)}")
        val laterSavingsId = UUID.fromString("2${UUID.randomUUID().toString().drop(1)}")
        val otherId = UUID.fromString("0${UUID.randomUUID().toString().drop(1)}")
        try {
            seed(currentId, partyId, "CURRENT")
            seed(savingsId, partyId, "SAVINGS")
            seed(laterSavingsId, partyId, "SAVINGS")
            seed(otherId, partyId, "NOSTRO")
            val first = Given {
                queryParam("partyId", partyId.toString())
                queryParam("limit", 1)
            } When { get("/api/v1/accounts") } Then { statusCode(200) }
            val firstJson = first.extract().body().jsonPath()
            assertThat(firstJson.getString("data[0].id")).isEqualTo(currentId.toString())
            assertThat(firstJson.getBoolean("pagination.hasNextPage")).isTrue()

            val second = Given {
                queryParam("partyId", partyId.toString())
                queryParam("limit", 1)
                queryParam("cursor", firstJson.getString("pagination.nextCursor"))
            } When { get("/api/v1/accounts") } Then { statusCode(200) }
            val secondJson = second.extract().body().jsonPath()
            assertThat(secondJson.getString("data[0].id")).isEqualTo(savingsId.toString())
            assertThat(secondJson.getBoolean("pagination.hasNextPage")).isTrue()

            val third = Given {
                queryParam("partyId", partyId.toString())
                queryParam("limit", 1)
                queryParam("cursor", secondJson.getString("pagination.nextCursor"))
            } When { get("/api/v1/accounts") } Then { statusCode(200) }
            val thirdJson = third.extract().body().jsonPath()
            assertThat(thirdJson.getString("data[0].id")).isEqualTo(laterSavingsId.toString())
            assertThat(thirdJson.getBoolean("pagination.hasNextPage")).isTrue()

            val fourth = Given {
                queryParam("partyId", partyId.toString())
                queryParam("limit", 1)
                queryParam("cursor", thirdJson.getString("pagination.nextCursor"))
            } When { get("/api/v1/accounts") } Then { statusCode(200) }
            val fourthJson = fourth.extract().body().jsonPath()
            assertThat(fourthJson.getString("data[0].id")).isEqualTo(otherId.toString())
            assertThat(fourthJson.getBoolean("pagination.hasNextPage")).isFalse()
        } finally {
            connection().use { db ->
                db.prepareStatement("DELETE FROM accounts WHERE id IN (?, ?, ?, ?)").use { delete ->
                    delete.setObject(1, currentId)
                    delete.setObject(2, savingsId)
                    delete.setObject(3, laterSavingsId)
                    delete.setObject(4, otherId)
                    delete.executeUpdate()
                }
            }
        }
    }

    private fun seed(id: UUID, partyId: UUID, type: String) {
        val bban = "0800" + ThreadLocalRandom.current().nextLong(1_000_000_000_000_000, 10_000_000_000_000_000)
        val check = 98 - BigInteger(bban + "123500").mod(BigInteger.valueOf(97)).toInt()
        val iban = "CZ%02d%s".format(check, bban)
        connection().use { db ->
            db.prepareStatement(
                """
                INSERT INTO accounts (id, account_number, account_type, party_id, product_id, currency_code, status)
                VALUES (?, ?, ?, ?, ?, 'CZK', 'ACTIVE')
                """.trimIndent(),
            ).use { insert ->
                insert.setObject(1, id)
                insert.setString(2, iban)
                insert.setString(3, type)
                insert.setObject(4, partyId)
                insert.setObject(5, UUID.randomUUID())
                insert.executeUpdate()
            }
        }
    }

    private fun connection(): java.sql.Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }
}
