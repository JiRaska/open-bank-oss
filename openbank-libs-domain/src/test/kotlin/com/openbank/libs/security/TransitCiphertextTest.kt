// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import com.openbank.libs.identity.BlindIndex
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TransitCiphertextTest {

    @Test fun `parses and renders the Transit envelope byte-for-byte`() {
        val raw = "vault:v12:AbC+/9=="
        val parsed = TransitCiphertext.parse(raw)
        assertThat(parsed.keyVersion).isEqualTo(12)
        assertThat(parsed.render()).isEqualTo(raw)
    }

    @Test fun `rejects malformed values without echoing them`() {
        listOf(
            "",
            "vault:v0:AA==",
            "vault:vx:AA==",
            "vault:v1:",
            "4111111111111111",
            "vault:v1:AA==\n",
        ).forEach { bad ->
            assertThatThrownBy { TransitCiphertext.parse(bad) }
                .isInstanceOf(FieldProtectionException::class.java)
                .satisfies({ if (bad.isNotEmpty()) assertThat(it.message).doesNotContain(bad) })
        }
    }

    @Test fun `tokenizer delegates to BlindIndex`() {
        assertThat(
            BlindIndexTokenizer("k".toByteArray()).tokenize("v"),
        ).isEqualTo(BlindIndex.compute("k".toByteArray(), "v"))
        assertThatThrownBy { BlindIndexTokenizer(ByteArray(0)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `rejects misplaced padding and oversized payloads`() {
        listOf("vault:v1:A=AA", "vault:v1:=AAA", "vault:v1:AA==AAAA", "vault:v1:AAA", "vault:v1:A===").forEach { bad ->
            assertThatThrownBy { TransitCiphertext.parse(bad) }.isInstanceOf(FieldProtectionException::class.java)
        }
        assertThat(TransitCiphertext.parse("vault:v1:" + "A".repeat(TransitCiphertext.MAX_PAYLOAD_CHARS)).keyVersion)
            .isEqualTo(1)
        assertThatThrownBy {
            TransitCiphertext.parse("vault:v1:" + "A".repeat(TransitCiphertext.MAX_PAYLOAD_CHARS + 4))
        }
            .isInstanceOf(FieldProtectionException::class.java).hasMessageContaining("exceeds")
    }

    @Test fun `domain tokens are HMAC over domain NUL value and separate fields`() {
        val t = BlindIndexTokenizer("k".toByteArray())
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec("k".toByteArray(), "HmacSHA256"))
        }
        val expected = mac.doFinal("pan\u0000v".toByteArray()).joinToString("") { "%02x".format(it) }
        assertThat(t.tokenize("v", "pan")).isEqualTo(expected)
        assertThat(t.tokenize("v", "pan")).isNotEqualTo(t.tokenize("v", "iban")).isNotEqualTo(t.tokenize("v"))
        // Without the separator, ("pa", "nv") and ("pan", "v") would collide.
        assertThat(t.tokenize("nv", "pa")).isNotEqualTo(t.tokenize("v", "pan"))
        assertThatThrownBy { t.tokenize("v", "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { t.tokenize("v", "a\u0000b") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
