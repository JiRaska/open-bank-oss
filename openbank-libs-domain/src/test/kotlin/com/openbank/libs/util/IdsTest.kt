// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdsTest {

    @Test
    fun `newId returns UUID version 7`() {
        val id = Ids.newId()
        assertEquals(7, id.version(), "Expected UUID version 7 but got ${id.version()}")
    }

    @Test
    fun `newId returns unique values`() {
        val a = Ids.newId()
        val b = Ids.newId()
        assertNotEquals(a, b)
    }

    @Test
    fun `newId is monotonically ordered within tight loop`() {
        val ids = (1..50).map { Ids.newId() }
        for (i in 1 until ids.size) {
            assertTrue(
                ids[i] > ids[i - 1],
                "UUID at index $i (${ids[i]}) is not greater than index ${i - 1} (${ids[i - 1]})",
            )
        }
    }

    @Test
    fun `newId has correct variant bits`() {
        val id = Ids.newId()
        val variant = id.variant()
        assertEquals(2, variant, "Expected RFC 4122 variant (2) but got $variant")
    }
}
