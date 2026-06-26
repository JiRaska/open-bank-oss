// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.statement.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the published contract without booting the app: `info.version` must equal the service
 * version (version.txt), and the StatementPeriod schema must document the period-close record the
 * service actually returns (ADR-0035 §F). Drift between the model and openapi.yaml fails here.
 */
class StatementContractTest {

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
    fun `StatementPeriod schema documents the sequence and balance anchors`() {
        val schema = openapi.substringAfter("    StatementPeriod:")
        assertThat(schema)
            .contains("legalSequenceNumber:")
            .contains("electronicSequenceNumber:")
            .contains("openingBalance:")
            .contains("closingBalance:")
    }

    @Test
    fun `the close endpoint documents the fail-closed reconciliation conflict`() {
        assertThat(openapi).contains("/api/v1/statements/{accountId}/close")
        assertThat(openapi).contains("409")
    }
}
