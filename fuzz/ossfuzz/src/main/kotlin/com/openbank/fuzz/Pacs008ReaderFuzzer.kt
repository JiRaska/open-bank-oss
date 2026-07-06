// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.openbank.libs.iso20022.Pacs008ParseException
import com.openbank.libs.iso20022.Pacs008Reader

/**
 * Jazzer fuzz target for the ISO 20022 pacs.008 reader — the highest-risk parser in
 * openbank-libs-domain: it consumes XML produced OUTSIDE the trust boundary (clearing /
 * simulator inbound credit transfers).
 *
 * Property: for ANY input, read() either returns a ReceivedCreditTransfer or throws the
 * typed Pacs008ParseException. Any other throwable (XXE-related, StackOverflow on deep
 * nesting, NumberFormatException leaking through, OOM on entity expansion) is a finding.
 */
object Pacs008ReaderFuzzer {
    private val reader = Pacs008Reader()

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val xml = data.consumeRemainingAsString()
        try {
            reader.read(xml)
        } catch (_: Pacs008ParseException) {
            // typed, expected rejection — fine
        }
    }
}
