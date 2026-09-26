// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Supplies the OpenBao token for a Transit call. [invalidate] is how [OpenBaoTransitFieldProtector]
 * reports a 403: a caching source drops the rejected token so the protector's single retry logs in
 * afresh. A plain lambda (`OpenBaoTokenSource { token }`) is a static token that ignores it.
 */
fun interface OpenBaoTokenSource {
    fun token(): String

    fun invalidate(rejected: String) {}
}

/** A client token and the `auth.lease_duration` OpenBao issued it with. */
class OpenBaoToken(val clientToken: String, val leaseDuration: Duration) {
    override fun toString(): String = "OpenBaoToken(leaseDuration=$leaseDuration)"
}

/**
 * Caches the token from [login] (typically [OpenBaoKubernetesLogin.login]) for its lease, so N
 * Transit calls cost ONE login rather than N. Refreshes once the remaining lease falls under
 * [refreshMargin] (or [refreshFraction] of the lease, whichever is larger), with a single-flight
 * lock: concurrent callers during a refresh wait for the one login in progress instead of each
 * starting their own. A token with `lease_duration` 0 (non-expiring root/periodic) is cached until
 * [invalidate]. A failed login is never cached; it throws [FieldProtectionException] each time.
 */
class CachingOpenBaoTokenSource(
    private val login: () -> OpenBaoToken,
    private val clock: Clock = Clock.systemUTC(),
    private val refreshMargin: Duration = Duration.ofSeconds(DEFAULT_REFRESH_MARGIN_SECONDS),
    private val refreshFraction: Double = DEFAULT_REFRESH_FRACTION,
) : OpenBaoTokenSource {

    private class Cached(val token: String, val refreshAt: Instant?)

    private val lock = ReentrantLock()

    @Volatile private var cached: Cached? = null

    override fun token(): String {
        fresh()?.let { return it }
        return lock.withLock {
            fresh() ?: loginAndCache()
        }
    }

    override fun invalidate(rejected: String) {
        lock.withLock {
            if (cached?.token == rejected) cached = null
        }
    }

    private fun fresh(): String? {
        val c = cached ?: return null
        return if (c.refreshAt == null || clock.instant().isBefore(c.refreshAt)) c.token else null
    }

    private fun loginAndCache(): String {
        val t = login()
        val lease = t.leaseDuration
        val refreshAt = if (lease.isZero || lease.isNegative) {
            null
        } else {
            val early = maxOf(refreshMargin, Duration.ofMillis((lease.toMillis() * refreshFraction).toLong()))
            clock.instant().plus(lease.minus(minOf(early, lease)))
        }
        cached = Cached(t.clientToken, refreshAt)
        return t.clientToken
    }

    companion object {
        const val DEFAULT_REFRESH_MARGIN_SECONDS = 60L
        const val DEFAULT_REFRESH_FRACTION = 0.1
    }
}
