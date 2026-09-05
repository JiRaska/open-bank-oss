// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class DelegatedSpendActivationContractTest {
    private val application = File("src/main/resources/application.yaml").readText()
    private val overrideFile = File(
        "../openbank-infra/gitops/components/payments/domestic-payment-service-msg-override.yaml",
    )
    private val rollout = File("../openbank-infra/gitops/components/payments/payments-services.yaml").readText()
    private val acls = File("../openbank-infra/gitops/components/payments/kafka-scheme-accepted-acl.yaml").readText()
    private val dlqs = File("../openbank-infra/gitops/components/kafka/kafka-dlq-topics.yaml").readText()
    private val threatModel = File("../docs/threat-models/openbank-domestic-payment.md").readText()

    @Test
    fun `consumer and finalizer are independently default off`() {
        assertThat(application).contains(
            "enabled: \${DOMESTIC_DELEGATED_SPEND_CONSUMER_ENABLED:false}",
            "enabled: \${DOMESTIC_DELEGATED_SPEND_FINALIZER_ENABLED:false}",
        )
        assertThat(overrideFile).doesNotExist()
    }

    @Test
    fun `activation artifact is absent before a compatible image is pinned`() {
        assertThat(overrideFile).doesNotExist()
        val domesticRollout = rollout.substringAfter("# domestic-payment  (port 8116)")
            .substringBefore("# sepa-instant")
        assertThat(domesticRollout).doesNotContain(
            "domestic-payment-service-msg-override",
            "DOMESTIC_DELEGATED_SPEND_CONSUMER_ENABLED",
            "DOMESTIC_DELEGATED_SPEND_FINALIZER_ENABLED",
        )
    }

    @Test
    fun `workload endpoint stays blocked until account authority proof is green`() {
        assertThat(overrideFile).doesNotExist()
        assertThat(threatModel).contains(
            "Account authority proof is an activation gate",
            "canonical-IBAN-to-account-id and account-id-to-owner checks are green",
            "must produce no payment, outbox event or workflow start",
        )
    }

    @Test
    fun `deny-by-default Kafka identity can read source and write only its explicit DLQ`() {
        assertThat(acls).contains(
            "name: openbank.delegation.spend-reservation-state",
            "name: openbank.dlq.domestic-payment.delegated-spend-reservation-state-in",
            "name: domestic-payment-delegated-spend-binding",
        )
        assertThat(dlqs).contains(
            "name: openbank.dlq.domestic-payment.delegated-spend-reservation-state-in",
            "retention.ms: 2592000000",
        )
    }
}
