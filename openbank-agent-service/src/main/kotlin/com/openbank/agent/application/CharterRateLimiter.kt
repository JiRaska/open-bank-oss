// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.application

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Enforces the per-agent charter limits declared in [CharterRegistry] (ADR-0031 D2):
 *  - **tokens_per_run**: checked at the end of each chat() invocation; if exceeded the
 *    response is replaced with a limit-exceeded message (the run already happened, but
 *    callers see the limit enforced from the next turn or via the error message).
 *    For strict pre-flight enforcement the gateway would need to estimate tokens up-front,
 *    which is model-dependent; post-run enforcement is accurate and simpler.
 *  - **runs_per_day**: checked at the START of chat(); an over-limit request is rejected
 *    immediately with a clear message and is NOT billed against the model.
 *
 * State is in-memory: a pod restart resets counters. Distributed enforcement (Redis /
 * external counter) is a follow-up for multi-replica deployments.
 */
@ApplicationScoped
class CharterRateLimiter(private val clock: Clock) {

    @Inject
    lateinit var registry: CharterRegistry

    private val log = Logger.getLogger(CharterRateLimiter::class.java)

    // Key: "agentId:YYYY-MM-DD" → run count that day (UTC)
    private val runCounters = ConcurrentHashMap<String, AtomicLong>()

    /** Returns a non-null error message if the agent is over its runs-per-day limit. */
    fun checkRunsPerDay(agentId: String): String? {
        val limit = registry.runsPerDay(agentId)
        if (limit == Long.MAX_VALUE) return null
        val key = "$agentId:${LocalDate.now(clock)}"
        val count = runCounters.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        return if (count > limit) {
            log.warnf("charter D2: agent=%s runs_per_day limit=%d reached (%d)", agentId, limit, count)
            "Charter limit reached: this assistant may only respond $limit times per day. " +
                "Quota resets at midnight UTC."
        } else {
            null
        }
    }

    /** Returns a non-null warning if the run consumed more tokens than the charter allows. */
    fun checkTokensPerRun(agentId: String, tokensUsed: Long): String? {
        val limit = registry.tokensPerRun(agentId)
        if (limit == Long.MAX_VALUE || tokensUsed <= limit) return null
        log.warnf("charter D2: agent=%s tokens_per_run limit=%d consumed=%d", agentId, limit, tokensUsed)
        return "(This response consumed $tokensUsed tokens, exceeding the charter limit of $limit. " +
            "Please narrow your query to stay within the allowed budget.)"
    }
}
