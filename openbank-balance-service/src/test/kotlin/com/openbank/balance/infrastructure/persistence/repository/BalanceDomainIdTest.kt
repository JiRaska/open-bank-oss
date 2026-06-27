// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.persistence.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression guard for the balance entity→domain id mapping. The balances table
 * has a BIGSERIAL surrogate PK and no UUID business key; the previous mapping
 * parsed that surrogate as a UUID and threw "Invalid UUID string", which surfaced
 * as a 400 on every balance write (initialize/credit/debit). The domain id is now
 * derived deterministically from the natural key (accountId, currency).
 */
class BalanceDomainIdTest {

    private val accountId = UUID.fromString("15fb75bd-0ffb-4206-adb8-c9e857e77412")

    @Test
    fun `is deterministic for the same natural key`() {
        assertEquals(
            balanceDomainId(accountId, "CZK"),
            balanceDomainId(accountId, "CZK"),
        )
    }

    @Test
    fun `differs per currency for the same account`() {
        assertNotEquals(
            balanceDomainId(accountId, "CZK"),
            balanceDomainId(accountId, "EUR"),
        )
    }

    @Test
    fun `differs per account for the same currency`() {
        assertNotEquals(
            balanceDomainId(accountId, "CZK"),
            balanceDomainId(UUID.randomUUID(), "CZK"),
        )
    }
}
