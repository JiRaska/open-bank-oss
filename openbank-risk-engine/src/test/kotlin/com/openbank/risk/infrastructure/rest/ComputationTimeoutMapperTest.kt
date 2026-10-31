// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.risk.domain.valuation.ComputationTimeoutException
import com.openbank.risk.domain.valuation.Deadline
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class ComputationTimeoutMapperTest {

    @Test
    fun `a deadline stops work only once its budget has passed`() {
        var now = 0L
        val deadline = Deadline("test", Duration.ofMillis(5)) { now }
        now = Duration.ofMillis(5).toNanos()
        deadline.check() // exactly at the budget: still allowed
        now += 1
        assertThatThrownBy { deadline.check() }.isInstanceOf(ComputationTimeoutException::class.java)
    }

    @Test
    fun `a timeout answers 503 with a stable error code`() {
        val response = ExceptionMappers().computationTimeout(
            ComputationTimeoutException("pre-trade pricing", Duration.ofSeconds(5)),
        )
        assertThat(response.status).isEqualTo(503)
        @Suppress("UNCHECKED_CAST")
        val body = response.entity as Map<String, Any>
        assertThat(body["error"]).isEqualTo("COMPUTATION_TIMEOUT")
        assertThat(body["budgetMillis"]).isEqualTo(5000L)
    }
}
