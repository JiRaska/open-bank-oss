// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import javax.sql.DataSource

// product-catalog now authenticates its callers (issue #401): reads need a valid token, writes need
// ROLE_OPERATOR. OIDC is disabled under test (%test) and @TestSecurity mocks the operator identity
// every real caller carries (openbank-services / openbank-edge service token, or the admin-ui operator).
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
class ProductCatalogResourceTest {
    @Inject
    lateinit var dataSource: DataSource

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
    fun `v1 compatibility responses advertise the v2 successor and sunset`() {
        Given { this } When { get("/api/v1/products") } Then {
            statusCode(200)
            header("X-API-Version", equalTo("v1"))
            header("Deprecation", equalTo("true"))
            header("Sunset", equalTo("Wed, 10 Feb 2027 00:00:00 GMT"))
            header("Link", equalTo("</api/v2/offerings>; rel=\"successor-version\""))
        }
        Given { this } When { get("/api/v1/fees") } Then {
            statusCode(200)
            header("X-API-Version", equalTo("v1"))
            header("Deprecation", equalTo("true"))
            header("Sunset", equalTo("Wed, 10 Feb 2027 00:00:00 GMT"))
            header("Link", equalTo("</api/v2/offerings>; rel=\"successor-version\""))
        }
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
    fun `invalid product preserves the legacy v1 conflict envelope`() {
        val payload = """{"code":"bad-code","name":"Test","type":"SAVINGS","currency":"EUR"}"""
        Given {
            contentType("application/json")
            body(payload)
        } When { post("/api/v1/products") } Then {
            statusCode(409)
            body(
                "error",
                equalTo("code must contain 2-64 uppercase letters, digits or underscores and start with a letter"),
            )
        }
    }

    @Test
    fun `duplicate product code returns conflict`() {
        val payload = """{"code":"P0_DUPLICATE","name":"Test","type":"SAVINGS","currency":"EUR"}"""
        Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/products")
        } Then { statusCode(201) }
        Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/products")
        } Then {
            statusCode(409)
            body("error", equalTo("Product with code 'P0_DUPLICATE' already exists"))
        }
    }

    @Test
    fun `ETag prevents a stale product update`() {
        val createPayload =
            """{"code":"P0_CONCURRENCY","name":"Initial","type":"SAVINGS","currency":"EUR"}"""
        val created = (
            Given {
                contentType("application/json")
                body(createPayload)
            } When {
                post("/api/v1/products")
            } Then {
                statusCode(201)
                header("ETag", equalTo("\"0\""))
                body("revision", equalTo(0))
            }
            ).extract().response()
        val id = created.jsonPath().getString("id")

        val firstUpdate =
            """{"code":"P0_CONCURRENCY","name":"First","type":"SAVINGS","currency":"EUR","revision":0}"""
        Given {
            contentType("application/json")
            header("If-Match", "\"0\"")
            body(firstUpdate)
        } When { put("/api/v1/products/$id") } Then {
            statusCode(200)
            header("ETag", equalTo("\"1\""))
            body("revision", equalTo(1))
        }

        val staleUpdate =
            """{"code":"P0_CONCURRENCY","name":"Stale","type":"SAVINGS","currency":"EUR","revision":0}"""
        Given {
            contentType("application/json")
            header("If-Match", "\"0\"")
            body(staleUpdate)
        } When { put("/api/v1/products/$id") } Then {
            statusCode(409)
            body("code", equalTo("CONCURRENT_MODIFICATION"))
        }
    }

    @Test
    fun `database advances revision for a legacy writer during rolling deployment`() {
        val payload = """{"code":"P0_MIXED_VERSION","name":"Initial","type":"SAVINGS","currency":"EUR"}"""
        val id = (
            Given {
                contentType("application/json")
                body(payload)
            } When {
                post("/api/v1/products")
            } Then {
                statusCode(201)
                header("ETag", equalTo("\"0\""))
            }
            ).extract().jsonPath().getString("id")

        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE products SET doc = doc WHERE id = CAST(? AS uuid)").use { statement ->
                statement.setString(1, id)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }

        Given { this } When { get("/api/v1/products/$id") } Then {
            statusCode(200)
            header("ETag", equalTo("\"1\""))
            body("revision", equalTo(1))
            body("name", equalTo("Initial"))
        }

        Given {
            contentType("application/json")
            header("If-Match", "\"0\"")
            body(payload)
        } When { put("/api/v1/products/$id") } Then {
            statusCode(409)
            body("code", equalTo("CONCURRENT_MODIFICATION"))
        }
    }

    @Test
    fun `create cannot bypass the draft lifecycle`() {
        val payload =
            """{"code":"P0_ACTIVE_CREATE","name":"Active","type":"SAVINGS","currency":"EUR","status":"ACTIVE"}"""

        Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/products")
        } Then {
            statusCode(409)
            body("error", equalTo("new products must start in DRAFT"))
        }
    }

    @Test
    fun `legacy invalid draft cannot bypass validation through activation`() {
        val id = "00000000-0000-0000-0000-00000000bad0"
        val document =
            """{"id":"$id","code":"legacy-bad-code","name":"Legacy","type":"SAVINGS",""" +
                """"currency":"EUR","status":"DRAFT"}"""
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO products (id, code, type, status, currency, doc) """ +
                    """VALUES (CAST(? AS uuid), ?, 'SAVINGS', 'DRAFT', 'EUR', to_jsonb(CAST(? AS text)))""",
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, "legacy-bad-code")
                statement.setString(3, document)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }

        Given { this } When { post("/api/v1/products/$id/activate") } Then {
            statusCode(400)
            body("code", equalTo("VALIDATION_ERROR"))
        }
        Given { this } When { get("/api/v1/products/$id") } Then {
            statusCode(200)
            body("status", equalTo("DRAFT"))
            body("revision", equalTo(0))
        }
    }

    @Test
    fun `generic update cannot bypass status or active product immutability guards`() {
        val current = (
            Given { this } When { get("/api/v1/products/prod-001") } Then { statusCode(200) }
            ).extract().jsonPath()
        val revision = current.getLong("revision")
        val statusPayload =
            """{"code":"SAVINGS_STANDARD","name":"Savings","type":"SAVINGS","currency":"EUR",""" +
                """"status":"ARCHIVED","revision":$revision}"""
        Given {
            contentType("application/json")
            body(statusPayload)
        } When {
            put("/api/v1/products/prod-001")
        } Then {
            statusCode(409)
            body("error", equalTo("status changes must use the dedicated lifecycle operation"))
        }

        val pricingPayload =
            """{"code":"SAVINGS_STANDARD","name":"Savings","type":"SAVINGS","currency":"EUR",""" +
                """"fee":99,"revision":$revision}"""
        Given {
            contentType("application/json")
            body(pricingPayload)
        } When {
            put("/api/v1/products/prod-001")
        } Then {
            statusCode(409)
            body("error", equalTo("active products are immutable; deactivate or author a new revision"))
        }

        Given { this } When { get("/api/v1/products/prod-001") } Then {
            statusCode(200)
            body("status", equalTo("ACTIVE"))
            body("fee", equalTo(0.0F))
        }
    }

    @Test
    fun `rejects malformed and weak If-Match values`() {
        val createPayload = """{"code":"P0_ETAG_SYNTAX","name":"Initial","type":"SAVINGS","currency":"EUR"}"""
        val id = (
            Given {
                contentType("application/json")
                body(createPayload)
            } When {
                post("/api/v1/products")
            } Then { statusCode(201) }
            ).extract().jsonPath().getString("id")
        val payload = """{"code":"P0_ETAG_SYNTAX","name":"Changed","type":"SAVINGS","currency":"EUR"}"""
        listOf("0", "\"0", "W/\"0\"", "\"0\",\"1\"", "*").forEach { invalid ->
            Given {
                contentType("application/json")
                header("If-Match", invalid)
                body(payload)
            } When { put("/api/v1/products/$id") } Then {
                statusCode(400)
                body("code", equalTo("VALIDATION_ERROR"))
            }
        }

        Given {
            contentType("application/json")
            header("If-Match", "\"0\"")
            body(payload)
        } When { put("/api/v1/products/$id") } Then {
            statusCode(200)
            body("name", equalTo("Changed"))
        }
    }

    @Test
    fun `product code remains immutable on update`() {
        val createPayload =
            """{"code":"P0_IMMUTABLE","name":"Initial","type":"SAVINGS","currency":"EUR"}"""
        val id = (
            Given {
                contentType("application/json")
                body(createPayload)
            } When {
                post("/api/v1/products")
            } Then { statusCode(201) }
            ).extract().jsonPath().getString("id")
        val updatePayload =
            """{"code":"P0_RENAMED","name":"Changed","type":"SAVINGS","currency":"EUR","revision":0}"""

        Given {
            contentType("application/json")
            body(updatePayload)
        } When {
            put("/api/v1/products/$id")
        } Then {
            statusCode(409)
            body("error", equalTo("code is immutable and must remain 'P0_IMMUTABLE'"))
        }
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
    fun `GET product by canonical account UUID resolves and returns the canonical identity (ADR-0105)`() {
        // account-service references the CZK current/savings products by the UUID it stamps on
        // accounts.product_id; the catalogue must resolve those UUIDs to the same products.
        val current = (
            Given { this } When { get("/api/v1/products/00000000-0000-0000-0000-0000000000c2") } Then {
                statusCode(200)
                body("id", equalTo("00000000-0000-0000-0000-0000000000c2"))
            }
            ).extract().body().asString()
        assertThat(current).contains("CURRENT_CZK")
        val savings = (
            Given { this } When { get("/api/v1/products/00000000-0000-0000-0000-0000000000c3") } Then {
                statusCode(200)
                body("id", equalTo("00000000-0000-0000-0000-0000000000c3"))
            }
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
        // and expose a derived display code — the UI no longer hardcodes any of this.
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
