// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.ledger.infrastructure.schedule

import io.smallrye.config.PropertiesConfigSource
import io.smallrye.config.SmallRyeConfigBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScheduleSwitchTest {

    private fun config(vararg kv: Pair<String, String>) =
        SmallRyeConfigBuilder()
            .withSources(PropertiesConfigSource(mapOf(*kv), "test", 100))
            .build()

    @Test
    fun `off and disabled, in any case and padding, switch a job off`() {
        listOf("off", "OFF", " disabled ", "Disabled").forEach { v ->
            assertThat(ScheduleSwitch.isOff("p", config("p" to v))).describedAs(v).isTrue()
        }
    }

    @Test
    fun `a real cron is on`() {
        assertThat(ScheduleSwitch.isOff("p", config("p" to "0 0 6 * * ?"))).isFalse()
    }

    @Test
    fun `an absent property is the annotation default, which is on`() {
        assertThat(ScheduleSwitch.isOff("p", config())).isFalse()
    }
}
