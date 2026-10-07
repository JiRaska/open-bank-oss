// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class BusinessPaymentBatchDraftValidationTest {
    private val resource = BusinessPaymentBatchDraftResource(
        BusinessPaymentBatchDraftStore(ObjectMapper()), ObjectMapper(),
    )
    private val first = BusinessPaymentBatchDraftResource.Item(
        UUID.randomUUID(), "123456789", "0800", "Supplier", 125, "CZK",
    )

    @Test
    fun `summary is calculated from validated minor units`() {
        val second = first.copy(itemId = UUID.randomUUID(), amountMinor = 75)
        assertEquals(BusinessPaymentBatchDraftResource.Summary(2, 200), resource.validate(listOf(first, second)))
    }

    @Test
    fun `duplicate item IDs and malformed payment details are refused`() {
        val bad = listOf(
            listOf(first, first.copy()),
            listOf(first.copy(amountMinor = 0)),
            listOf(first.copy(currency = "EUR")),
            listOf(first.copy(creditorBankCode = "abcd")),
            listOf(first.copy(messageForPayee = "x".repeat(141))),
            listOf(first, first.copy(itemId = UUID.randomUUID(), amountMinor = Long.MAX_VALUE)),
            List(101) { first.copy(itemId = UUID.randomUUID()) },
        )
        bad.forEach { items -> assertThrows(IllegalArgumentException::class.java) { resource.validate(items) } }
    }
}
