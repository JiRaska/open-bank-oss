// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.integration

import com.openbank.cardissuance.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.http.ContentType
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Jackson's Kotlin module null-checks a data class's CONSTRUCTOR PARAMETERS; it does not check the
 * ELEMENTS of a collection. So `{"limits": [null]}` deserialises happily into a
 * `List<CategoryLimitInput>` holding a null, the handler dereferences it, and the endpoint answers
 * 500 where 400 belongs.
 *
 * Only a booted-HTTP test can see this: a unit test constructing `CategoryLimitsRequest` in Kotlin
 * cannot produce the null element at all.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class CategoryLimitsNullElementIT {

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `a null element in limits is rejected with 400, not 500`() {
        Given {
            contentType(ContentType.JSON)
            body("""{"limits":[null]}""")
        } When {
            put("/api/v1/cards/${UUID.randomUUID()}/category-limits")
        } Then {
            statusCode(400)
        }
    }
}
