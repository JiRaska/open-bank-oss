// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.schedule

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StubResolveTest {
    @Test
    fun `no stub is placed by the roll direction`() {
        assertThat(StubType.resolve(null, RollDirection.FROM_START)).isEqualTo(StubType.SHORT_FINAL)
        assertThat(StubType.resolve(null, RollDirection.FROM_END)).isEqualTo(StubType.SHORT_INITIAL)
    }

    @Test
    fun `an explicit stub wins over either direction`() {
        for (stub in StubType.entries) {
            for (direction in RollDirection.entries) {
                assertThat(StubType.resolve(stub, direction)).isEqualTo(stub)
            }
        }
    }

    @Test
    fun `the wire spelling of no stub is NONE`() {
        assertThat(StubType.NO_STUB).isEqualTo("NONE")
        assertThat(StubType.entries.map { it.name }).doesNotContain(StubType.NO_STUB)
    }
}
