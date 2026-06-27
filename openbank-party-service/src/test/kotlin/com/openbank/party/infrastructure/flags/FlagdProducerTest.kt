// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.flags

import com.openbank.libs.flags.FlagdProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FlagdProducerTest {

    @Test
    fun `produces a FlagdProvider FeatureClient from config`() {
        val producer = FlagdProducer().apply {
            flagsUrl = "http://localhost:18013"
            flagsTimeoutMs = 50
        }

        val client = producer.featureClient()

        assertThat(client).isInstanceOf(FlagdProvider::class.java)
    }

    @Test
    fun `fail-static when no flagd sidecar is reachable - flag takes its default`() {
        // Unroutable port: no flagd → FlagdProvider must NOT throw and resolve to the default.
        val client = FlagdProducer().apply {
            flagsUrl = "http://127.0.0.1:1"
            flagsTimeoutMs = 50
        }.featureClient()

        // enabled() defaults to false; a fail-static eval keeps it false rather than throwing.
        assertThat(client.enabled("party-list-enriched")).isFalse()
    }
}
