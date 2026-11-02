// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration

/**
 * #13883: an intent older than `openbank.treasury.posting-intent.grace` counts as having no attempt
 * in flight (a coded ledger refusal then proves absence; an operator may close it). That holds only
 * while one ledger call, connect plus read (the client has no retry), ends well inside the grace.
 * Checked at BOOT (a StartupEvent observer, not the lazily created use-case producer), so a bad
 * configuration stops the pod instead of failing the first request that needs the service.
 */
@ApplicationScoped
class PostingIntentGraceCheck(
    @ConfigProperty(name = "openbank.treasury.posting-intent.grace", defaultValue = "PT2M")
    private val grace: Duration,
    @ConfigProperty(name = "quarkus.rest-client.ledger-service.connect-timeout", defaultValue = "2000")
    private val connectMs: Long,
    @ConfigProperty(name = "quarkus.rest-client.ledger-service.read-timeout", defaultValue = "5000")
    private val readMs: Long,
) {
    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) =
        requireGraceAboveLedgerTimeout(grace, connectMs, readMs)
}

/** Refuses a grace shorter than [GRACE_TO_LEDGER_CALL_FACTOR] whole ledger calls. */
internal fun requireGraceAboveLedgerTimeout(grace: Duration, connectMs: Long, readMs: Long) {
    val call = Duration.ofMillis(connectMs + readMs)
    require(grace >= call.multipliedBy(GRACE_TO_LEDGER_CALL_FACTOR)) {
        "openbank.treasury.posting-intent.grace ($grace) must be at least $GRACE_TO_LEDGER_CALL_FACTOR x the " +
            "ledger client's connect + read timeout ($call)"
    }
}

private const val GRACE_TO_LEDGER_CALL_FACTOR = 4L
