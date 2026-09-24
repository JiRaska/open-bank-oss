// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

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
}
