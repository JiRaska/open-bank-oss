// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.scheduler

import com.openbank.account.application.usecase.AccountNumberReissueService
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs [AccountNumberReissueService] and publishes `openbank.account.number_invalid` — the number of
 * accounts whose stored CZ number still fails the ČNB mod-11 check, re-counted from the store after
 * every pass. That gauge is the post-deploy proof: it must read 0, and -1 means "not measured yet",
 * never "none". `suspend fun` so the Vert.x context the reactive store needs exists (see the
 * `@Scheduled` note in CLAUDE.md). OFF unless a deployment turns it on.
 */
@Startup
@ApplicationScoped
class AccountNumberReissueJob(
    private val service: AccountNumberReissueService,
    private val registry: MeterRegistry,
    @ConfigProperty(name = "openbank.account.number-reissue.enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    private val log = Logger.getLogger(AccountNumberReissueJob::class.java)
    private val invalid = AtomicLong(NOT_MEASURED)

    @PostConstruct
    fun register() {
        Gauge.builder(INVALID_METRIC, invalid) { it.get().toDouble() }
            .description("Accounts whose CZ account number fails the CNB mod-11 check; -1 until first measured")
            .tag("service", "account")
            .register(registry)
    }

    @Scheduled(
        every = "{openbank.account.number-reissue.interval:10m}",
        delayed = "{openbank.account.number-reissue.initial-delay:30s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "account-number-reissue",
    )
    @Suppress("TooGenericExceptionCaught") // any failure is the same "not measured this tick"
    suspend fun tick() {
        if (!enabled) return
        try {
            val report = service.reissueInvalid()
            invalid.set(report.remaining.toLong())
            log.infof(
                "account-number reissue: scanned=%d reissued=%d skipped=%d failed=%d remaining=%d",
                report.scanned,
                report.reissued,
                report.skipped,
                report.failed,
                report.remaining,
            )
        } catch (e: Exception) {
            log.warnf("account-number reissue pass failed; gauge keeps its last value: %s", e.toString())
        }
    }

    companion object {
        const val INVALID_METRIC = "openbank.account.number_invalid"
        const val NOT_MEASURED = -1L
    }
}
