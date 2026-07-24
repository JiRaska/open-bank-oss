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

    /** ADR-0169 D2: a document-signing SCA challenge is bound to a document hash + ceremony. */
    @Nested
    inner class DocumentBinding {

        @Test
        fun `matching documentSha256 and ceremonyId authorise`() {
            val dl = DynamicLinkingData(null, null, null, null, null, "abc123", "ceremony-1")
            assertThat(dl.authorises(null, null, null, "abc123", "ceremony-1")).isTrue()
        }

        @Test
        fun `documentSha256 comparison is case-insensitive`() {
            val dl = DynamicLinkingData(null, null, null, null, null, "ABC123", "ceremony-1")
            assertThat(dl.authorises(null, null, null, "abc123", "ceremony-1")).isTrue()
        }

        @Test
        fun `a different documentSha256 is rejected — a signature over document A cannot authorise document B`() {
            val dl = DynamicLinkingData(null, null, null, null, null, "abc123", "ceremony-1")
            assertThat(dl.authorises(null, null, null, "def456", "ceremony-1")).isFalse()
        }

        @Test
        fun `a different ceremonyId is rejected even with the same document hash`() {
            val dl = DynamicLinkingData(null, null, null, null, null, "abc123", "ceremony-1")
            assertThat(dl.authorises(null, null, null, "abc123", "ceremony-2")).isFalse()
        }

        @Test
        fun `consuming with a documentSha256 the signed data never carried is rejected`() {
            // A payment (or any non-document) challenge must never authorise a document signature.
            val dl = DynamicLinkingData("100.00", "CZK", null, null, null)
            assertThat(dl.authorises("100.00", "CZK", null, "abc123", "ceremony-1")).isFalse()
        }

        @Test
        fun `consuming a document-bound challenge without supplying the hash is rejected`() {
            // The inverse of the above: a document challenge cannot be spent as a no-op approval.
            val dl = DynamicLinkingData(null, null, null, null, null, "abc123", "ceremony-1")
            assertThat(dl.authorises(null, null, null)).isFalse()
        }
    }

    /** A card-management SCA challenge is bound to one card and one action on it. */
    @Nested
    inner class CardBinding {

        @Test
        fun `matching cardId and cardAction authorise`() {
            val dl = cardLinking("card-1", "LIMIT_INCREASE")
            assertThat(dl.authorises(null, null, null, cardId = "card-1", cardAction = "LIMIT_INCREASE")).isTrue()
        }

        @Test
        fun `cardId comparison is case-insensitive`() {
            val dl = cardLinking("CARD-1", "LIMIT_INCREASE")
            assertThat(dl.authorises(null, null, null, cardId = "card-1", cardAction = "LIMIT_INCREASE")).isTrue()
        }

        @Test
        fun `a different cardId is rejected — approval for card A cannot act on card B`() {
            val dl = cardLinking("card-1", "LIMIT_INCREASE")
            assertThat(dl.authorises(null, null, null, cardId = "card-2", cardAction = "LIMIT_INCREASE")).isFalse()
        }

        @Test
        fun `a different cardAction is rejected — a limit raise cannot reveal the PAN`() {
            val dl = cardLinking("card-1", "LIMIT_INCREASE")
            assertThat(dl.authorises(null, null, null, cardId = "card-1", cardAction = "REVEAL_DETAILS")).isFalse()
        }

        @Test
        fun `consuming a card-bound challenge without supplying the card is rejected`() {
            val dl = cardLinking("card-1", "REVEAL_DETAILS")
            assertThat(dl.authorises(null, null, null)).isFalse()
        }

        @Test
        fun `a payment challenge never authorises a card operation`() {
            val dl = DynamicLinkingData("100.00", "CZK", null, null, null)
            assertThat(dl.authorises("100.00", "CZK", null, cardId = "card-1", cardAction = "LIMIT_INCREASE"))
                .isFalse()
        }

        @Test
        fun `a card challenge never authorises a payment`() {
            val dl = cardLinking("card-1", "LIMIT_INCREASE")
            assertThat(dl.authorises("100.00", "CZK", "CZ6508000000192000145399")).isFalse()
        }

        @Test
        fun `a card challenge never authorises a document signature`() {
            val dl = cardLinking("card-1", "LIMIT_INCREASE")
            assertThat(dl.authorises(null, null, null, "abc123", "ceremony-1")).isFalse()
        }

        @Test
        fun `a document challenge never authorises a card operation`() {
            val dl = DynamicLinkingData(null, null, null, null, null, "abc123", "ceremony-1")
            assertThat(dl.authorises(null, null, null, cardId = "card-1", cardAction = "LIMIT_INCREASE")).isFalse()
        }

        private fun cardLinking(cardId: String, cardAction: String) =
            DynamicLinkingData(null, null, null, null, null, cardId = cardId, cardAction = cardAction)
    }
}
