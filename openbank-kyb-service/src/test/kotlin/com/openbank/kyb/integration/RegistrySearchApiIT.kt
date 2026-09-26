// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.integration

import com.openbank.kyb.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test

/**
 * `GET /api/v1/kyb/registry/search` over real HTTP (issue #9707).
 *
 * A unit test cannot tell a served route from an unserved one, and it cannot tell a 400 from a
 * 500 — both matter here. The parameters are declared nullable precisely so an absent one is a
 * 400 from `requireNotNull`; declared non-null, JAX-RS injects null and Kotlin's
 * `checkNotNullParameter` throws at offset 0, which `GenericExceptionMapper` renders as a 500
 * (root CLAUDE.md, gate `nonnull-jaxrs-param-ratchet`). The missing-parameter cases below are
 * that guard.
 */
@QuarkusTest
@QuarkusTestResource(KybBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class RegistrySearchApiIT {

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `a name search returns pickable rows carrying the identifier the customer did not know`() {
        Given {
            queryParam("country", "CZ")
            queryParam("name", "Příklad")
        } When {
            get("/api/v1/kyb/registry/search")
        } Then {
            statusCode(200)
            body("hits", hasSize<Any>(2))
            body("hits[0].scheme", equalTo("CZ_ICO"))
            body("hits[0].identifier", equalTo("45274649"))
            body("tooManyMatches", equalTo(false))
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `the town narrows the result set`() {
        Given {
            queryParam("country", "CZ")
            queryParam("name", "Příklad")
            queryParam("city", "Praha")
        } When {
            get("/api/v1/kyb/registry/search")
        } Then {
            statusCode(200)
            body("hits", hasSize<Any>(1))
            body("hits[0].identifier", equalTo("26185610"))
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `too many matches is a 200 telling the customer to narrow, not an error`() {
        Given {
            queryParam("country", "CZ")
            queryParam("name", "stavby")
        } When {
            get("/api/v1/kyb/registry/search")
        } Then {
            // A 4xx/5xx here would read as "the bank is broken" or "no such company"; the register
            // simply refuses a query above its match cap, and the customer can act on that.
            statusCode(200)
            body("tooManyMatches", equalTo(true))
            body("totalMatches", equalTo(2818))
            body("hits", hasSize<Any>(0))
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `a register without name search is a 404, so the UI can hide the box`() {
        Given {
            queryParam("country", "ZZ")
            queryParam("name", "Anything")
        } When {
            get("/api/v1/kyb/registry/search")
        } Then {
            statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `a missing name is 400, never the 500 a non-null parameter would give`() {
        Given {
            queryParam("country", "CZ")
        } When {
            get("/api/v1/kyb/registry/search")
        } Then {
            statusCode(400)
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `a missing country is 400`() {
        Given {
            queryParam("name", "Příklad")
        } When {
            get("/api/v1/kyb/registry/search")
        } Then {
            statusCode(400)
        }
    }

    @Test
    fun `the search surface is not anonymous`() {
        Given {
            queryParam("country", "CZ")
            queryParam("name", "Příklad")
        } When {
            get("/api/v1/kyb/registry/search")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `schemes advertises which registers can be searched at all`() {
        Given {
            queryParam("country", "CZ")
        } When {
            get("/api/v1/kyb/schemes")
        } Then {
            statusCode(200)
            body("pack.supportsNameSearch", equalTo(true))
        }
    }
}
