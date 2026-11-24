// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fuzz

import com.openbank.libs.identity.RodneCislo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** The fuzzer's semantic oracle must itself be right, and must disagree with a wrong-accepting parser. */
class RodneCisloReferenceTest {
    private val vectors = Path.of("../../openbank-libs-domain/src/test/resources/identifier-spec-vectors/cz-rc.json")

    @Test
    fun `reference agrees with every committed spec vector`() {
        val text = Files.readString(vectors)
        val cases = Regex(""""value":\s*"([^"]*)",\s*"valid":\s*(true|false),\s*"rule":\s*"([^"]*)"""").findAll(text).toList()
        assertTrue(cases.size > 20, "parsed ${cases.size} vectors")
        // the reference is structure only (like the one-argument parse the fuzzer drives): the future-date rule
        // needs a caller-supplied today and is judged by RodneCislo.parse(raw, today) in the conformance test
        cases.filterNot { "[as_of]" in it.groupValues[3] }.forEach { m ->
            val value = m.groupValues[1]
            assertEquals(m.groupValues[2].toBoolean(), RodneCisloReference.isValid(value), "vector $value")
        }
    }

    @Test
    fun `known-positive - numbers the pre-#16749 parser accepted are rejected by the reference`() {
        // +20 overflow in 2003, 9-digit 1985, remainder-10 quirk in 1986, month 41: all stdnum-valid,
        // all never issued. The old consistency-only fuzzer could not flag any of them.
        listOf("0321010001", "850101000", "8601010040", "7641010003").forEach { candidate ->
            val n = if (candidate.length == 10 && candidate != "8601010040") {
                candidate.take(9) + (candidate.take(9).toLong() % 11 % 10)
            } else {
                candidate
            }
            assertFalse(RodneCisloReference.isValid(n), "reference must reject $n")
            assertFalse(RodneCislo.isValid(n), "shared parser must reject $n")
        }
    }

    @Test
    fun `shared parser agrees with the reference across the structured candidate space`() {
        for (yy in 0..99) for (mm in listOf(1, 2, 12, 13, 21, 33, 41, 51, 62, 63, 71, 82, 83)) {
            for (dd in listOf(0, 1, 28, 29, 31)) for (serial in listOf(0, 7, 999)) {
                val head = "%02d%02d%02d%03d".format(yy, mm, dd, serial)
                val rem = head.toLong() % 11
                for (n in listOf(head, head + (if (rem == 10L) 0 else rem), head + ((rem + 1) % 10))) {
                    assertEquals(RodneCisloReference.isValid(n), RodneCislo.isValid(n), "input $n")
                }
            }
        }
    }
}
