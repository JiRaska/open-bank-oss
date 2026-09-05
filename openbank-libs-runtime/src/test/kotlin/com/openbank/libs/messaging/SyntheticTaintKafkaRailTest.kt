// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.messaging

import com.openbank.libs.synthetic.SyntheticTaint
import com.openbank.libs.web.MDC_SYNTHETIC
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jboss.logging.MDC
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Both polarities of the Kafka read rail, plus the leak cases.
 *
 * Deliberately NOT the proof that the rail survives a reactive chain — a unit test runs the block
 * on the calling thread, where MDC alone would pass. That claim is only decidable against a real
 * Vert.x/Panache chain and is made by `DelegatedSpendReservationTaintIT` in
 * `openbank-domestic-payment`.
 */
class SyntheticTaintKafkaRailTest {

    @AfterEach
    fun clearMdc() = MDC.remove(MDC_SYNTHETIC)

    @Test
    fun `a tainted header sets both rails for the duration of the block`(): Unit = runBlocking {
        val seen = mutableListOf<Boolean>()
        SyntheticTaintKafkaRail.withTaintFrom(mapOf(SyntheticTaint.KAFKA_HEADER to "true")) {
            seen += SyntheticTaintKafkaRail.mdcTainted()
            seen += SyntheticTaintKafkaRail.baggageTainted()
            seen += SyntheticTaintKafkaRail.currentlyTainted()
        }
        assertThat(seen).containsExactly(true, true, true)
    }

    @Test
    fun `an absent header leaves both rails real`(): Unit = runBlocking {
        SyntheticTaintKafkaRail.withTaintFrom(emptyMap()) {
            assertThat(SyntheticTaintKafkaRail.mdcTainted()).isFalse()
            assertThat(SyntheticTaintKafkaRail.baggageTainted()).isFalse()
        }
    }

    @Test
    fun `header casing does not decide the taint`(): Unit = runBlocking {
        SyntheticTaintKafkaRail.withTaintFrom(mapOf("X-OpenBank-Synthetic" to "TRUE")) {
            assertThat(SyntheticTaintKafkaRail.currentlyTainted()).isTrue()
        }
    }

    @Test
    fun `a permissive-looking value is REAL, not synthetic`(): Unit = runBlocking {
        listOf("1", "yes", "on", "TRUE!", "", " ").forEach { value ->
            SyntheticTaintKafkaRail.withTaintFrom(mapOf(SyntheticTaint.KAFKA_HEADER to value)) {
                assertThat(SyntheticTaintKafkaRail.currentlyTainted())
                    .describedAs("value '%s' must not taint", value)
                    .isFalse()
            }
        }
    }

    @Test
    fun `an untainted record clears a rail leaked onto this thread`(): Unit = runBlocking {
        MDC.put(MDC_SYNTHETIC, "true")
        SyntheticTaintKafkaRail.withTaintFrom(emptyMap()) {
            assertThat(SyntheticTaintKafkaRail.currentlyTainted()).isFalse()
        }
        // ...and hands the thread back exactly as it found it.
        assertThat(MDC.get(MDC_SYNTHETIC)).isEqualTo("true")
    }

    @Test
    fun `the rails are torn down after a normal completion`(): Unit = runBlocking {
        SyntheticTaintKafkaRail.withTaintFrom(mapOf(SyntheticTaint.KAFKA_HEADER to "true")) { }
        assertThat(SyntheticTaintKafkaRail.currentlyTainted()).isFalse()
        assertThat(MDC.get(MDC_SYNTHETIC)).isNull()
    }

    @Test
    fun `the rails are torn down when the block throws`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking {
                SyntheticTaintKafkaRail.withTaintFrom(mapOf(SyntheticTaint.KAFKA_HEADER to "true")) {
                    error("handler failed")
                }
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(SyntheticTaintKafkaRail.currentlyTainted()).isFalse()
        assertThat(MDC.get(MDC_SYNTHETIC)).isNull()
    }
}
