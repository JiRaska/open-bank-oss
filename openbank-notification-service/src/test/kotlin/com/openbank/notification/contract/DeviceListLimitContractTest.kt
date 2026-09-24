// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.contract

import com.openbank.notification.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class DeviceListLimitContractTest {
    @Test
    fun `device list documents its optional bounded read`() {
        val openapi = File("src/main/resources/openapi.yaml").readText()
        val deviceList = openapi.substringAfter("  /api/v1/devices:").substringBefore("  /api/v1/devices/{deviceId}:")
        assertThat(deviceList).contains("operationId: listDevices", "- name: limit", "maximum: 200")

        val version = Regex("""(?m)^  version: (\d+)\.(\d+)\.(\d+)$""").find(openapi)
        assertThat(version).isNotNull()
        assertThat(version!!.groupValues[1].toInt()).isEqualTo(1)
        assertThat(version.groupValues[2].toInt()).isGreaterThanOrEqualTo(10)
    }

    @Test
    fun `anonymous device list is rejected with 401 before the source query`() {
        given()
            .queryParam("partyId", UUID.randomUUID())
            .queryParam("limit", 21)
            .`when`()
            .get("/api/v1/devices")
            .then()
            .statusCode(401)
    }
}
