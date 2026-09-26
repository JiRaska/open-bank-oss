// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.iso20022

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class Camt056BuilderTest {
    private val builder = Camt056Builder()
    private val validator = Iso20022Validator.forSchema(Camt056Builder.SCHEMA_RESOURCE)

    private fun recall(reason: String? = "DUPL") = PaymentCancellationRequest(
        assignmentId = "OB-CXL-0001",
        creationDateTime = OffsetDateTime.of(2026, 6, 22, 10, 21, 0, 0, ZoneOffset.UTC),
        cancellationId = "CXL-0001",
        originalEndToEndId = "E2E-0001",
        originalTransactionId = "TX-0001",
        cancellationReasonCode = reason,
        additionalInfo = reason?.let { "recall: $it" },
    )

    @Test
    fun `a cancellation request validates against the vendored XSD`() {
        val xml = builder.build(recall())
        assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:camt.056.001.08")
        assertThat(xml).contains("<OrgnlEndToEndId>E2E-0001</OrgnlEndToEndId>")
        assertThat(xml).contains("<Cd>DUPL</Cd>")
        assertThat(validator.validate(xml)).isEqualTo(Iso20022ValidationResult.Valid)
    }

    @Test
    fun `a cancellation without a reason still validates`() {
        val xml = builder.build(recall(reason = null))
        assertThat(xml).doesNotContain("CxlRsnInf")
        assertThat(validator.validate(xml)).isEqualTo(Iso20022ValidationResult.Valid)
    }
}
