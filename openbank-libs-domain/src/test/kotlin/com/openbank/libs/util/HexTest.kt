// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.util

import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [Hex.lower] must be byte-identical to the per-byte idiom it replaced across the libs
 * (`joinToString("") { "%02x".format(it) }`), not merely "valid hex": the strings it renders are
 * persisted hashes and wire fields, so the property is equality with the OLD rendering over
 * random arrays, alongside the fixed edges a hex renderer gets wrong.
 */
class HexTest {

    /** The exact idiom every call site used before [Hex] existed. */
    private fun oldIdiom(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** The masked variant `BlindIndex`/`BlindIndexTokenizer` used; same output, kept as a second oracle. */
    private fun oldMaskedIdiom(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `equals the old per-byte idiom over random byte arrays`(): Unit = runBlocking {
        checkAll(2_000, Arb.byteArray(Arb.int(0..96), Arb.byte())) { bytes ->
            val got = Hex.lower(bytes)
            assertThat(got).isEqualTo(oldIdiom(bytes))
            assertThat(got).isEqualTo(oldMaskedIdiom(bytes))
        }
    }

    @Test
    fun `edges - empty, leading zero, sign bit, every byte value`() {
        assertThat(Hex.lower(ByteArray(0))).isEmpty()
        assertThat(Hex.lower(byteArrayOf(0x00))).isEqualTo("00")
        assertThat(Hex.lower(byteArrayOf(0x0f))).isEqualTo("0f")
        assertThat(Hex.lower(byteArrayOf(-1))).isEqualTo("ff")
        assertThat(Hex.lower(byteArrayOf(-128))).isEqualTo("80")
        val all = ByteArray(256) { it.toByte() }
        assertThat(Hex.lower(all)).isEqualTo(oldIdiom(all)).hasSize(512).matches("[0-9a-f]+")
    }
}
