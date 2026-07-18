// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Executable form of the escaping guarantee behind issue #1382: a template variable rendered
 * into an HTML notification body can never smuggle live markup into a customer's mail client.
 */
class HtmlEscapeTest {

    @Test
    fun `escapes the five HTML-significant characters`() {
        assertThat(HtmlEscape.escape("<img src=x onerror=alert(1)>"))
            .isEqualTo("&lt;img src=x onerror=alert(1)&gt;")
        assertThat(HtmlEscape.escape("Tom & Jerry")).isEqualTo("Tom &amp; Jerry")
        assertThat(HtmlEscape.escape("\"quoted\" & 'single'"))
            .isEqualTo("&quot;quoted&quot; &amp; &#39;single&#39;")
    }

    @Test
    fun `an attribute-breakout payload can no longer close the surrounding quote`() {
        // The PASSWORD_RESET template interpolates resetLink into href="...". A raw double quote
        // in the value would close the attribute early and let the rest of the string inject a
        // new attribute or element; escaped, the quote is inert text.
        val payload = "https://example.com/reset?t=abc\" onmouseover=\"alert(1)"
        val escaped = HtmlEscape.escape(payload)
        assertThat(escaped).doesNotContain("\"")
        assertThat(escaped).contains("&quot;")
    }

    @Test
    fun `plain text is unaffected`() {
        assertThat(HtmlEscape.escape("TCK-42")).isEqualTo("TCK-42")
        assertThat(HtmlEscape.escape("")).isEqualTo("")
    }
}
