// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.observability

import com.openbank.notification.domain.model.EmailSendOutcome
import com.openbank.notification.domain.model.NotificationTemplate
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Issue #4737 — the EMAIL counter, and the label split the mocked-channel alert depends on. */
class EmailMetricsAdapterTest {

    private val registry = SimpleMeterRegistry()
    private val adapter = EmailMetricsAdapter(registry)

    @Test
    fun `accepted and mocked land on separate series`() {
        adapter.recordSend(NotificationTemplate.SCA_APPROVAL, EmailSendOutcome.ACCEPTED)
        adapter.recordSend(NotificationTemplate.SCA_APPROVAL, EmailSendOutcome.MOCKED)

        val counters = registry.find("openbank.notification.email.sends").counters()
        assertThat(counters).hasSize(2)
        assertThat(counters.map { it.id.getTag("outcome") }).containsExactlyInAnyOrder("ACCEPTED", "MOCKED")

        // The point of the split: an environment with the mailer mocked must not add to ACCEPTED,
        // which is the series the "email channel is a no-op" alert reads. Sharing one series is
        // what made the identical push defect invisible.
        val accepted = registry.find("openbank.notification.email.sends").tag("outcome", "ACCEPTED").counter()
        assertThat(accepted?.count()).isEqualTo(1.0)
    }

    @Test
    fun `the mocked series exists and is non-zero, so the no-op is alertable rather than silent`() {
        adapter.recordSend(NotificationTemplate.SCA_APPROVAL, EmailSendOutcome.MOCKED)

        val mocked = registry.find("openbank.notification.email.sends").tag("outcome", "MOCKED").counter()
        assertThat(mocked).isNotNull
        assertThat(mocked?.count()).isEqualTo(1.0)
        // Nothing fails when this increments — that is the whole reason an error rate cannot
        // catch this state, and why the counter has to exist.
        assertThat(registry.find("openbank.notification.email.sends").tag("outcome", "FAILED").counter()).isNull()
    }

    @Test
    fun `the template label survives, so a lost SCA mail is distinguishable from a lost marketing one`() {
        adapter.recordSend(NotificationTemplate.SCA_APPROVAL, EmailSendOutcome.MOCKED)
        adapter.recordSend(NotificationTemplate.CONSENT_GRANTED, EmailSendOutcome.MOCKED)

        val templates = registry.find("openbank.notification.email.sends")
            .tag("outcome", "MOCKED").counters().map { it.id.getTag("template") }
        assertThat(templates).containsExactlyInAnyOrder("SCA_APPROVAL", "CONSENT_GRANTED")
    }

    @Test
    fun `every series carries the service tag`() {
        EmailSendOutcome.entries.forEach { adapter.recordSend(NotificationTemplate.SCA_APPROVAL, it) }

        val counters = registry.find("openbank.notification.email.sends").counters()
        assertThat(counters).hasSize(EmailSendOutcome.entries.size)
        assertThat(counters.map { it.id.getTag("service") }).allMatch { it == "notification" }
    }

    @Test
    fun `an absent registry is a no-op rather than a crash`() {
        val nullRegistryAdapter = EmailMetricsAdapter(null)
        nullRegistryAdapter.recordSend(NotificationTemplate.SCA_APPROVAL, EmailSendOutcome.MOCKED)
    }
}
