// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The closed allow-list for the value that decides where a tapped push navigates the customer's
 * app. It is attacker-reachable (a producer sets it), so the interesting cases are the near
 * misses: a prefix that looks canonical but is not, and an id that parses as a UUID while not
 * being the canonical rendering of one.
 */
class MobileDeepLinkTest {

    @Test
    fun `a null deep link is allowed - most notifications carry none`() {
        assertThat(MobileDeepLink.isAllowed(null)).isTrue()
    }

    @Test
    fun `each fixed route is allowed exactly as written`() {
        listOf(
            "openbank://home",
            "openbank://savings",
            "openbank://cards",
            "openbank://payments",
            "openbank://products",
        ).forEach { assertThat(MobileDeepLink.isAllowed(it)).`as`(it).isTrue() }
    }

    @Test
    fun `a non-bank scheme is refused`() {
        assertThat(MobileDeepLink.isAllowed("https://evil.example/steal")).isFalse()
        assertThat(MobileDeepLink.isAllowed("javascript:alert(1)")).isFalse()
        assertThat(MobileDeepLink.isAllowed("file:///etc/passwd")).isFalse()
    }

    @Test
    fun `an unlisted bank route is refused - the list is an allow-list, not a scheme check`() {
        assertThat(MobileDeepLink.isAllowed("openbank://admin")).isFalse()
        assertThat(MobileDeepLink.isAllowed("openbank://home/extra")).isFalse()
        assertThat(MobileDeepLink.isAllowed("")).isFalse()
    }

    @Test
    fun `a delegation detail route with a canonical UUID is allowed`() {
        val id = UUID.randomUUID()

        assertThat(MobileDeepLink.isAllowed("openbank://delegations/$id")).isTrue()
    }

    @Test
    fun `a delegation route whose id is not CANONICAL is refused`() {
        // UUID.fromString is famously lenient — it accepts an upper-cased and even a short-group
        // rendering. Only the round-trip equality check rejects these.
        val id = UUID.randomUUID()

        assertThat(MobileDeepLink.isAllowed("openbank://delegations/${id.toString().uppercase()}")).isFalse()
        assertThat(MobileDeepLink.isAllowed("openbank://delegations/1-1-1-1-1")).isFalse()
    }

    @Test
    fun `a delegation route with a non-UUID id is refused rather than throwing`() {
        assertThat(MobileDeepLink.isAllowed("openbank://delegations/../../admin")).isFalse()
        assertThat(MobileDeepLink.isAllowed("openbank://delegations/")).isFalse()
        assertThat(MobileDeepLink.isAllowed("openbank://delegations/all")).isFalse()
    }

    @Test
    fun `the delegation prefix must be exact - a lookalike host is refused`() {
        val id = UUID.randomUUID()

        assertThat(MobileDeepLink.isAllowed("openbank://delegationsX/$id")).isFalse()
        assertThat(MobileDeepLink.isAllowed("Openbank://delegations/$id")).isFalse()
    }
}
