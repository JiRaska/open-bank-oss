// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.temporal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.temporal.common.converter.DefaultDataConverter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Regression for #2749. Temporal's stock JSON payload converter builds a plain Jackson ObjectMapper,
 * which cannot construct a Kotlin data class — it has no no-arg constructor. campaign-service's
 * journey passes `List<CampaignStep>`, so every workflow task failed with
 * `Cannot construct instance of ... (no Creators, like default constructor, exist)` **after** the
 * send activity had already run: the work happened and the journey still never advanced.
 *
 * The second test is the one that matters — it pins the *reason* the override exists. Without it,
 * someone could drop `registerKotlinModule()` and only this file's first test would fail, with no
 * indication of what the override was for.
 */
class KotlinDataConverterTest {

    data class Step(val order: Int, val template: String, val variables: Map<String, String>)

    private val converter = TemporalClientProducer(
        config = object : TemporalConfig {
            override fun enabled() = true
            override fun serverUrl() = "localhost:7233"
            override fun namespace() = "test"
            override fun taskQueue() = "test"
            override fun metricsEnabled() = false
        },
        meterRegistry = SimpleMeterRegistry(),
    ).kotlinAwareDataConverter()

    @Test
    fun `a Kotlin data class survives a payload round trip`() {
        val step = Step(1, "MARKETING_PRODUCT_OFFER", mapOf("offerTitle" to "Saver"))

        val payload = converter.toPayload(step).orElseThrow()
        val restored = converter.fromPayload(payload, Step::class.java, Step::class.java)

        assertEquals(step, restored)
    }

    @Test
    fun `the stock converter cannot - which is why the override exists`() {
        val step = Step(1, "MARKETING_PRODUCT_OFFER", emptyMap())
        val stock = DefaultDataConverter.newDefaultInstance()

        val payload = stock.toPayload(step).orElseThrow()
        assertThrows<Exception> {
            stock.fromPayload(payload, Step::class.java, Step::class.java)
        }
    }
}
