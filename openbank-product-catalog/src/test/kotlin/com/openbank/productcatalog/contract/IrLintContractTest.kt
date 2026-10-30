// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.contract

import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * API contract of ADR-0352 phase 5b on the catalog (openapi.yaml 2.10.0): `POST /api/v2/product-ir/lint`,
 * the Studio IR editor's dry run.
 *
 * Two claims. (1) The lint reports the parser's problems; it shares `GenericCatalogService.parseIr`
 * with the revision write, so the two cannot disagree (IrPublishContractTest covers the write refusing
 * an invalid IR). (2) The Studio path yields the bytes `obp compile` yields: for
 * every committed `openbank-products/ir/<name>.json` the lint's `canonicalIr` equals the file and its
 * `revisionHash` equals the hash the shared parser computes — the Studio half of
 * `product-ir-authoring-parity`.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
class IrLintContractTest {

    @Test
    fun `an invalid IR is valid=false with the parser's problems, shared with the write path`() {
        val invalid = """{"irVersion":1,"productCode":"X","currency":"EUR","hooks":{},"bogus":true}"""
        Given {
            contentType("application/json")
            body("""{"ir":$invalid}""")
        } When {
            post("/api/v2/product-ir/lint")
        } Then {
            statusCode(200)
            body("valid", equalTo(false))
            body("revisionHash", nullValue())
            body("canonicalIr", nullValue())
            body("problems.path", hasItem("/bogus"))
        }
    }

    @Test
    fun `a request without an ir object is a 400, never a vacuous valid=true`() {
        Given {
            contentType("application/json")
            body("""{}""")
        } When {
            post("/api/v2/product-ir/lint")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "test-reader", roles = ["CATALOG_SCOPE_READ"])
    fun `a read-only caller is refused with 403`() {
        Given {
            contentType("application/json")
            body("""{"ir":{"irVersion":1,"productCode":"X","currency":"EUR","hooks":{}}}""")
        } When {
            post("/api/v2/product-ir/lint")
        } Then {
            statusCode(403)
        }
    }

    @Test
    fun `every committed reference IR lints to its own bytes and hash`() {
        val files = Files.list(IR_DIR).use { s -> s.filter { it.toString().endsWith(".json") }.sorted().toList() }
        assertThat(files).describedAs("committed IR under $IR_DIR").isNotEmpty()
        files.forEach { file ->
            val committed = Files.readString(file).trimEnd()
            val expected = ProductIrParser.parse(committed)
            val canonical = Given {
                contentType("application/json")
                body("""{"ir":$committed}""")
            } When {
                post("/api/v2/product-ir/lint")
            } Then {
                statusCode(200)
                body("valid", equalTo(true))
                body("productCode", equalTo(expected.productCode))
                body("irVersion", equalTo(expected.irVersion))
                body("revisionHash", equalTo(expected.revisionHash))
            } Extract { jsonPath().getString("canonicalIr") }
            assertThat(canonical).describedAs(file.fileName.toString()).isEqualTo(committed)
        }
    }

    private companion object {
        val IR_DIR: Path = Path.of("..", "openbank-products", "ir")
    }
}
