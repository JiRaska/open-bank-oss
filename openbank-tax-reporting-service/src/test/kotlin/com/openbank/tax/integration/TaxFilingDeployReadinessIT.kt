// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/**
 * The first boot this service has ever had (#5760): it had never run in any environment, and no
 * test booted it. Drives real HTTP against a real Postgres, so a green run means the application
 * starts with its deployed configuration shape, Flyway applied the schema (a missing table is a
 * 500 on the list route, not a 200), and the routes are registered and guarded.
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_tax_reporting_boot_it")],
)
@QuarkusTestResource(TaxFilingDeployReadinessIT.InMemoryChannels::class)
class TaxFilingDeployReadinessIT {
    private val path = "/api/v1/tax/filings"

    @Test
    fun `anonymous caller is refused`() {
        Given { this } When { get(path) } Then { statusCode(401) }
    }

    @Test
    @TestSecurity(user = "auditor", roles = ["ROLE_AUDITOR"])
    fun `auditor reads filings from the migrated schema`() {
        Given { this } When { get(path) } Then {
            statusCode(200)
            body("size()", equalTo(0))
        }
        Given { this } When { get("$path/overdue") } Then { statusCode(200) }
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `viewer cannot assemble or file a period`() {
        Given { contentType("application/json") } When { post("$path/2026-01/assemble") } Then {
            statusCode(403)
        }
        Given {
            contentType("application/json")
            body("""{"reference":"REF"}""")
        } When { post("$path/2026-01/filed") } Then { statusCode(403) }
    }

    class InMemoryChannels : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("withholding-remitted-in")

        override fun stop() = InMemoryConnector.clear()
    }
}
