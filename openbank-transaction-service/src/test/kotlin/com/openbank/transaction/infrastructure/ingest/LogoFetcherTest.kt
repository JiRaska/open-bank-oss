// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.ingest

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * These are about what [LogoFetcher] REFUSES to connect to.
 *
 * The success path of an SSRF guard is indistinguishable from having no guard at all — a logo
 * downloads either way — so every test here names a URL that must never produce a request, and the
 * assertion is that no request happened. A guard proven only by its happy path is decoration.
 *
 * Each case is a real bypass rather than a hypothetical: the cloud metadata address, a loopback
 * name, a redirect, and a plaintext scheme are the four shapes an SSRF report is actually written
 * about.
 */
class LogoFetcherTest {

    private fun fetcher(hosts: String?) = LogoFetcher(Optional.ofNullable(hosts))

    /**
     * The default. A deployment that has not thought about this feature must not acquire it by
     * upgrading — an allowlist is a decision, and its absence is a decision too.
     */
    @Test
    fun `with no allowlist configured the feature is off and every URL is refused`() {
        val off = fetcher(null)

        assertThat(off.isEnabled()).isFalse()
        assertThatThrownBy { off.fetch("https://upload.wikimedia.org/logo.png") }
            .isInstanceOf(LogoFetcher.RefusedException::class.java)
            .hasMessageContaining("logo fetching is disabled")
    }

    @Test
    fun `an empty allowlist is the same as none`() {
        assertThat(fetcher("   ").isEnabled()).isFalse()
        assertThat(fetcher(",, ,").isEnabled()).isFalse()
    }

    /**
     * The attack this endpoint exists to not be. `169.254.169.254` is the cloud instance metadata
     * service: reaching it at all hands over role credentials, and the response never has to come
     * back as an image for that to have happened.
     */
    @Test
    fun `the cloud metadata address is refused even when its host is allowlisted`() {
        val f = fetcher("169.254.169.254")

        assertThatThrownBy { f.fetch("https://169.254.169.254/latest/meta-data/iam/security-credentials/") }
            .isInstanceOf(LogoFetcher.RefusedException::class.java)
            .hasMessageContaining("non-public address")
    }

    /**
     * An allowlisted NAME that resolves inward is the interesting case: the allowlist is a spelling
     * check unless the resolved address is checked too.
     */
    @Test
    fun `an allowlisted host that resolves to loopback is refused`() {
        val f = fetcher("localhost")

        assertThatThrownBy { f.fetch("https://localhost/logo.png") }
            .isInstanceOf(LogoFetcher.RefusedException::class.java)
            .hasMessageContaining("non-public address")
    }

    @Test
    fun `a private-range address is refused`() {
        val f = fetcher("10.0.0.1")

        assertThatThrownBy { f.fetch("https://10.0.0.1/logo.png") }
            .isInstanceOf(LogoFetcher.RefusedException::class.java)
            .hasMessageContaining("non-public address")
    }

    /**
     * Carrier-grade NAT. The JDK has no predicate for 100.64.0.0/10, which is exactly why it is easy
     * to leave out of a hand-written check and worth a test of its own.
     */
    @Test
    fun `a carrier-grade NAT address is refused`() {
        val f = fetcher("100.64.0.1")

        assertThatThrownBy { f.fetch("https://100.64.0.1/logo.png") }
            .isInstanceOf(LogoFetcher.RefusedException::class.java)
            .hasMessageContaining("non-public address")
    }

    /** IPv6 unique-local, the other range the JDK's `isSiteLocalAddress` does not cover. */
    @Test
    fun `an IPv6 unique-local address is refused`() {
        val f = fetcher("[fd00::1]")

        assertThatThrownBy { f.fetch("https://[fd00::1]/logo.png") }
            .isInstanceOf(LogoFetcher.RefusedException::class.java)
            .hasMessageContaining("non-public address")
    }

    @Test
    fun `a host outside the allowlist is refused before anything is resolved`() {
        val f = fetcher("upload.wikimedia.org")

        assertThatThrownBy { f.fetch("https://evil.example.com/logo.png") }
            .isInstanceOf(LogoFetcher.RefusedException::class.java)
            .hasMessageContaining("not in the configured allowlist")
    }

    /**
     * Plaintext is refused for two reasons that both matter: the fetch is interceptable, and http is
     * the shape most internal-only endpoints take.
     */
    @Test
    fun `a non-https URL is refused`() {
        val f = fetcher("upload.wikimedia.org")

        assertThatThrownBy { f.fetch("http://upload.wikimedia.org/logo.png") }
            .isInstanceOf(LogoFetcher.RefusedException::class.java)
            .hasMessageContaining("only https")
    }

    @Test
    fun `a file URL is refused`() {
        val f = fetcher("upload.wikimedia.org")

        assertThatThrownBy { f.fetch("file:///etc/passwd") }
            .isInstanceOf(LogoFetcher.RefusedException::class.java)
    }

    @Test
    fun `a malformed URL is refused as a bad request, not a crash`() {
        val f = fetcher("upload.wikimedia.org")

        assertThatThrownBy { f.fetch("h ttp://not a url") }
            .isInstanceOf(LogoFetcher.RefusedException::class.java)
    }

    @Test
    fun `the allowlist is reported so an operator does not have to guess it`() {
        val f = fetcher("upload.wikimedia.org, commons.wikimedia.org")

        assertThat(f.isEnabled()).isTrue()
        assertThat(f.allowedHosts()).containsExactlyInAnyOrder("upload.wikimedia.org", "commons.wikimedia.org")
    }

    /**
     * Hosts are matched case-insensitively, because DNS is and an operator's typing is not.
     *
     * Asserted on the parsed allowlist rather than by attempting a fetch: a test that has to resolve
     * a real name fails on a machine with no DNS, and would then be reporting the network rather
     * than the rule.
     */
    @Test
    fun `host matching is case-insensitive`() {
        assertThat(fetcher("Upload.WikiMedia.org").allowedHosts()).containsExactly("upload.wikimedia.org")
    }

    /**
     * A literal address is checked the same way a name is. Written with a public literal so the case
     * that must PASS the address check is exercised too — otherwise every test here could pass
     * against a guard that refuses everything unconditionally, which is a guard that also breaks the
     * feature.
     */
    @Test
    fun `a public literal address passes the address check and fails later, at the connection`() {
        val f = fetcher("93.184.216.34")

        // Refused eventually (nothing answers), but NOT for being non-public or unlisted — those are
        // the two rejections that would mean the guard is over-broad.
        assertThatThrownBy { f.fetch("https://93.184.216.34/logo.png") }
            .isInstanceOf(LogoFetcher.RefusedException::class.java)
            .hasMessageNotContaining("non-public address")
            .hasMessageNotContaining("not in the configured allowlist")
    }
}
