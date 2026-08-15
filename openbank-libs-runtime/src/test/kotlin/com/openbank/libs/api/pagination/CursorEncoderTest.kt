// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.\n// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.\n
package com.openbank.libs.api.pagination

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CursorEncoderTest {

    @Test
    fun `encode and decode are inverse operations`() {
        val original = UUID.randomUUID().toString()
        val encoded = CursorEncoder.encode(original)
        val decoded = CursorEncoder.decode(encoded)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `encoded value is URL-safe base64 without padding`() {
        val encoded = CursorEncoder.encode("test-value")
        assertThat(encoded).doesNotContain("=")
        assertThat(encoded).doesNotContain("+")
        assertThat(encoded).doesNotContain("/")
    }

    @Test
    fun `encodes empty string`() {
        val encoded = CursorEncoder.encode("")
        val decoded = CursorEncoder.decode(encoded)
        assertThat(decoded).isEmpty()
    }

    @Test
    fun `handles UUID values`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val roundTripped = CursorEncoder.decode(CursorEncoder.encode(uuid))
        assertThat(roundTripped).isEqualTo(uuid)
    }
}
