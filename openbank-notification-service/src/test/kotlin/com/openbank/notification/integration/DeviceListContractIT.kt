// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.notification.integration

import com.openbank.notification.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DeviceListContractIT.AdvisoryAuthzProfile::class)
@TestSecurity(user = "operator@openbank.test", roles = ["ROLE_ADMIN"])
class DeviceListContractIT {
    class AdvisoryAuthzProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("authz.enforce" to "false")
    }

    @Test
    fun `bounded device list keeps items and total response shape`() {
        Given {
            queryParam("partyId", UUID.randomUUID())
            queryParam("limit", 21)
        } When {
            get("/api/v1/devices")
        } Then {
            statusCode(200)
            body("items.size()", equalTo(0))
            body("total", equalTo(0))
        }
    }

    @Test
    fun `device list rejects invalid limit`() {
        Given {
            queryParam("partyId", UUID.randomUUID())
            queryParam("limit", 0)
        } When {
            get("/api/v1/devices")
        } Then {
            statusCode(400)
        }
    }
}
