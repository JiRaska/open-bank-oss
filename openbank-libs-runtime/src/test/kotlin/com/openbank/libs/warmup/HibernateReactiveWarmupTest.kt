// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.warmup

import com.openbank.libs.storage.ObjectStoreBlobEntity
import com.openbank.libs.storage.PostgresBlobStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The warm-up reads one row of every mapped entity. `ObjectStoreBlobEntity` is mapped in every service
 * that has this module, but its table exists only where the Postgres blob adapter was opted into, so
 * reading it elsewhere would query a table outside the configured storage backend.
 */
class HibernateReactiveWarmupTest {
    private class OrdinaryEntity

    @Test
    fun `the blob entity is not warmed when the Postgres blob adapter is absent`() {
        val asked = mutableListOf<Class<*>>()
        val warm = HibernateReactiveWarmup.shouldWarm(ObjectStoreBlobEntity::class.java) { owner ->
            asked += owner
            false
        }

        assertThat(warm).isFalse()
        assertThat(asked).containsExactly(PostgresBlobStore::class.java)
    }

    @Test
    fun `the blob entity is warmed when the adapter is present (document-service keeps its warm path)`() {
        assertThat(HibernateReactiveWarmup.shouldWarm(ObjectStoreBlobEntity::class.java) { true }).isTrue()
    }

    @Test
    fun `an ordinary entity is always warmed and the adapter question is never asked`() {
        val warm = HibernateReactiveWarmup.shouldWarm(OrdinaryEntity::class.java) { error("must not be asked") }

        assertThat(warm).isTrue()
    }
}
