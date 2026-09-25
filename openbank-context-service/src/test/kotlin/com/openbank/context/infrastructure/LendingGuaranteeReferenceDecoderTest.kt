// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.context.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class LendingGuaranteeReferenceDecoderTest {
    private val decoder = LendingGuaranteeReferenceDecoder(ObjectMapper())
    private val guarantee = UUID.randomUUID()
    private val loan = UUID.randomUUID()
    private val event = UUID.randomUUID().toString()
    private val body = """{"schemaVersion":1,"eventType":"lending.graph.guarantee.approved",
        "guaranteeId":"$guarantee","loanId":"$loan","revision":1,
        "bankScope":"openbank-cz","occurredAt":"2026-09-25T10:00:00Z"}
    """.trimIndent()

    @Test
    fun `accepts exact approved guarantee pointer`() {
        val ref = decoder.decode(body, event, LendingGuaranteeReferenceDecoder.TYPE)
        assertThat(ref.guaranteeId).isEqualTo(guarantee)
        assertThat(ref.loanId).isEqualTo(loan)
        assertThat(ref.revision).isEqualTo(1)
        assertThat(ref.bankScope).isEqualTo("openbank-cz")
    }

    @Test
    fun `rejects malformed duplicate oversized and expanded pointers`() {
        val bad = listOf(
            "{",
            "{}",
            body + "{}",
            " ".repeat(513),
            body.replace("\"revision\":1", "\"revision\":0"),
            body.replace("\"revision\":1", "\"revision\":1,\"revision\":2"),
            body.replace("\"revision\":1", "\"revision\":1,\"partyId\":\"sensitive\""),
            body.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
        )
        bad.forEach {
            assertThatThrownBy {
                decoder.decode(it, event, LendingGuaranteeReferenceDecoder.TYPE)
            }.isInstanceOf(Exception::class.java)
        }
    }

    @Test
    fun `requires canonical event identity and type`() {
        assertThatThrownBy { decoder.decode(body, null, LendingGuaranteeReferenceDecoder.TYPE) }
            .isInstanceOf(Exception::class.java)
        assertThatThrownBy { decoder.decode(body, event, "other") }.isInstanceOf(Exception::class.java)
    }
}
