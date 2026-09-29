// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(com.openbank.transaction.it.PostgresRedpandaTestResource::class)
class TransactionCategoryOverrideIT {
    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    @ConfigProperty(name = "quarkus.datasource.username")
    lateinit var jdbcUser: String

    @ConfigProperty(name = "quarkus.datasource.password")
    lateinit var jdbcPassword: String

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `upsert enrichment and repeated removal preserve the other account override`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val firstTransaction = createTransaction(first)
        val secondTransaction = createTransaction(second)
        setCategory(firstTransaction, first, "GROCERIES")
        setCategory(secondTransaction, second, "GROCERIES")
        assertThat(storedCategories(first)).containsExactly("GROCERIES")

        setCategory(firstTransaction, first, "TRANSPORT")

        assertThat(storedCategories(first)).containsExactly("TRANSPORT")
        assertThat(storedCategories(second)).containsExactly("GROCERIES")
        RestAssured.given().queryParam("accountId", first)
            .get("/api/v1/transactions/category-overrides")
            .then().statusCode(200)
            .body("data.size()", equalTo(1))
            .body("data[0].counterpartyKey", equalTo("EXAMPLESHOP"))
            .body("data[0].category", equalTo("TRANSPORT"))
        RestAssured.given().queryParam("accountId", first)
            .get("/api/v1/transactions")
            .then().statusCode(200)
            .body("data[0].category", equalTo("TRANSPORT"))
            .body("data[0].categorySource", equalTo("CUSTOMER"))
        RestAssured.given().queryParam("accountId", second)
            .get("/api/v1/transactions")
            .then().statusCode(200).body("data[0].category", equalTo("GROCERIES"))

        repeat(2) {
            RestAssured.given().queryParam("accountId", first)
                .delete("/api/v1/transactions/$firstTransaction/category")
                .then().statusCode(204)
        }
        assertThat(storedCategories(first)).isEmpty()
        assertThat(storedCategories(second)).containsExactly("GROCERIES")
        RestAssured.given().queryParam("accountId", first)
            .get("/api/v1/transactions/category-overrides")
            .then().statusCode(200).body("data.size()", equalTo(0))
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `invalid category and unrelated account cannot create an override`() {
        val account = UUID.randomUUID()
        val unrelated = UUID.randomUUID()
        val transaction = createTransaction(account)
        RestAssured.given().queryParam("accountId", account).contentType("application/json")
            .body(mapOf("category" to "not-a-category"))
            .put("/api/v1/transactions/$transaction/category")
            .then().statusCode(400)
        RestAssured.given().queryParam("accountId", unrelated).contentType("application/json")
            .body(mapOf("category" to "GROCERIES"))
            .put("/api/v1/transactions/$transaction/category")
            .then().statusCode(400)
        assertThat(storedCategories(account)).isEmpty()
        assertThat(storedCategories(unrelated)).isEmpty()
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `missing account returns a client error through HTTP`() {
        val transaction = UUID.randomUUID()
        RestAssured.given().contentType("application/json").body(mapOf("category" to "GROCERIES"))
            .put("/api/v1/transactions/$transaction/category").then().statusCode(400)
        RestAssured.given().delete("/api/v1/transactions/$transaction/category").then().statusCode(400)
        RestAssured.given().get("/api/v1/transactions/category-overrides").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_VIEWER"])
    fun `viewer cannot change or remove a category`() {
        val transaction = UUID.randomUUID()
        val account = UUID.randomUUID()
        RestAssured.given().queryParam("accountId", account).contentType("application/json")
            .body(mapOf("category" to "GROCERIES"))
            .put("/api/v1/transactions/$transaction/category").then().statusCode(403)
        RestAssured.given().queryParam("accountId", account)
            .delete("/api/v1/transactions/$transaction/category").then().statusCode(403)
    }

    private fun createTransaction(account: UUID): String = RestAssured.given()
        .contentType("application/json")
        .body(
            mapOf(
                "idempotencyKey" to UUID.randomUUID().toString(),
                "type" to "CREDIT",
                "targetAccountId" to account.toString(),
                "amount" to "100.00",
                "currencyCode" to "CZK",
                "description" to "Example Shop",
                "valueDate" to LocalDate.now().toString(),
            ),
        )
        .post("/api/v1/transactions")
        .then().statusCode(201).extract().jsonPath().getString("id")

    private fun setCategory(transaction: String, account: UUID, category: String) {
        RestAssured.given().queryParam("accountId", account).contentType("application/json")
            .body(mapOf("category" to category))
            .put("/api/v1/transactions/$transaction/category")
            .then().statusCode(200).body("category", equalTo(category))
    }

    private fun storedCategories(account: UUID): List<String> =
        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { connection ->
            connection.prepareStatement(
                "SELECT category FROM transaction_category_override WHERE account_id = ? ORDER BY counterparty_key",
            ).use { statement ->
                statement.setObject(1, account)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(rows.getString("category"))
                    }
                }
            }
        }
}
