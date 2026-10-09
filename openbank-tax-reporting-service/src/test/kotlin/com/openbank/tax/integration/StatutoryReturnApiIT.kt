// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/** Drives the published routes through HTTP, CDI security, Flyway and the unbound source adapter. */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_api_it")],
)
@QuarkusTestResource(StatutoryReturnMessagingTestResource::class)
class StatutoryReturnApiIT {
    private val path = "/api/v1/statutory-returns"
    private val absentId = "00000000-0000-0000-0000-000000000099"
    private val assembleBody = """{
        "catalogueId":"cz-pension-cnb",
        "returnCode":"PSP10-12-PS",
        "entityId":"company",
        "period":"2025-01"
    }"""

    @Test
    fun `anonymous caller cannot read the regulatory capability`() {
        Given { this } When { get("$path/capability") } Then {
            statusCode(401)
        }
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `viewer sees the served catalogue and unavailable capabilities`() {
        Given { this } When { get("$path/catalogues") } Then {
            statusCode(200)
            body("[0].id", equalTo("cz-pension-cnb"))
        }
        Given { this } When { get("$path/capability") } Then {
            statusCode(200)
            body("dataSourceAvailable", equalTo(false))
            body("wireFormatAvailable", equalTo(false))
        }
        Given { this } When { get("$path/breaches") } Then {
            statusCode(503)
            body("error", containsString("reporting start"))
        }
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `viewer cannot mutate returns at any lifecycle route`() {
        Given {
            contentType("application/json")
            body(assembleBody)
        } When { post("$path/assemble") } Then {
            statusCode(403)
        }
        Given { contentType("application/json") } When { post("$path/$absentId/approve") } Then {
            statusCode(403)
        }
        Given {
            contentType("application/json")
            body("""{"reference":"REF"}""")
        } When {
            post("$path/$absentId/submitted")
        } Then {
            statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `operator reaches assembly but unbound pension figures fail closed`() {
        Given {
            contentType("application/json")
            body(assembleBody)
        } When { post("$path/assemble") } Then {
            statusCode(503)
            body("error", containsString("No data source is bound"))
        }
    }
}
