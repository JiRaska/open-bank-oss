// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class DomesticPaymentIdempotencyCutoverContractTest {
    private val application = File("src/main/resources/application.yaml").readText()
    private val rollout = File("../openbank-infra/gitops/components/payments/payments-services.yaml").readText()
    private val runbook = File("../docs/runbooks/svc-domestic-payment.md").readText()

    @Test
    fun `no compatibility flag can make a null fingerprint authoritative`() {
        assertThat(application).doesNotContain("strict-request-fingerprint-enabled")
        assertThat(rollout).doesNotContain("DOMESTIC_IDEMPOTENCY_STRICT_REQUEST_FINGERPRINT_ENABLED")
    }

    @Test
    fun `runbook requires a drained blue-green switch and reconciliation for legacy ambiguity`() {
        val prose = runbook.replace(Regex("\\s+"), " ")
        assertThat(prose).contains(
            "Use a blue/green switch, not a mixed-version rolling interval",
            "currently 15 seconds",
            "Never tell the caller to retry with a new key",
            "operator reconciliation",
        )
    }
}
