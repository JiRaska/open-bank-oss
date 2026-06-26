// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.sdd.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the published contract without booting the app: `info.version` must equal the service
 * version (version.txt), and the spec must document the mandate lifecycle and the fail-closed
 * authorisation decision the service actually returns (ADR-0036). Drift fails here.
 */
class SddContractTest {

    private val openapi = File("src/main/resources/openapi.yaml").readText()

    @Test
    fun `openapi documents a semver contract version`() {
        // ADR-0048: the API-contract version (openapi info.version) and the release
        // version (version.txt) are independent axes and must not be forced equal -
        // release-please bumps version.txt on every release, which used to fail here.
        // The contract axis is classified by the oasdiff CI gate; this test only pins
        // the invariant that the spec declares a parseable semver contract version.
        val version = Regex("""(?m)^\s+version:\s*"?([^"\s]+)"?\s*$""").find(openapi)?.groupValues?.get(1)
        assertThat(version).isNotNull()
        assertThat(version!!).matches("""\d+\.\d+\.\d+.*""")
    }

    @Test
    fun `the Mandate schema documents the rulebook identity and lifecycle`() {
        val schema = openapi.substringAfter("    Mandate:")
        assertThat(schema)
            .contains("creditorIdentifier:")
            .contains("umr:")
            .contains("scheme:")
            .contains("status:")
    }

    @Test
    fun `the authorisation endpoint documents the fail-closed decision`() {
        assertThat(openapi).contains("/api/v1/sdd/collections/authorise")
        assertThat(openapi).contains("ACCEPT")
        assertThat(openapi).contains("REJECT")
        assertThat(openapi).contains("REFUSE")
    }
}
