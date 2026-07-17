// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the published contract for the `opsmessage.compose` / `opsmessage.approval.decide`
 * endpoints (ADR-0176 D2/D5, PR #1368) without booting the app: they shipped with zero
 * `openapi.yaml` update (issue #1387), invisible to `check-api-contract.py` because that gate
 * only classifies a diff for a changed spec — nothing to compare when the spec never moves at
 * all. This test fails if either path (or the version bump that documenting them requires)
 * regresses.
 */
class OperatorMessageContractTest {

    private val openapi = File("src/main/resources/openapi.yaml").readText()

    @Test
    fun `info version documents a semver contract version at or above the additive bump`() {
        // ADR-0048: additive change (new paths, same /v1) => MINOR (or MAJOR) bump within the
        // same URL major. This test only pins that the declared version parses and is at least
        // 1.6.0, the version this documentation shipped under -- it does not re-derive the
        // classification itself, that is check-api-contract.py's job.
        val version = Regex("""(?m)^\s+version:\s*"?([^"\s]+)"?\s*$""").find(openapi)?.groupValues?.get(1)
        assertThat(version).isNotNull()
        val (major, minor) = version!!.split(".").take(2).map { it.toInt() }
        assertThat(major).isEqualTo(1)
        assertThat(minor).isGreaterThanOrEqualTo(6)
    }

    @Test
    fun `opsmessage compose is documented`() {
        assertThat(openapi)
            .contains("/api/v1/notifications/messages:")
            .contains("composeOperatorMessage")
            .contains("ComposeMessageRequest")
    }

    @Test
    fun `opsmessage approval decide is documented`() {
        assertThat(openapi)
            .contains("/api/v1/notifications/approvals/{id}:")
            .contains("decideOperatorMessageApproval")
            .contains("DecideApprovalRequest")
    }
}
