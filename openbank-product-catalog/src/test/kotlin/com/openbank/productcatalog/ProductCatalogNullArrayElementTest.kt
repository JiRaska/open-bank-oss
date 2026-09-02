// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/**
 * Issue #7867. Jackson's Kotlin module null-checks a data class's CONSTRUCTOR PARAMETERS; it does
 * NOT check the ELEMENTS of a collection, so `{"tags": [null]}` used to deserialise into a
 * `List<String>` holding a null, and the first element-wise read NPE'd -- answering 500 where a
 * client error belongs. v1 renders a rejected write as the established `{error}` conflict envelope.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
)
@TestSecurity(user = "test-operator", roles = ["ROLE_OPERATOR"])
class ProductCatalogNullArrayElementTest {
    // Issue #7867: Jackson's Kotlin module null-checks CONSTRUCTOR PARAMETERS, not the ELEMENTS of a
    // collection, so a `[null]` array element used to reach the validator and NPE -- answering 500
    // where a client error belongs. v1 renders a rejected write as the legacy 409 `{error}` envelope.
    private fun postExpectingClientError(payload: String, message: String) {
        Given {
            contentType("application/json")
            body(payload)
        } When { post("/api/v1/products") } Then {
            statusCode(409)
            body("error", equalTo(message))
        }
    }

    @Test
    fun `a null tags element is rejected as a client error, not a 500`() {
        postExpectingClientError(
            """{"code":"NULLTAG_PROD","name":"Test","type":"SAVINGS","currency":"EUR","tags":["ok",null]}""",
            "tags[1] must not be null",
        )
    }

    @Test
    fun `a null fees element is rejected as a client error, not a 500`() {
        postExpectingClientError(
            """{"code":"NULLFEE_PROD","name":"Test","type":"SAVINGS","currency":"EUR","fees":[null]}""",
            "fees[0] must not be null",
        )
    }

    @Test
    fun `a null termsAndConditions element is rejected as a client error, not a 500`() {
        postExpectingClientError(
            """{"code":"NULLTC_PROD","name":"Test","type":"SAVINGS","currency":"EUR","termsAndConditions":[null]}""",
            "termsAndConditions[0] must not be null",
        )
    }

    @Test
    fun `a null supportedCurrencies element is rejected as a client error, not a 500`() {
        postExpectingClientError(
            """{"code":"NULLCCY_PROD","name":"Test","type":"CURRENT","currency":"EUR",""" +
                """"multiCurrencyConfig":{"enabled":true,"defaultCurrency":"EUR","supportedCurrencies":["EUR",null]}}""",
            "multiCurrencyConfig.supportedCurrencies[1] must not be null",
        )
    }

    @Test
    fun `a null interestTiers element is rejected as a client error, not a 500`() {
        postExpectingClientError(
            """{"code":"NULLTIER_PROD","name":"Test","type":"SAVINGS","currency":"EUR",""" +
                """"savingsConfig":{"interestTiers":[null]}}""",
            "savingsConfig.interestTiers[0] must not be null",
        )
    }
}
