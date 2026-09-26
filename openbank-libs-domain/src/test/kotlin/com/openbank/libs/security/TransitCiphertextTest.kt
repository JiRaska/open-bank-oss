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
}
