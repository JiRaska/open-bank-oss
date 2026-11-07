// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fuzz

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random

/** The CI container executes the same oracle before starting coverage-guided fuzzing. */
@Timeout(30)
class Pacs008ReaderFuzzerTest {
    @Test
    fun `public corpus and malformed XML exercise validator and subsequent valid-message recovery`() {
        val seed = Files.readString(Path.of("corpus/Pacs008ReaderFuzzer/seed1"))
        listOf(seed, "", "<", "<a></b>", "<!DOCTYPE x><x/>", "\u0000", "not XML")
            .forEach(Pacs008ReaderFuzzer::verify)
        val random = Random(0x20022)
        repeat(256) {
            val cut = random.nextInt(seed.length)
            Pacs008ReaderFuzzer.verify(seed.take(cut))
            Pacs008ReaderFuzzer.verify(seed.substring(0, cut) + "&invalid;" + seed.substring(cut))
        }
    }
}
