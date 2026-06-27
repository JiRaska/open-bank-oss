// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(com.openbank.sepainstant.it.PostgresRedpandaRedisTestResource::class)
class SctInstAccessControlIT {

    @Test
    fun `anonymous cannot submit an SCT Inst payment`(): Unit {
        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body("""{ "debtorAccountId": "${UUID.randomUUID()}", "debtorIban": "CZ00", "debtorName": "A", "creditorIban": "DE00", "creditorName": "B", "creditorBic": "COBADEFFXXX", "amount": 1, "currency": "EUR" }""")
        } When {
            post("/api/v1/sepa-instant")
        } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "viewer-only", roles = ["ROLE_VIEWER"])
    fun `viewer cannot submit an SCT Inst payment`(): Unit {
        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            body("""{ "debtorAccountId": "${UUID.randomUUID()}", "debtorIban": "CZ00", "debtorName": "A", "creditorIban": "DE00", "creditorName": "B", "creditorBic": "COBADEFFXXX", "amount": 1, "currency": "EUR" }""")
        } When {
            post("/api/v1/sepa-instant")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "viewer-only", roles = ["ROLE_VIEWER"])
    fun `viewer can list SCT Inst payments`(): Unit {
        Given {
            contentType("application/json")
        } When {
            get("/api/v1/sepa-instant")
        } Then {
            statusCode(200)
        }
    }

    @Test
    fun `anonymous cannot list SCT Inst payments`(): Unit {
        Given {
            contentType("application/json")
        } When {
            get("/api/v1/sepa-instant")
        } Then {
            statusCode(401)
        }
    }
}
