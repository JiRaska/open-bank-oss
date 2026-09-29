// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.resilience

import jakarta.ws.rs.ProcessingException
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.junit.jupiter.api.Test

/** ADR-0321 D1: the table, pinned. A changed value here is a fleet-wide behaviour change. */
class ResilienceProfilesTest {

    @ResilienceProfile(ResilienceProfiles.MONEY_SYNC)
    @Timeout(ResilienceProfiles.MoneySync.TIMEOUT_MS)
    @Retry(
        maxRetries = ResilienceProfiles.MoneySync.MAX_RETRIES,
        delay = ResilienceProfiles.MoneySync.DELAY_MS,
        jitter = ResilienceProfiles.MoneySync.JITTER_MS,
        retryOn = [RetryableKeyedCallException::class],
        abortOn = [UpstreamCallException::class],
    )
    @CircuitBreaker(
        requestVolumeThreshold = ResilienceProfiles.MoneySync.CB_REQUEST_VOLUME_THRESHOLD,
        failureRatio = ResilienceProfiles.MoneySync.CB_FAILURE_RATIO,
        delay = ResilienceProfiles.MoneySync.CB_DELAY_MS,
        successThreshold = ResilienceProfiles.MoneySync.CB_SUCCESS_THRESHOLD,
    )
    @Bulkhead(ResilienceProfiles.MoneySync.BULKHEAD)
    @Suppress("UnusedPrivateMember")
    private fun annotated() = Unit

    @Test
    fun `the constants are usable as fault-tolerance annotation arguments`() {
        val m = javaClass.getDeclaredMethod("annotated")
        assertThat(m.getAnnotation(ResilienceProfile::class.java).value).isEqualTo("money-sync")
        assertThat(m.getAnnotation(Timeout::class.java).value).isEqualTo(3_000L)
        assertThat(m.getAnnotation(Bulkhead::class.java).value).isEqualTo(20)
    }

    @Test
    fun `the four profiles match the ADR-0321 D1 table`() {
        with(ResilienceProfiles.MoneySync) {
            assertThat(listOf(TIMEOUT_MS, DELAY_MS, JITTER_MS, CB_DELAY_MS)).containsExactly(3_000L, 200L, 100L, 5_000L)
            assertThat(listOf(MAX_RETRIES, CB_REQUEST_VOLUME_THRESHOLD, CB_SUCCESS_THRESHOLD, BULKHEAD))
                .containsExactly(1, 10, 2, 20)
        }
        with(ResilienceProfiles.Read) {
            assertThat(listOf(TIMEOUT_MS, DELAY_MS, JITTER_MS, CB_DELAY_MS)).containsExactly(2_000L, 200L, 100L, 5_000L)
            assertThat(listOf(MAX_RETRIES, CB_REQUEST_VOLUME_THRESHOLD, CB_SUCCESS_THRESHOLD, BULKHEAD))
                .containsExactly(2, 10, 2, 50)
        }
        with(ResilienceProfiles.ExternalScheme) {
            assertThat(listOf(TIMEOUT_MS, DELAY_MS, JITTER_MS, CB_DELAY_MS))
                .containsExactly(10_000L, 1_000L, 500L, 10_000L)
            assertThat(listOf(MAX_RETRIES, CB_REQUEST_VOLUME_THRESHOLD, CB_SUCCESS_THRESHOLD, BULKHEAD))
                .containsExactly(2, 4, 2, 10)
        }
        with(ResilienceProfiles.Batch) {
            assertThat(listOf(TIMEOUT_MS, DELAY_MS, JITTER_MS, CB_DELAY_MS))
                .containsExactly(30_000L, 2_000L, 1_000L, 30_000L)
            assertThat(listOf(MAX_RETRIES, CB_REQUEST_VOLUME_THRESHOLD, BULKHEAD)).containsExactly(3, 10, 5)
        }
        assertThat(ResilienceProfiles.ALL)
            .containsExactlyInAnyOrder("money-sync", "read", "external-scheme", "batch", "custom")
    }

    @Test
    fun `every retry carries jitter`() {
        assertThat(
            listOf(
                ResilienceProfiles.MoneySync.JITTER_MS,
                ResilienceProfiles.Read.JITTER_MS,
                ResilienceProfiles.ExternalScheme.JITTER_MS,
                ResilienceProfiles.Batch.JITTER_MS,
            ),
        ).allMatch { it > 0 }
    }

    @Test
    fun `KeyedCall unwraps a filter decision instead of reclassifying it`() {
        val decided = UpstreamCallException("503")
        assertThat(KeyedCall.classify(keyed = true, e = ProcessingException(decided))).isSameAs(decided)
        assertThat(KeyedCall.classify(keyed = true, e = ProcessingException("reset")))
            .isInstanceOf(RetryableKeyedCallException::class.java)
        assertThat(KeyedCall.classify(keyed = false, e = ProcessingException("reset")))
            .isInstanceOf(UpstreamCallException::class.java)
    }

    @Test
    fun `a non-processing failure propagates unchanged`() {
        val boom = IllegalStateException("boom")
        val thrown = runCatching { KeyedCall.invoke(keyed = true) { throw boom } }.exceptionOrNull()
        assertThat(thrown).isSameAs(boom)
    }
}
