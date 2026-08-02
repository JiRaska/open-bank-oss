// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.ml

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.openbank.libs.domain.feature.VELOCITY_TXN_COUNT_H1
import com.openbank.libs.domain.feature.VELOCITY_TXN_COUNT_H24
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Same behavioural contract as the superseded `BaselineFraudModelTest` — this adapter is required
 *  to reproduce it byte-for-byte, just via a real ONNX Runtime session (ADR-0139 phase-1b). */
class OnnxFraudModelTest {

    private val model = OnnxFraudModel()

    @AfterEach
    fun cleanup() {
        model.close()
    }

    @Test
    fun `score is bounded in 0_1 and deterministic`() {
        val features = mapOf(VELOCITY_TXN_COUNT_H1.name to 5.0, VELOCITY_TXN_COUNT_H24.name to 12.0)
        val first = model.scoreShadow(features)
        val second = model.scoreShadow(features)
        assertThat(first).isNotNull().isBetween(0.0, 1.0)
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `risk increases monotonically with H1 velocity`() {
        val low = model.scoreShadow(mapOf(VELOCITY_TXN_COUNT_H1.name to 1.0))!!
        val high = model.scoreShadow(mapOf(VELOCITY_TXN_COUNT_H1.name to 25.0))!!
        assertThat(high).isGreaterThan(low)
    }

    @Test
    fun `missing features default to zero velocity (low baseline risk)`() {
        val score = model.scoreShadow(emptyMap())
        assertThat(score).isNotNull().isLessThan(0.05)
    }

    @Test
    fun `load failure degrades to null scores instead of throwing`() {
        val brokenSession = OnnxFraudModel.loadSession(OrtEnvironment.getEnvironment(), "ml/does-not-exist.onnx")
        assertThat(brokenSession).isNull()
    }

    // --- ADR-0141 model-card verification ---

    @Test
    fun `bundled model loads against its own card`() {
        // The committed card pins the bundled model's real sha256, so verification passes even
        // under require-signature.
        val session = OnnxFraudModel.loadSession(
            OrtEnvironment.getEnvironment(),
            "ml/baseline-fraud-v1.onnx",
            "ml/baseline-fraud-v1.card.json",
            requireSignature = true,
        )
        assertThat(session).isNotNull()
        session?.close()
    }

    @Test
    fun `tampered bytes fail the card under require-signature, pass advisory`() {
        val tampered = byteArrayOf(1, 2, 3, 4) // not the pinned sha256
        // require-signature=true -> fail closed (do not serve a model whose bytes do not match the card)
        assertThat(OnnxFraudModel.verifyCard(tampered, "ml/baseline-fraud-v1.card.json", requireSignature = true))
            .isFalse()
        // advisory (false) -> log + proceed, so a card drift never bricks shadow serving
        assertThat(OnnxFraudModel.verifyCard(tampered, "ml/baseline-fraud-v1.card.json", requireSignature = false))
            .isTrue()
    }

    @Test
    fun `a missing card fails closed under require-signature`() {
        val anyBytes = byteArrayOf(9)
        assertThat(OnnxFraudModel.verifyCard(anyBytes, "ml/no-such.card.json", requireSignature = true))
            .isFalse()
    }

    // --- the native library not loading at all ---
    //
    // The cases above cover a missing *resource*, which is an Exception. A platform mismatch is an
    // Error: OrtEnvironment.getEnvironment() System.load()s a bundled .so and throws
    // ExceptionInInitializerError wrapping UnsatisfiedLinkError. That escaped the field initializer,
    // so constructing this bean threw, so CDI failed every injection point of it — and
    // FraudResource.reviewQueue, a read-only analyst query that never touches a model, answered 500
    // on every call in the deployed environment. These two cases fail against that code.

    @Test
    fun `a native library Error leaves the environment null instead of propagating`() {
        mockkStatic(OrtEnvironment::class)
        try {
            every { OrtEnvironment.getEnvironment() } throws
                ExceptionInInitializerError(UnsatisfiedLinkError("libstdc++.so.6: No such file or directory"))

            assertThat(OnnxFraudModel.loadEnvironment()).isNull()
        } finally {
            unmockkStatic(OrtEnvironment::class)
        }
    }

    @Test
    fun `construction survives a native library Error and degrades to null scores`() {
        mockkStatic(OrtEnvironment::class)
        try {
            every { OrtEnvironment.getEnvironment() } throws
                ExceptionInInitializerError(UnsatisfiedLinkError("libstdc++.so.6: No such file or directory"))

            // The constructor must not throw — anything sharing this bean's CDI graph depends on it.
            val degraded = OnnxFraudModel()
            assertThat(degraded.scoreShadow(mapOf(VELOCITY_TXN_COUNT_H1.name to 5.0))).isNull()
            degraded.close()
        } finally {
            unmockkStatic(OrtEnvironment::class)
        }
    }

    @Test
    fun `a linkage Error while creating the session degrades instead of propagating`() {
        val hostile = mockk<OrtEnvironment>()
        every { hostile.createSession(any<ByteArray>(), any<OrtSession.SessionOptions>()) } throws
            UnsatisfiedLinkError("native session creation failed")

        assertThat(OnnxFraudModel.loadSession(hostile, "ml/baseline-fraud-v1.onnx")).isNull()
    }
}
