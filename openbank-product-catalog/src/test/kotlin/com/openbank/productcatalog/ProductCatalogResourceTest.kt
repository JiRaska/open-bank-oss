// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.productcatalog

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// @RolesAllowed added to create/update/activate/deactivate (this class's own security fix);
// authenticate as an operator so those calls don't 403. The reads stay unauthenticated by
// design, and @TestSecurity is a no-op for them either way.
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
class ProductCatalogResourceTest {

    @Test
    fun `GET products returns seed data`() {
        val body = (
            Given {
                this
            } When { get("/api/v1/products") } Then { statusCode(200) }
            ).extract().body().asString()
        assertThat(body).contains("SAVINGS_STANDARD")
    }

    @Test
    fun `GET product by id returns product`() {
        Given { this } When { get("/api/v1/products/prod-001") } Then { statusCode(200) }
    }

    @Test
    fun `GET unknown product returns 404`() {
        Given { this } When { get("/api/v1/products/does-not-exist") } Then { statusCode(404) }
    }

    @Test
    fun `POST create product returns 201`() {
        val payload = """{"code":"TEST_PROD","name":"Test","type":"SAVINGS","currency":"EUR"}"""
        Given {
            contentType("application/json")
            body(payload)
        } When { post("/api/v1/products") } Then
            { statusCode(201) }
    }

    @Test
    fun `POST activate product returns 200`() {
        Given { this } When { post("/api/v1/products/prod-011/activate") } Then { statusCode(200) }
    }

    @Test
    fun `CZK current account is seeded as a multi-currency CZK-base product`() {
        val body = (
            Given {
                this
            } When { get("/api/v1/products/prod-014") } Then { statusCode(200) }
            ).extract().body().asString()
        assertThat(body).contains("CURRENT_CZK")
        assertThat(body).contains("\"currency\":\"CZK\"")
        assertThat(body).contains("\"defaultCurrency\":\"CZK\"")
    }

    @Test
    fun `GET product by canonical account UUID resolves the seeded product (ADR-0105)`() {
        // account-service references the CZK current/savings products by the UUID it stamps on
        // accounts.product_id; the catalogue must resolve those UUIDs to the same products.
        val current = (
            Given { this } When { get("/api/v1/products/00000000-0000-0000-0000-0000000000c2") } Then
                { statusCode(200) }
            ).extract().body().asString()
        assertThat(current).contains("CURRENT_CZK")
        val savings = (
            Given { this } When { get("/api/v1/products/00000000-0000-0000-0000-0000000000c3") } Then
                { statusCode(200) }
            ).extract().body().asString()
        assertThat(savings).contains("SAVINGS_CZK")
    }

    @Test
    fun `multi-currency umbrella account is seeded with a broad currency set`() {
        val body = (
            Given {
                this
            } When { get("/api/v1/products/prod-015") } Then { statusCode(200) }
            ).extract().body().asString()
        assertThat(body).contains("CURRENT_MULTICURRENCY_UMBRELLA")
        assertThat(body).contains("\"enabled\":true")
        // 12-currency pocket umbrella: assert a representative spread is present.
        assertThat(body).contains("CHF").contains("HUF").contains("BGN")
    }

    @Test
    fun `GET fees returns the flattened fee schedule served by the catalog`() {
        val body = (Given { this } When { get("/api/v1/fees") } Then { statusCode(200) }).extract().body().asString()
        // Fees are aggregated across products, carry their owning product identity,
        // and expose a derived stable code — the UI no longer hardcodes any of this.
        assertThat(body).contains("\"productCode\":\"CURRENT_PERSONAL\"")
        assertThat(body).contains("CURRENT_PERSONAL_FX_CONVERSION")
        assertThat(body).contains("\"frequency\":")
    }

    @Test
    fun `GET fees filters by currency`() {
        val body = (
            Given {
                this
            } When { get("/api/v1/fees?currency=CZK") } Then { statusCode(200) }
            ).extract().body().asString()
        // CZK-base products contribute CZK fees; EUR-only fees must be filtered out.
        assertThat(body).contains("\"currency\":\"CZK\"")
        assertThat(body).doesNotContain("\"currency\":\"EUR\"")
    }
}
