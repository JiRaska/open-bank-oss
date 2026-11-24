// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.libs.market

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class BookingContextTest {
    @Test
    fun `a booking context names the legal bank and establishment independently of host and mode`() {
        val bank = UUID.randomUUID()
        val establishment = UUID.randomUUID()
        val branch = BookingContext(bank, establishment, "AT", "BRANCH")
        assertEquals(bank, branch.bankId)
        assertEquals(establishment, branch.establishmentId)
        assertEquals("FOS", branch.copy(hostCountry = "SK", mode = "FOS").mode)
        assertEquals("BRANCH", branch.mode)
        assertThrows(IllegalArgumentException::class.java) { branch.copy(hostCountry = "ZZ") }
        assertThrows(IllegalArgumentException::class.java) { branch.copy(hostCountry = "CZ") }
        assertThrows(IllegalArgumentException::class.java) { branch.copy(mode = "SUBSIDIARY") }
    }
}
