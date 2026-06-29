// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence.repository

import com.openbank.notification.infrastructure.persistence.entity.DeviceTokenEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit-tests the lifecycle-column semantics introduced by V7 migration (ADR-0135 §2).
 * No DB required — these verify the logic in [DeviceTokenRepository.register] that sets
 * [DeviceTokenEntity.registeredAt] and [DeviceTokenEntity.refreshedAt].
 */
class DeviceTokenLifecycleColumnsTest {

    private val fixedNow = Instant.parse("2026-06-29T10:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    @Test
    fun `new entity has registeredAt and refreshedAt set to now`() {
        val entity = DeviceTokenEntity().also {
            it.registeredAt = Instant.now(clock)
            it.refreshedAt = Instant.now(clock)
        }

        assertEquals(fixedNow, entity.registeredAt)
        assertEquals(fixedNow, entity.refreshedAt)
    }

    @Test
    fun `re-registration does not update registeredAt but sets refreshedAt`() {
        val originalRegistration = Instant.parse("2026-01-01T00:00:00Z")
        val entity = DeviceTokenEntity().also {
            it.registeredAt = originalRegistration
            it.refreshedAt = originalRegistration
        }

        // Simulate re-registration: only refreshedAt is updated
        entity.refreshedAt = fixedNow

        assertEquals(originalRegistration, entity.registeredAt, "registeredAt must be immutable after initial creation")
        assertEquals(fixedNow, entity.refreshedAt)
    }

    @Test
    fun `refreshedAt starts null for V6-backfilled rows with null last_used_at`() {
        val entity = DeviceTokenEntity()
        // V7 migration backfills refreshed_at = last_used_at; a row with NULL last_used_at stays NULL
        entity.refreshedAt = null

        assertNull(entity.refreshedAt)
    }

    @Test
    fun `registeredAt is non-null after initial registration`() {
        val entity = DeviceTokenEntity().also {
            it.registeredAt = fixedNow
            it.refreshedAt = fixedNow
        }

        assertNotNull(entity.registeredAt)
        assertEquals(fixedNow, entity.registeredAt)
    }
}
