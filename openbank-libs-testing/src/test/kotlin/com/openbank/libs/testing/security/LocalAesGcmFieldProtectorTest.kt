// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.security

import com.openbank.libs.security.FieldProtectionException
import com.openbank.libs.security.TransitCiphertext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

class LocalAesGcmFieldProtectorTest {

    private val k1 = ByteArray(32) { 1 }
    private val k2 = ByteArray(32) { 2 }
    private val pepper = "p".toByteArray()

    @Test fun `round-trips with aad and rewraps to the latest version`() {
        val old = LocalAesGcmFieldProtector(mapOf(1 to k1), pepper)
        val ct = old.encrypt("x".toByteArray(), "row:1".toByteArray())
        assertThat(ct).startsWith("vault:v1:")
        val rotated = LocalAesGcmFieldProtector(mapOf(1 to k1, 2 to k2), pepper)
        val v2 = rotated.rewrap(ct, "row:1".toByteArray())
        assertThat(v2).startsWith("vault:v2:")
        assertThat(rotated.decrypt(v2, "row:1".toByteArray()).decodeToString()).isEqualTo("x")
    }

    @Test fun `wrong key, wrong aad and unknown version fail closed`() {
        val ct = LocalAesGcmFieldProtector(mapOf(1 to k1), pepper).encrypt("x".toByteArray(), "a".toByteArray())
        assertThatThrownBy { LocalAesGcmFieldProtector(mapOf(1 to k2), pepper).decrypt(ct, "a".toByteArray()) }
            .isInstanceOf(FieldProtectionException::class.java)
        assertThatThrownBy { LocalAesGcmFieldProtector(mapOf(1 to k1), pepper).decrypt(ct, "b".toByteArray()) }
            .isInstanceOf(FieldProtectionException::class.java)
        assertThatThrownBy { LocalAesGcmFieldProtector(mapOf(2 to k1), pepper).decrypt(ct, "a".toByteArray()) }
            .isInstanceOf(FieldProtectionException::class.java)
    }

    @Test fun `every encryption draws a fresh 96-bit nonce`() {
        val p = LocalAesGcmFieldProtector(mapOf(1 to k1), pepper)
        val nonces = (1..2_000).map {
            val raw = Base64.getDecoder().decode(TransitCiphertext.parse(p.encrypt("same".toByteArray())).payload)
            raw.copyOfRange(0, 12).toList()
        }
        assertThat(nonces.toSet()).hasSize(nonces.size)
    }
}
