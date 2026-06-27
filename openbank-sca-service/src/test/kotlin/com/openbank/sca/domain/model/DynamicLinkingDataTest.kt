// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DynamicLinkingDataTest {

    @Nested
    inner class Authorises {

        @Test
        fun `all null fields authorise any call with null fields`() {
            val dl = DynamicLinkingData(null, null, null, null, null)
            assertThat(dl.authorises(null, null, null)).isTrue()
        }

        @Test
        fun `matching amount currency and creditor authorise`() {
            val dl = DynamicLinkingData("250.00", "CZK", "CZ6508000000192000145399", "Acme", "INV-1")
            assertThat(dl.authorises("250.00", "CZK", "CZ6508000000192000145399")).isTrue()
        }

        @Test
        fun `amount compares numerically so trailing zeros do not matter`() {
            val dl = DynamicLinkingData("250.0", "CZK", null, null, null)
            assertThat(dl.authorises("250.00", "CZK", null)).isTrue()
            assertThat(dl.authorises("250", "CZK", null)).isTrue()
        }

        @Test
        fun `amount mismatch is rejected`() {
            val dl = DynamicLinkingData("250.00", "CZK", null, null, null)
            assertThat(dl.authorises("250.01", "CZK", null)).isFalse()
        }

        @Test
        fun `currency comparison is case-insensitive`() {
            val dl = DynamicLinkingData("100.00", "czk", null, null, null)
            assertThat(dl.authorises("100.00", "CZK", null)).isTrue()
            assertThat(dl.authorises("100.00", "Czk", null)).isTrue()
        }

        @Test
        fun `currency mismatch is rejected`() {
            val dl = DynamicLinkingData("100.00", "CZK", null, null, null)
            assertThat(dl.authorises("100.00", "EUR", null)).isFalse()
        }

        @Test
        fun `creditor iban is normalised by stripping spaces and uppercasing`() {
            val dl = DynamicLinkingData(null, null, "CZ65 0800 0000 1920 0014 5399", null, null)
            assertThat(dl.authorises(null, null, "CZ6508000000192000145399")).isTrue()
            assertThat(dl.authorises(null, null, "cz6508000000192000145399")).isTrue()
        }

        @Test
        fun `creditor mismatch is rejected`() {
            val dl = DynamicLinkingData(null, null, "CZ6508000000192000145399", null, null)
            assertThat(dl.authorises(null, null, "CZ6508000000192000145400")).isFalse()
        }

        @Test
        fun `null creditorIban on the signed data matches any caller-supplied creditor`() {
            // When the signing data carries no IBAN the field is not bound — e.g. login challenge.
            val dl = DynamicLinkingData("100.00", "CZK", null, null, null)
            assertThat(dl.authorises("100.00", "CZK", "CZ6508000000192000145399")).isTrue()
        }

        @Test
        fun `non-null creditorIban on signed data rejects null caller creditor`() {
            val dl = DynamicLinkingData("100.00", "CZK", "CZ6508000000192000145399", null, null)
            assertThat(dl.authorises("100.00", "CZK", null)).isFalse()
        }

        @Test
        fun `malformed amount string returns false gracefully`() {
            val dl = DynamicLinkingData("not-a-number", "CZK", null, null, null)
            assertThat(dl.authorises("250.00", "CZK", null)).isFalse()
        }
    }
}
