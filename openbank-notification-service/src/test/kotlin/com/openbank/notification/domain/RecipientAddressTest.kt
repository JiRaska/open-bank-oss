// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The one case this predicate exists for is the first one: a party UUID must not read as an
 * address (issue #3581). Every producer on `openbank.notification.requests` puts a party id in
 * `recipient`, and for the whole life of the service that string went to the mailer as the SMTP
 * envelope — an assertion that the recipient parses as an address would have failed from the
 * first commit, which is exactly why one lives here now.
 *
 * Addresses below are synthetic (`example.com`, an RFC 2606 reserved domain).
 */
class RecipientAddressTest {

    @Test
    fun `a party UUID is not an address`() {
        assertThat(RecipientAddress.isEmailAddress(UUID.randomUUID().toString())).isFalse()
    }

    @Test
    fun `ordinary addresses are accepted`() {
        for (candidate in listOf("a.b@example.com", "party+campaign@sub.example.co.uk", " spaced@example.com ")) {
            assertThat(RecipientAddress.isEmailAddress(candidate))
                .withFailMessage("expected %s to be treated as an address", candidate)
                .isTrue()
        }
    }

    @Test
    fun `non-addresses are rejected`() {
        for (candidate in listOf(null, "", "   ", "no-at-sign", "@example.com", "two@@example.com", "a@b", "a@.com")) {
            assertThat(RecipientAddress.isEmailAddress(candidate))
                .withFailMessage("expected %s to be rejected", candidate)
                .isFalse()
        }
    }
}
